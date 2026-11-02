//! Fuzzes Monero address decoding as exercised through this project's own dependency surface
//! (`wallet_core::oxide_wallet::address::MoneroAddress`). The decoder itself is `monero-oxide`'s,
//! which has its own upstream audits/fuzzing; this target exists because address strings are
//! attacker-chosen input the moment a user pastes or scans one, and is cheap insurance that this
//! project's specific pinned version + feature flags don't introduce a panic path of their own.
#![no_main]

use libfuzzer_sys::fuzz_target;
use wallet_core::oxide_wallet::address::MoneroAddress;

fuzz_target!(|data: &[u8]| {
  if let Ok(s) = core::str::from_utf8(data) {
    let _ = MoneroAddress::from_str_with_unchecked_network(s);
  }
});
