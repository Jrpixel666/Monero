//! Fuzzes `wallet_core::uri::parse`, the hand-written parser in this codebase for `monero:`
//! payment URIs (percent-decoding, address decoding, fixed-point amount math). This is exactly
//! the kind of code the original project plan calls out for fuzzing: it runs on attacker-chosen
//! input the moment a user scans a QR code or taps a link, before any trust decision is made.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
  if let Ok(s) = core::str::from_utf8(data) {
    // Must never panic, regardless of input -- a parse failure is an `Err`, not a crash.
    let _ = wallet_core::uri::parse(s);
  }
});
