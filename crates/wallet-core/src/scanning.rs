//! Output scanning.
//!
//! This is a thin wrapper around [`monero_wallet::Scanner`]. The actual trial-decryption and
//! output-ownership logic is entirely `monero-oxide`'s; this module only owns the policy of which
//! subaddresses a given wallet watches.
//!
//! Feeding this real block data requires a daemon connection (`network`, Stage 4) and is exercised
//! end-to-end against a regtest node rather than with synthetic blocks here (see `README.md`,
//! Testing stage) — constructing a valid `ScannableBlock` by hand would just duplicate
//! monero-oxide's own block-parsing tests without adding assurance.
#![forbid(unsafe_code)]

use monero_wallet::{
  address::SubaddressIndex, interface::ScannableBlock, ScanError, Scanner, Timelocked, ViewPair,
  WalletOutput,
};

/// Watches one wallet's registered subaddresses across scanned blocks.
pub struct WalletScanner(Scanner);

impl WalletScanner {
  /// Create a scanner for the given view pair. The primary address (account 0, index 0) is
  /// watched implicitly by `monero-oxide`; call [`WalletScanner::watch_subaddress`] for any
  /// others the wallet has issued.
  pub fn new(view_pair: ViewPair) -> Self {
    Self(Scanner::new(view_pair))
  }

  /// Start watching an additional subaddress (e.g. a newly-created account or address index).
  pub fn watch_subaddress(&mut self, index: SubaddressIndex) {
    self.0.register_subaddress(index);
  }

  /// Scan a block already fetched and validated from a daemon, returning any outputs owned by
  /// this wallet's watched addresses, still subject to their own timelocks.
  pub fn scan_block(&mut self, block: ScannableBlock) -> Result<Timelocked, ScanError> {
    self.0.scan(block)
  }
}

/// Outputs that have cleared their timelock and are safe to spend.
pub fn spendable_outputs(timelocked: Timelocked) -> Vec<WalletOutput> {
  timelocked.not_additionally_locked()
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::keys::SpendKey;

  #[test]
  fn scanner_construction_and_subaddress_registration_do_not_panic() {
    let key = SpendKey::generate();
    let mut scanner = WalletScanner::new(key.view_pair().unwrap());
    scanner.watch_subaddress(SubaddressIndex::new(0, 1).unwrap());
    scanner.watch_subaddress(SubaddressIndex::new(1, 0).unwrap());
  }
}
