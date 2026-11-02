//! UniFFI bindings exposing `wallet-core` and `storage` to Kotlin.
//!
//! Design rule: the raw spend-key scalar never crosses this boundary, in either direction.
//! Restoring a wallet takes a *password plus an encrypted envelope*; exporting one returns an
//! *encrypted envelope*. Decryption/encryption both happen on the Rust side via `storage`, so
//! there is no FFI call whose Kotlin-side return value is the plaintext key.
//!
//! What's still a known gap for stage 3: the `password: String` parameters below are plain Kotlin
//! strings, which the JVM cannot guarantee gets zeroized/GC'd promptly. Passing passwords as a
//! byte array the Kotlin side clears itself after the call is the fix, and belongs with the rest
//! of the Android security hardening work, not bolted on here.
#![forbid(unsafe_code)]

use std::sync::Arc;

use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

/// Errors surfaced across the FFI boundary. Deliberately flattened to a message string per
/// category — the Kotlin side has no use for `wallet-core`'s internal error variants, only for
/// telling the user what went wrong.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum WalletError {
  /// A problem with key material itself (malformed bytes, torsioned key).
  #[error("{0}")]
  Key(String),
  /// A problem opening or creating the encrypted wallet envelope.
  #[error("{0}")]
  Storage(String),
}

impl From<wallet_core::keys::KeyError> for WalletError {
  fn from(e: wallet_core::keys::KeyError) -> Self {
    WalletError::Key(e.to_string())
  }
}

impl From<storage::StorageError> for WalletError {
  fn from(e: storage::StorageError) -> Self {
    WalletError::Storage(e.to_string())
  }
}

/// The Monero network an address is derived for.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum FfiNetwork {
  /// Mainnet.
  Mainnet,
  /// Testnet.
  Testnet,
  /// Stagenet.
  Stagenet,
}

impl From<FfiNetwork> for wallet_core::Network {
  fn from(network: FfiNetwork) -> Self {
    match network {
      FfiNetwork::Mainnet => wallet_core::Network::Mainnet,
      FfiNetwork::Testnet => wallet_core::Network::Testnet,
      FfiNetwork::Stagenet => wallet_core::Network::Stagenet,
    }
  }
}

/// A Monero wallet's spend/view key pair, held in Rust-managed memory for its entire lifetime.
#[derive(uniffi::Object)]
pub struct FfiWallet {
  key: wallet_core::keys::SpendKey,
}

#[uniffi::export]
impl FfiWallet {
  /// Generate a brand-new wallet from the OS CSPRNG.
  #[uniffi::constructor]
  pub fn generate() -> Arc<Self> {
    Arc::new(Self { key: wallet_core::keys::SpendKey::generate() })
  }

  /// Restore a wallet from an envelope previously produced by [`FfiWallet::export_encrypted`].
  /// The decrypted key bytes live only inside this call's stack frame.
  #[uniffi::constructor]
  pub fn restore_from_encrypted(password: String, envelope: Vec<u8>) -> Result<Arc<Self>, WalletError> {
    let plaintext = storage::decrypt(password.as_bytes(), &envelope)?;
    if plaintext.len() != 32 {
      return Err(WalletError::Key("decrypted key material was not 32 bytes".to_owned()));
    }
    let mut spend_bytes = Zeroizing::new([0u8; 32]);
    spend_bytes.copy_from_slice(&plaintext);
    let key = wallet_core::keys::SpendKey::from_canonical_bytes(spend_bytes)?;
    Ok(Arc::new(Self { key }))
  }

  /// Encrypt this wallet's spend key under `password`, returning a self-contained envelope
  /// (Argon2id params + salt + nonce + ciphertext) suitable for writing straight to disk.
  pub fn export_encrypted(&self, password: String) -> Result<Vec<u8>, WalletError> {
    let bytes = self.key.to_canonical_bytes();
    Ok(storage::encrypt(password.as_bytes(), &*bytes)?)
  }

  /// This wallet's primary (non-sub) address, base58-encoded, for the given network.
  pub fn primary_address(&self, network: FfiNetwork) -> Result<String, WalletError> {
    Ok(self.key.primary_address(network.into())?.to_string())
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn generate_export_restore_round_trip_preserves_the_address() {
    let wallet = FfiWallet::generate();
    let address_before = wallet.primary_address(FfiNetwork::Mainnet).unwrap();

    let envelope = wallet.export_encrypted("a strong password".to_owned()).unwrap();
    let restored = FfiWallet::restore_from_encrypted("a strong password".to_owned(), envelope).unwrap();

    assert_eq!(address_before, restored.primary_address(FfiNetwork::Mainnet).unwrap());
  }

  #[test]
  fn restore_with_wrong_password_is_rejected() {
    let wallet = FfiWallet::generate();
    let envelope = wallet.export_encrypted("correct".to_owned()).unwrap();
    assert!(FfiWallet::restore_from_encrypted("incorrect".to_owned(), envelope).is_err());
  }
}
