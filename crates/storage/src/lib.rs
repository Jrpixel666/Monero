//! Encrypted-at-rest wallet storage.
//!
//! The on-disk envelope is: `MAGIC(4) | VERSION(1) | m_cost(4) | t_cost(4) | p_cost(1) | salt(16)
//! | nonce(24) | ciphertext(..) || tag(16)`. KDF parameters are stored in the header (not just
//! hardcoded) so a future version can raise them for new wallets without breaking the ability to
//! open old files with their original parameters.
//!
//! No custom cryptography: key stretching is Argon2id (`argon2` crate), authenticated encryption
//! is XChaCha20-Poly1305 (`chacha20poly1305` crate, RustCrypto). This crate only defines the
//! envelope format and parameter choices around them.
#![forbid(unsafe_code)]
#![warn(missing_docs)]

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::{
  aead::{Aead as _, KeyInit as _},
  Key, XChaCha20Poly1305, XNonce,
};
use rand_core::{OsRng, RngCore as _};
use thiserror::Error;
use zeroize::Zeroizing;

const MAGIC: [u8; 4] = *b"MXWF";
const VERSION: u8 = 1;
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 24;
const KEY_LEN: usize = 32;
const HEADER_LEN: usize = 4 + 1 + 4 + 4 + 1 + SALT_LEN + NONCE_LEN;

/// OWASP-recommended Argon2id parameters (second recommended option: 19 MiB, 2 iterations, 1
/// degree of parallelism). Stored in the header rather than assumed, so this can be raised later.
const DEFAULT_M_COST_KIB: u32 = 19 * 1024;
const DEFAULT_T_COST: u32 = 2;
const DEFAULT_P_COST: u8 = 1;

/// Hard ceilings on the Argon2id parameters a *decrypted* envelope's header is allowed to
/// request, found by fuzzing: `m_cost`/`t_cost` are read directly from untrusted file bytes and
/// handed to `argon2::Params::new`, which trusts them. Before this bound existed, a corrupted or
/// malicious wallet file (e.g. a tampered backup) could set `m_cost` high enough to request a
/// multi-terabyte allocation, aborting the process outright (confirmed via `fuzz/`:
/// `cargo +nightly fuzz run fuzz_storage_decrypt`). These ceilings are generous — 16x this
/// crate's own default — specifically so they never reject a file this crate itself produced,
/// only ones crafted or corrupted to be hostile.
const MAX_M_COST_KIB: u32 = DEFAULT_M_COST_KIB * 16;
const MAX_T_COST: u32 = 64;
const MAX_P_COST: u8 = 16;

// A file this crate produces must always be decryptable by this crate's own bounds check.
const _: () = assert!(DEFAULT_M_COST_KIB <= MAX_M_COST_KIB);
const _: () = assert!(DEFAULT_T_COST <= MAX_T_COST);
const _: () = assert!(DEFAULT_P_COST <= MAX_P_COST);

/// Errors opening or creating an encrypted wallet file.
#[derive(Debug, Error)]
pub enum StorageError {
  /// The envelope was too short, had the wrong magic bytes, or used an unsupported version.
  #[error("not a recognized wallet file")]
  NotAWalletFile,
  /// Key derivation failed (e.g. parameters in the file are internally inconsistent).
  #[error("key derivation failed")]
  KeyDerivation,
  /// The envelope's header requested Argon2id parameters past this crate's sanity ceiling —
  /// either corrupted or crafted to make opening the file expensive or crash the process.
  #[error("wallet file requested unreasonable key-derivation parameters")]
  ParametersOutOfBounds,
  /// Decryption failed. Deliberately not distinguished from "file corrupted" to avoid acting as
  /// a password-correctness oracle beyond what AEAD failure already reveals.
  #[error("incorrect password or corrupted wallet file")]
  InvalidPasswordOrCorrupted,
}

fn derive_key(
  password: &[u8],
  salt: &[u8; SALT_LEN],
  m_cost: u32,
  t_cost: u32,
  p_cost: u8,
) -> Result<Zeroizing<[u8; KEY_LEN]>, StorageError> {
  let params = Params::new(m_cost, t_cost, u32::from(p_cost), Some(KEY_LEN))
    .map_err(|_| StorageError::KeyDerivation)?;
  let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
  let mut key = Zeroizing::new([0u8; KEY_LEN]);
  argon2.hash_password_into(password, salt, &mut *key).map_err(|_| StorageError::KeyDerivation)?;
  Ok(key)
}

/// Encrypt `plaintext` (e.g. a serialized wallet spend key) under `password`, using fresh random
/// salt and nonce, and the current default Argon2id parameters.
pub fn encrypt(password: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, StorageError> {
  let mut salt = [0u8; SALT_LEN];
  OsRng.fill_bytes(&mut salt);
  let mut nonce_bytes = [0u8; NONCE_LEN];
  OsRng.fill_bytes(&mut nonce_bytes);

  let key = derive_key(password, &salt, DEFAULT_M_COST_KIB, DEFAULT_T_COST, DEFAULT_P_COST)?;
  let cipher = XChaCha20Poly1305::new(Key::from_slice(&*key));
  let nonce = XNonce::from_slice(&nonce_bytes);
  let ciphertext = cipher.encrypt(nonce, plaintext).map_err(|_| StorageError::KeyDerivation)?;

  let mut out = Vec::with_capacity(HEADER_LEN + ciphertext.len());
  out.extend_from_slice(&MAGIC);
  out.push(VERSION);
  out.extend_from_slice(&DEFAULT_M_COST_KIB.to_le_bytes());
  out.extend_from_slice(&DEFAULT_T_COST.to_le_bytes());
  out.push(DEFAULT_P_COST);
  out.extend_from_slice(&salt);
  out.extend_from_slice(&nonce_bytes);
  out.extend_from_slice(&ciphertext);
  Ok(out)
}

/// Decrypt an envelope produced by [`encrypt`]. Returns the plaintext in a zeroizing buffer.
pub fn decrypt(password: &[u8], envelope: &[u8]) -> Result<Zeroizing<Vec<u8>>, StorageError> {
  if envelope.len() < HEADER_LEN || envelope[0..4] != MAGIC {
    return Err(StorageError::NotAWalletFile);
  }
  if envelope[4] != VERSION {
    return Err(StorageError::NotAWalletFile);
  }

  let m_cost = u32::from_le_bytes(envelope[5..9].try_into().unwrap());
  let t_cost = u32::from_le_bytes(envelope[9..13].try_into().unwrap());
  let p_cost = envelope[13];
  if m_cost > MAX_M_COST_KIB || t_cost > MAX_T_COST || p_cost > MAX_P_COST {
    return Err(StorageError::ParametersOutOfBounds);
  }
  let salt: [u8; SALT_LEN] = envelope[14..14 + SALT_LEN].try_into().unwrap();
  let nonce_start = 14 + SALT_LEN;
  let nonce_bytes: [u8; NONCE_LEN] =
    envelope[nonce_start..nonce_start + NONCE_LEN].try_into().unwrap();
  let ciphertext = &envelope[nonce_start + NONCE_LEN..];

  let key = derive_key(password, &salt, m_cost, t_cost, p_cost)?;
  let cipher = XChaCha20Poly1305::new(Key::from_slice(&*key));
  let nonce = XNonce::from_slice(&nonce_bytes);

  cipher
    .decrypt(nonce, ciphertext)
    .map(Zeroizing::new)
    .map_err(|_| StorageError::InvalidPasswordOrCorrupted)
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn round_trips() {
    let envelope =
      encrypt(b"correct horse battery staple", b"super secret spend key bytes").unwrap();
    let plaintext = decrypt(b"correct horse battery staple", &envelope).unwrap();
    assert_eq!(&*plaintext, b"super secret spend key bytes");
  }

  #[test]
  fn wrong_password_fails() {
    let envelope = encrypt(b"correct horse battery staple", b"secret").unwrap();
    assert!(matches!(
      decrypt(b"wrong password", &envelope),
      Err(StorageError::InvalidPasswordOrCorrupted)
    ));
  }

  #[test]
  fn tampered_ciphertext_fails() {
    let mut envelope = encrypt(b"password", b"secret").unwrap();
    let last = envelope.len() - 1;
    envelope[last] ^= 0x01;
    assert!(matches!(
      decrypt(b"password", &envelope),
      Err(StorageError::InvalidPasswordOrCorrupted)
    ));
  }

  #[test]
  fn garbage_input_is_rejected_as_not_a_wallet_file() {
    assert!(matches!(
      decrypt(b"password", b"not a wallet file"),
      Err(StorageError::NotAWalletFile)
    ));
  }

  #[test]
  fn two_encryptions_of_the_same_plaintext_differ() {
    let a = encrypt(b"password", b"secret").unwrap();
    let b = encrypt(b"password", b"secret").unwrap();
    assert_ne!(a, b, "fresh salt/nonce must make ciphertexts unlinkable");
  }

  /// Regression test for a crash `fuzz/fuzz_targets/fuzz_storage_decrypt.rs` found: a header
  /// with an absurd `m_cost` used to reach `argon2::Params::new`/`hash_password_into` and abort
  /// the process attempting a multi-terabyte allocation, rather than returning an `Err`.
  #[test]
  fn absurd_m_cost_in_the_header_is_rejected_without_attempting_key_derivation() {
    let mut envelope = encrypt(b"password", b"secret").unwrap();
    envelope[5..9].copy_from_slice(&u32::MAX.to_le_bytes()); // m_cost
    assert!(matches!(decrypt(b"password", &envelope), Err(StorageError::ParametersOutOfBounds)));
  }

  #[test]
  fn absurd_t_cost_in_the_header_is_rejected() {
    let mut envelope = encrypt(b"password", b"secret").unwrap();
    envelope[9..13].copy_from_slice(&u32::MAX.to_le_bytes()); // t_cost
    assert!(matches!(decrypt(b"password", &envelope), Err(StorageError::ParametersOutOfBounds)));
  }

  #[test]
  fn absurd_p_cost_in_the_header_is_rejected() {
    let mut envelope = encrypt(b"password", b"secret").unwrap();
    envelope[13] = u8::MAX; // p_cost
    assert!(matches!(decrypt(b"password", &envelope), Err(StorageError::ParametersOutOfBounds)));
  }
}
