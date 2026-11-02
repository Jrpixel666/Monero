//! The wallet's secret key hierarchy.
//!
//! Monero derives a wallet deterministically from one 32-byte spend scalar: the private view key
//! is `Hs(spend_scalar)`, and every subaddress and public key falls out of that pair. This module
//! holds that scalar in a zeroizing wrapper and never exposes it except through
//! [`SpendKey::to_canonical_bytes`], which callers must immediately hand to `storage` for
//! encryption rather than holding onto themselves.
//!
//! Seed-phrase (25-word / Polyseed) encode-decode is intentionally **not** implemented here. The
//! only Monero seed-word crates (`monero-seed`, `monero-polyseed`) live outside the audited
//! `monero-oxide` repository and carry a weaker maintenance guarantee; wiring them in is Stage 2
//! work, done deliberately rather than by hand-rolling a wordlist/checksum scheme from memory.

use curve25519_dalek::constants::ED25519_BASEPOINT_POINT;
use rand_core::{OsRng, RngCore as _};
use thiserror::Error;
use zeroize::Zeroizing;

use monero_wallet::{
  address::{MoneroAddress, Network, SubaddressIndex},
  ed25519::{CompressedPoint, Point, Scalar},
  ViewPair, ViewPairError,
};

/// Errors constructing or using a [`SpendKey`] or [`ViewOnlyWallet`].
#[derive(Debug, Error)]
pub enum KeyError {
  /// The supplied bytes were not a canonical, validly-encoded scalar.
  #[error("malformed key encoding: {0}")]
  Malformed(std::io::Error),
  /// The supplied bytes decoded but do not encode a valid point on the curve.
  #[error("invalid point encoding")]
  InvalidPoint,
  /// The derived view pair was invalid (torsioned spend key).
  #[error(transparent)]
  ViewPair(#[from] ViewPairError),
}

/// The wallet's master spend key.
///
/// This is the single secret that must never leave process memory unencrypted. It is held in a
/// [`Zeroizing`] wrapper so it is wiped on drop, and its `Debug` impl is redacted.
pub struct SpendKey(Zeroizing<Scalar>);

impl core::fmt::Debug for SpendKey {
  fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
    f.debug_struct("SpendKey").field("scalar", &"<redacted>").finish()
  }
}

impl SpendKey {
  /// Generate a new, random spend key from the OS CSPRNG.
  ///
  /// 64 bytes of randomness are hashed down to a scalar (rather than reading exactly 32 and
  /// reducing them) to avoid any bias from naive modular reduction of a uniform 32-byte string.
  pub fn generate() -> Self {
    let mut entropy = Zeroizing::new([0u8; 64]);
    OsRng.fill_bytes(&mut *entropy);
    Self(Zeroizing::new(Scalar::hash(&entropy[..])))
  }

  /// Reconstruct a spend key from its canonical 32-byte scalar encoding.
  ///
  /// This performs no provenance check. Restoring from untrusted/external bytes is the caller's
  /// responsibility to pair with a scan-based sanity check before the result is trusted.
  pub fn from_canonical_bytes(bytes: Zeroizing<[u8; 32]>) -> Result<Self, KeyError> {
    let mut reader: &[u8] = &bytes[..];
    Scalar::read(&mut reader).map(Zeroizing::new).map(Self).map_err(KeyError::Malformed)
  }

  /// Serialize to the canonical 32-byte scalar encoding.
  ///
  /// Callers must pass this directly to `storage::encrypt` and must not hold or copy it any
  /// longer than that single call requires.
  pub fn to_canonical_bytes(&self) -> Zeroizing<[u8; 32]> {
    let mut buf = Zeroizing::new(Vec::with_capacity(32));
    self.0.write(&mut *buf).expect("writing to an in-memory Vec cannot fail");
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&buf);
    out
  }

  fn public_spend_point(&self) -> Point {
    let dalek_scalar: curve25519_dalek::Scalar = (*self.0).into();
    Point::from(ED25519_BASEPOINT_POINT * dalek_scalar)
  }

  /// The deterministic private view key, `Hs(spend_scalar)`.
  fn private_view_key(&self) -> Zeroizing<Scalar> {
    let mut spend_bytes = Zeroizing::new(Vec::with_capacity(32));
    self.0.write(&mut *spend_bytes).expect("writing to an in-memory Vec cannot fail");
    Zeroizing::new(Scalar::hash(&spend_bytes[..]))
  }

  /// Derive the `monero-oxide` [`ViewPair`] used for scanning and address generation.
  pub fn view_pair(&self) -> Result<ViewPair, KeyError> {
    Ok(ViewPair::new(self.public_spend_point(), self.private_view_key())?)
  }

  /// The account's primary (legacy, non-sub) address.
  ///
  /// Subaddresses should be preferred for anything handed to a counterparty; this exists because
  /// every Monero wallet has exactly one of these and tooling expects it.
  pub fn primary_address(&self, network: Network) -> Result<MoneroAddress, KeyError> {
    Ok(self.view_pair()?.legacy_address(network))
  }

  /// Derive a subaddress at the given account/address index.
  pub fn subaddress(
    &self,
    network: Network,
    index: SubaddressIndex,
  ) -> Result<MoneroAddress, KeyError> {
    Ok(self.view_pair()?.subaddress(network, index))
  }

  /// This wallet's public spend key. Safe to share — every address already encodes it — and,
  /// combined with [`SpendKey::export_private_view_key`], what a [`ViewOnlyWallet`] companion is
  /// built from.
  pub fn public_spend_key_bytes(&self) -> [u8; 32] {
    self.public_spend_point().compress().to_bytes()
  }

  /// Export the private view key. Unlike the spend key, sharing this is a deliberate, named
  /// action a real wallet exposes deliberately — e.g. for an auditor or a watch-only companion
  /// device — since it lets the holder see incoming funds, not spend them.
  pub fn export_private_view_key(&self) -> Zeroizing<[u8; 32]> {
    let view_scalar = self.private_view_key();
    let mut buf = Zeroizing::new(Vec::with_capacity(32));
    view_scalar.write(&mut *buf).expect("writing to an in-memory Vec cannot fail");
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&buf);
    out
  }
}

/// A view-only wallet: can derive addresses and (once wired to a scanner) detect incoming
/// outputs, but holds no spend key and so cannot sign a transaction. Built from a public spend
/// key and a private view key — see [`SpendKey::public_spend_key_bytes`] and
/// [`SpendKey::export_private_view_key`].
pub struct ViewOnlyWallet {
  view_pair: ViewPair,
}

impl ViewOnlyWallet {
  /// Construct from a public spend key and private view key, as exported by a full [`SpendKey`].
  pub fn from_keys(
    public_spend_key: [u8; 32],
    private_view_key: Zeroizing<[u8; 32]>,
  ) -> Result<Self, KeyError> {
    let mut spend_reader: &[u8] = &public_spend_key[..];
    let spend_point = CompressedPoint::read(&mut spend_reader)
      .map_err(KeyError::Malformed)?
      .decompress()
      .ok_or(KeyError::InvalidPoint)?;

    let mut view_reader: &[u8] = &private_view_key[..];
    let view_scalar = Zeroizing::new(Scalar::read(&mut view_reader).map_err(KeyError::Malformed)?);

    Ok(Self { view_pair: ViewPair::new(spend_point, view_scalar)? })
  }

  /// The underlying `monero-oxide` view pair, e.g. to hand to a [`crate::scanning::WalletScanner`].
  pub fn view_pair(&self) -> &ViewPair {
    &self.view_pair
  }

  /// This wallet's primary (non-sub) address.
  pub fn primary_address(&self, network: Network) -> MoneroAddress {
    self.view_pair.legacy_address(network)
  }

  /// Derive a subaddress at the given account/address index.
  pub fn subaddress(&self, network: Network, index: SubaddressIndex) -> MoneroAddress {
    self.view_pair.subaddress(network, index)
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn generate_derives_a_valid_view_pair_and_address() {
    let key = SpendKey::generate();
    let view_pair = key.view_pair().expect("freshly generated key must be torsion-free");
    let address = key.primary_address(Network::Mainnet).unwrap();
    assert_eq!(address.spend(), view_pair.spend());
  }

  #[test]
  fn round_trips_through_canonical_bytes() {
    let key = SpendKey::generate();
    let original_address = key.primary_address(Network::Mainnet).unwrap();

    let bytes = key.to_canonical_bytes();
    let restored = SpendKey::from_canonical_bytes(bytes).unwrap();
    let restored_address = restored.primary_address(Network::Mainnet).unwrap();

    assert_eq!(original_address, restored_address);
  }

  #[test]
  fn two_generated_keys_differ() {
    let a = SpendKey::generate();
    let b = SpendKey::generate();
    assert_ne!(a.to_canonical_bytes(), b.to_canonical_bytes());
  }

  #[test]
  fn subaddress_differs_from_primary_address() {
    let key = SpendKey::generate();
    let primary = key.primary_address(Network::Mainnet).unwrap();
    let sub = key.subaddress(Network::Mainnet, SubaddressIndex::new(0, 1).unwrap()).unwrap();
    assert_ne!(primary, sub);
  }

  #[test]
  fn debug_output_does_not_leak_the_scalar() {
    let key = SpendKey::generate();
    let rendered = format!("{key:?}");
    assert!(!rendered.contains("scalar: Scalar"));
    assert!(rendered.contains("redacted"));
  }

  #[test]
  fn view_only_wallet_derives_the_same_addresses_as_the_full_wallet() {
    let key = SpendKey::generate();
    let view_only =
      ViewOnlyWallet::from_keys(key.public_spend_key_bytes(), key.export_private_view_key())
        .unwrap();

    assert_eq!(
      key.primary_address(Network::Mainnet).unwrap(),
      view_only.primary_address(Network::Mainnet)
    );

    let index = SubaddressIndex::new(1, 2).unwrap();
    assert_eq!(
      key.subaddress(Network::Mainnet, index).unwrap(),
      view_only.subaddress(Network::Mainnet, index)
    );
  }

  #[test]
  fn view_only_wallet_rejects_garbage_point_bytes() {
    // All-0xFF is not a valid compressed Edwards point encoding.
    let garbage_spend_key = [0xFFu8; 32];
    let key = SpendKey::generate();
    assert!(ViewOnlyWallet::from_keys(garbage_spend_key, key.export_private_view_key()).is_err());
  }
}
