#!/usr/bin/env bash
# Cross-compiles crates/ffi for Android (arm64-v8a, x86_64) and regenerates the Kotlin bindings
# that app/ builds against. Run this after any change to crates/ffi, crates/wallet-core or
# crates/storage, before building the Android app.
#
# Requires: ANDROID_NDK_HOME set, `cargo ndk` installed (`cargo install cargo-ndk`), and the
# aarch64-linux-android/x86_64-linux-android Rust targets added (`rustup target add ...`).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your Android NDK install (see README.md)}"

jni_libs_dir="app/src/main/jniLibs"
kotlin_gen_dir="app/src/main/kotlin-gen"

echo "==> Cross-compiling crates/ffi for arm64-v8a, x86_64"
cargo ndk -t arm64-v8a -t x86_64 -o "$jni_libs_dir" build --release -p ffi

echo "==> Regenerating Kotlin bindings"
rm -rf "$kotlin_gen_dir"
mkdir -p "$kotlin_gen_dir"
cargo run -p uniffi-bindgen --bin uniffi-bindgen -- generate \
  --library "$jni_libs_dir/arm64-v8a/libmonero_wallet_ffi.so" \
  --language kotlin \
  --out-dir "$kotlin_gen_dir"

echo "==> Done. Build the app with: gradle assembleDebug (see README.md)"
