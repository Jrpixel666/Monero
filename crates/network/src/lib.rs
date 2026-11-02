//! Daemon RPC trust boundary.
//!
//! **Stage boundary**: this crate currently defines the trust model and the trait surface for
//! talking to a Monero daemon. The actual transport — Tor via Arti, TLS pinning for clearnet
//! fallback, wiring to `monero-simple-request-rpc`, foreground-service-scheduled sync — is Stage
//! 4 (Networking & Privacy) of the project plan in `README.md`. Shipping a trait with no real
//! transport yet is deliberate: it is the seam the FFI and sync-service layers will be built
//! against, and it lets the trust rules below be reviewed and tested before any network code
//! exists to get those rules wrong.
//!
//! The core rule: a daemon — remote or local, chosen by the user or bundled — is **never**
//! trusted. Every response is untrusted input. Consensus-relevant validation happens in
//! `wallet-core` (via `monero-oxide`), not here and not on the node's say-so.
#![forbid(unsafe_code)]
#![warn(missing_docs)]

use thiserror::Error;

/// A client capable of querying a Monero daemon.
///
/// Implementations do not need to validate responses beyond basic structural decoding — ownership
/// of "is this consensus-valid" belongs to `wallet-core`. What implementations must not do is
/// silently swallow an inconsistency (e.g. a height that moved backward) that would let a
/// malicious node roll the wallet's view of the chain back; see [`check_height_monotonic`].
pub trait DaemonRpc {
  /// The error type for this client's transport.
  type Error: core::fmt::Debug;

  /// The daemon's current reported chain height.
  fn get_height(&self) -> Result<u64, Self::Error>;
}

/// A trust violation detected in a daemon's response, independent of transport.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum NodeTrustError {
  /// The node reported a height lower than one it, or another node, already reported.
  #[error("daemon reported chain height {new} after previously reporting {previous}")]
  HeightRegressed {
    /// The previously observed height.
    previous: u64,
    /// The newly reported, lower height.
    new: u64,
  },
}

/// Reject a newly reported chain height that regresses from a previously observed one.
///
/// A malicious or eclipsing node can try to roll a wallet's view of the chain backward (e.g. to
/// replay an already-spent output as still spendable, or to hide recent transactions). This does
/// not by itself prove the node is malicious — it could be a misconfigured node that resynced —
/// but the wallet must not silently accept it either way.
pub fn check_height_monotonic(previous: Option<u64>, new: u64) -> Result<(), NodeTrustError> {
  match previous {
    Some(previous) if new < previous => Err(NodeTrustError::HeightRegressed { previous, new }),
    _ => Ok(()),
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn first_observation_is_always_accepted() {
    assert_eq!(check_height_monotonic(None, 0), Ok(()));
    assert_eq!(check_height_monotonic(None, 1_000_000), Ok(()));
  }

  #[test]
  fn increasing_height_is_accepted() {
    assert_eq!(check_height_monotonic(Some(100), 101), Ok(()));
    assert_eq!(check_height_monotonic(Some(100), 100), Ok(()));
  }

  #[test]
  fn regressing_height_is_rejected() {
    assert_eq!(
      check_height_monotonic(Some(100), 99),
      Err(NodeTrustError::HeightRegressed { previous: 100, new: 99 })
    );
  }
}
