//! Standalone binding generator for the `ffi` crate (kept out of the cdylib build so the
//! `cli`/`clap` dependency never ships inside the Android `.so`).
fn main() {
  uniffi::uniffi_bindgen_main();
}
