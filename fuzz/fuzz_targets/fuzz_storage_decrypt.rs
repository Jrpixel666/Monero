//! Fuzzes `storage::decrypt`'s envelope header parsing against arbitrary bytes — exactly the
//! shape of input a corrupted, truncated, or maliciously crafted wallet backup file would be.
//! AEAD decryption itself (via the `chacha20poly1305`/`argon2` crates) is assumed correct per
//! their own upstream testing; what this exercises is our own envelope-header slicing in
//! `crates/storage/src/lib.rs`, which must reject malformed input cleanly rather than panic
//! (e.g. an out-of-bounds slice on a too-short header).
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
  let _ = storage::decrypt(b"any password", data);
});
