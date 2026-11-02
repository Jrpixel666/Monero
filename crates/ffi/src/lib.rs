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

use std::sync::{Arc, OnceLock};

use tokio::runtime::Runtime;
use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

/// `rpc`'s async daemon calls are built on real Tokio I/O internally (via
/// `monero-simple-request-rpc`'s use of hyper/Tokio), which needs an actual Tokio reactor
/// polling them — UniFFI's async export mechanism itself is executor-agnostic and doesn't
/// provide one. This background runtime bridges the two: exported async functions `spawn` onto
/// it and `.await` the `JoinHandle`, so the real I/O runs on this runtime's worker threads
/// regardless of what (if anything) is driving the outer future on the Kotlin side.
fn background_runtime() -> &'static Runtime {
  static RUNTIME: OnceLock<Runtime> = OnceLock::new();
  RUNTIME.get_or_init(|| Runtime::new().expect("failed to start background Tokio runtime"))
}

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
  /// A problem talking to a daemon.
  #[error("{0}")]
  Network(String),
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

impl From<rpc::RpcError> for WalletError {
  fn from(e: rpc::RpcError) -> Self {
    WalletError::Network(e.to_string())
  }
}

/// Connect to a Monero daemon at `daemon_url` (e.g. `"https://xmr.example.com"`) and return its
/// current chain height. This is a direct, unauthenticated clearnet connection — see
/// `crates/rpc`'s module docs for why it isn't routed through Tor yet.
///
/// Every value this returns is untrusted daemon input; `network::HeightTracker` (not exposed
/// over FFI yet — there's no sync session to hold one across calls until stage 5) is how a real
/// sync loop should guard against a height that regresses between calls.
#[uniffi::export]
pub async fn fetch_chain_height(daemon_url: String) -> Result<u64, WalletError> {
  let result = background_runtime()
    .spawn(async move {
      let client = rpc::DaemonClient::connect(daemon_url).await?;
      client.latest_block_number().await
    })
    .await;

  match result {
    Ok(inner) => inner.map_err(WalletError::from),
    Err(_join_error) => Err(WalletError::Network("background RPC task panicked".to_owned())),
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

  #[tokio::test]
  #[ignore = "hits a real third-party Monero node; opt in with: cargo test -p ffi -- --ignored"]
  async fn fetch_chain_height_reaches_a_real_node() {
    let height = fetch_chain_height("https://xmr.kret.dev".to_owned()).await.unwrap();
    assert!(height > 3_000_000, "mainnet should be well past block 3,000,000 by now");
  }
}
