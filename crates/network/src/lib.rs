//! Daemon RPC trust boundary.
//!
//! This crate defines the trust model and trait surface for talking to a Monero daemon; the real
//! transport (currently clearnet HTTPS, via `monero-oxide`'s own `monero-simple-request-rpc`) is
//! `crates/rpc`, which implements [`DaemonRpc`] against it and is tested live against a real
//! public Monero node (see `crates/rpc/tests`). Routing that transport through Tor (Arti) is
//! designed for but not wired up — see `crates/rpc`'s module docs for exactly why and what the
//! integration seam is.
//!
//! The core rule: a daemon — remote or local, chosen by the user or bundled — is **never**
//! trusted. Every response is untrusted input. Consensus-relevant validation happens in
//! `wallet-core` (via `monero-oxide`), not here and not on the node's say-so.
#![forbid(unsafe_code)]
#![warn(missing_docs)]

use core::future::Future;

use thiserror::Error;

/// A client capable of querying a Monero daemon.
///
/// Implementations do not need to validate responses beyond basic structural decoding — ownership
/// of "is this consensus-valid" belongs to `wallet-core`. What implementations must not do is
/// silently swallow an inconsistency (e.g. a height that moved backward) that would let a
/// malicious node roll the wallet's view of the chain back; see [`check_height_monotonic`].
pub trait DaemonRpc: Send + Sync {
  /// The error type for this client's transport.
  type Error: core::fmt::Debug;

  /// The daemon's current reported chain height.
  fn get_height(&self) -> impl Send + Future<Output = Result<u64, Self::Error>>;
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

/// Stateful wrapper applying [`check_height_monotonic`] across successive observations from a
/// [`DaemonRpc`], so a caller doesn't have to thread the "previous height" state through by hand.
#[derive(Debug, Default)]
pub struct HeightTracker {
  last_observed: Option<u64>,
}

impl HeightTracker {
  /// Record a newly observed height, rejecting it if it regresses from the last one seen.
  /// On acceptance, this becomes the new baseline for the next call.
  pub fn observe(&mut self, new: u64) -> Result<(), NodeTrustError> {
    check_height_monotonic(self.last_observed, new)?;
    self.last_observed = Some(new);
    Ok(())
  }

  /// The highest height accepted so far, if any.
  pub fn last_observed(&self) -> Option<u64> {
    self.last_observed
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

  #[test]
  fn height_tracker_accumulates_a_rising_baseline_and_rejects_regression() {
    let mut tracker = HeightTracker::default();
    assert_eq!(tracker.last_observed(), None);

    tracker.observe(100).unwrap();
    assert_eq!(tracker.last_observed(), Some(100));

    tracker.observe(105).unwrap();
    assert_eq!(tracker.last_observed(), Some(105));

    assert_eq!(
      tracker.observe(104),
      Err(NodeTrustError::HeightRegressed { previous: 105, new: 104 })
    );
    // A rejected observation must not corrupt the baseline.
    assert_eq!(tracker.last_observed(), Some(105));
  }
}
