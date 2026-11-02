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

/// Errors opening or creating an encrypted wallet file.
#[derive(Debug, Error)]
pub enum StorageError {
  /// The envelope was too short, had the wrong magic bytes, or used an unsupported version.
  #[error("not a recognized wallet file")]
  NotAWalletFile,
  /// Key derivation failed (e.g. parameters in the file are internally inconsistent).
  #[error("key derivation failed")]
  KeyDerivation,
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
  let params =
    Params::new(m_cost, t_cost, u32::from(p_cost), Some(KEY_LEN)).map_err(|_| StorageError::KeyDerivation)?;
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
  let ciphertext =
    cipher.encrypt(nonce, plaintext).map_err(|_| StorageError::KeyDerivation)?;

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
    let envelope = encrypt(b"correct horse battery staple", b"super secret spend key bytes").unwrap();
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
    assert!(matches!(decrypt(b"password", b"not a wallet file"), Err(StorageError::NotAWalletFile)));
  }

  #[test]
  fn two_encryptions_of_the_same_plaintext_differ() {
    let a = encrypt(b"password", b"secret").unwrap();
    let b = encrypt(b"password", b"secret").unwrap();
    assert_ne!(a, b, "fresh salt/nonce must make ciphertexts unlinkable");
  }
}
