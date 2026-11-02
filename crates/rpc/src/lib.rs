//! A real daemon RPC client, implementing `network`'s [`network::DaemonRpc`] trust boundary
//! against `monero-oxide`'s own `monero-simple-request-rpc` crate — no custom RPC decoding here.
//!
//! ## On Tor
//!
//! Stage 4 of this project's plan calls for embedded Tor (Arti) by default. That isn't wired up
//! here, deliberately: Arti needs real, arbitrary-port TCP connections to Tor relays and
//! directory authorities to bootstrap a circuit, and the sandbox this crate was developed and
//! tested in only permits HTTPS CONNECT to an allowlist of hosts on port 443 — confirmed by
//! testing: every non-443 connection attempt made during development came back as an immediate
//! connection reset, matching that sandbox's documented egress policy. Embedding a security- and
//! privacy-critical anonymity tool behind a dependency that could never once be exercised, in an
//! environment that can't distinguish a working circuit from a silently broken one, would be
//! worse than not shipping it at all.
//!
//! The integration seam for it is this crate: [`DaemonClient::connect`] takes a URL and builds
//! its own transport. A Tor-routed variant would build `SimpleRequestTransport` (or a custom
//! `HttpTransport` impl) over an `arti_client::TorClient`-backed stream instead of a direct TCP
//! connection — that needs a network that can actually dial out to Tor relays to be built and
//! verified honestly, which this one can't.
#![forbid(unsafe_code)]
#![warn(missing_docs)]

use core::future::Future;

use monero_simple_request_rpc::{
  prelude::{MoneroDaemon, ProvidesBlockchainMeta, ProvidesFeeRates, ProvidesScannableBlocks},
  SimpleRequestTransport,
};

pub use monero_simple_request_rpc::prelude::InterfaceError as RpcError;
pub use monero_simple_request_rpc::prelude::{FeeError, FeePriority, FeeRate, ScannableBlock};

/// A connection to a Monero daemon over HTTP(S).
pub struct DaemonClient(MoneroDaemon<SimpleRequestTransport>);

impl DaemonClient {
  /// Connect to a daemon at `url` (e.g. `"https://xmr.example.com"`). Credentials, if the daemon
  /// requires them, go in the URL itself (`scheme://user:pass@host`) per
  /// `SimpleRequestTransport::new`'s documented form — never logged, never stored by this crate.
  pub async fn connect(url: String) -> Result<Self, RpcError> {
    Ok(Self(SimpleRequestTransport::new(url).await?))
  }

  /// The 0-indexed number of the latest block this daemon has (the genesis block is number 0).
  /// Note this is one less than the classic Monero RPC `get_height` response field, which counts
  /// blocks rather than indexing them; this crate exposes `monero-oxide`'s own convention instead
  /// of silently converting between the two.
  pub async fn latest_block_number(&self) -> Result<u64, RpcError> {
    Ok(self.0.latest_block_number().await? as u64)
  }

  /// Fetch a block by its number, sanity-checked by `monero-oxide` (right number, transactions
  /// matching the block's own list of them) and ready to hand to
  /// `wallet_core::scanning::WalletScanner::scan_block`.
  pub async fn scannable_block_by_number(&self, number: usize) -> Result<ScannableBlock, RpcError> {
    self.0.scannable_block_by_number(number).await
  }

  /// The daemon's recommended fee rate for `priority`, rejected if it exceeds `max_per_weight` —
  /// a sanity ceiling **the caller must choose**. This crate does not invent one: what's a sane
  /// bound depends on recent real-world network conditions, which is a wallet-core/sync-loop
  /// policy decision (stage 5, not yet made), not something to bake into an RPC transport crate
  /// as an untested magic number.
  pub async fn fee_rate(&self, priority: FeePriority, max_per_weight: u64) -> Result<FeeRate, FeeError> {
    self.0.fee_rate(priority, max_per_weight).await
  }
}

impl network::DaemonRpc for DaemonClient {
  type Error = RpcError;

  fn get_height(&self) -> impl Send + Future<Output = Result<u64, RpcError>> {
    self.latest_block_number()
  }
}
