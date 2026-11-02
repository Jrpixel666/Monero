//! Live integration test against a real public Monero mainnet node, proving the whole pipeline —
//! real daemon RPC → monero-oxide's own response validation → wallet-core's scanner — actually
//! runs, not just compiles.
//!
//! Ignored by default: this makes a real network request to a third party's infrastructure, and
//! nothing should do that without being asked. Opt in explicitly:
//!
//! ```sh
//! cargo test -p rpc --test live_node -- --ignored
//! ```
//!
//! The node below (reachable over HTTPS on port 443, which is why it was chosen — most public
//! Monero nodes listen on 18081/18089, non-443 ports that this project's own development sandbox
//! cannot reach, per `crates/rpc`'s module docs) can be overridden with `MONERO_TEST_NODE_URL` if
//! it goes offline.

use rpc::{DaemonClient, FeePriority};
use wallet_core::{keys::SpendKey, scanning::WalletScanner};

fn node_url() -> String {
  std::env::var("MONERO_TEST_NODE_URL").unwrap_or_else(|_| "https://xmr.kret.dev".to_owned())
}

#[tokio::test]
#[ignore = "hits a real third-party Monero node; opt in with: cargo test -p rpc -- --ignored"]
async fn fetches_increasing_height_from_a_real_node() {
  let client = DaemonClient::connect(node_url()).await.expect("failed to connect to test node");

  let mut tracker = network::HeightTracker::default();
  let first = network::DaemonRpc::get_height(&client).await.expect("get_height failed");
  tracker.observe(first).expect("first observation is always accepted");
  assert!(first > 3_000_000, "mainnet should be well past block 3,000,000 by now");

  // A second call shortly after must not report a lower height; HeightTracker enforces that.
  let second = network::DaemonRpc::get_height(&client).await.expect("get_height failed");
  tracker.observe(second).expect("height must not regress between two immediate calls");
}

#[tokio::test]
#[ignore = "hits a real third-party Monero node; opt in with: cargo test -p rpc -- --ignored"]
async fn fetches_and_scans_a_real_block_with_our_wallet_scanner() {
  let client = DaemonClient::connect(node_url()).await.expect("failed to connect to test node");

  let height = client.latest_block_number().await.expect("get_height failed");
  // A few blocks back from the tip, comfortably past any reorg window.
  let block_number = (height - 10) as usize;

  let block = client
    .scannable_block_by_number(block_number)
    .await
    .expect("monero-oxide rejected the daemon's block as invalid");

  // This wallet owns none of this block's outputs — the point is that real daemon data, through
  // real monero-oxide validation, reaches our WalletScanner wrapper without it falling over.
  let key = SpendKey::generate();
  let mut scanner = WalletScanner::new(key.view_pair().unwrap());
  let timelocked = scanner.scan_block(block).expect("scanning a validated block must not fail");
  assert!(
    wallet_core::scanning::spendable_outputs(timelocked).is_empty(),
    "a freshly generated wallet should not own any outputs in a real historical block"
  );
}

#[tokio::test]
#[ignore = "hits a real third-party Monero node; opt in with: cargo test -p rpc -- --ignored"]
async fn fetches_a_real_fee_rate() {
  let client = DaemonClient::connect(node_url()).await.expect("failed to connect to test node");

  // A generous ceiling for this test's purposes only -- not a recommendation for what a real
  // wallet should use, which needs its own historical-fee-based policy (see crates/rpc's docs).
  let max_per_weight = 10_000_000_000;
  let fee_rate = client
    .fee_rate(FeePriority::Normal, max_per_weight)
    .await
    .expect("fee_rate failed or exceeded the sanity ceiling");
  assert!(fee_rate.per_weight() > 0, "a real daemon should report a nonzero fee rate");
}
