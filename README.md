# Monero Android wallet — Rust core + Jetpack Compose UI

A from-scratch build of a security-hardened Android Monero wallet. The cryptographic and protocol
core is [`monero-oxide`](https://github.com/monero-oxide/monero-oxide) (the audited, Serai/Cuprate
pure-Rust Monero library) — this project never reimplements cryptography.

This is a large, multi-stage build. Each stage below ships complete, compiling, tested code before
the next starts; nothing is marked done until it actually builds and has a test exercising it.
`THREAT_MODEL.md` tracks which mitigations are real today versus planned.

## Status

**Stages 1 and 2 are done** — there is a real, installable debug APK (see "Building the Android
app" below) with a Rust core wired all the way through to a Compose screen on-device. Stages
3–9 are not started; the parts of stage 3/6 that a minimal app needed to exist at all (no
plaintext-backup manifest flag, `FLAG_SECURE`) were applied opportunistically and are called out
as such below — they are not the full stage 3/6 scope.

| Stage | What it covers | Status |
|---|---|---|
| 1 | Rust workspace, `monero-oxide`-backed key management, scanning wrapper, encrypted storage, daemon trust boundary | **done** |
| 2 | UniFFI bindings (`crates/ffi`) exposing stage 1 to Kotlin; cross-compiled `.so` for arm64-v8a/x86_64; minimal Compose screen proving generate→encrypt→persist→decrypt→restore works on-device | **done** (seed phrase support (25-word + Polyseed) via `monero-seed`/`monero-polyseed` deferred — see `crates/wallet-core/src/keys.rs`) |
| 3 | Android security layer: Keystore-wrapped storage key, biometric/PIN unlock, root/emulator detection, auto-lock, decoy wallet | not started (`allowBackup=false` and `FLAG_SECURE` landed early, as part of stage 2's screen — not the rest of this stage) |
| 4 | Networking & privacy: embedded Tor (Arti), daemon RPC client wired to the `network` trust boundary, decoy selection, foreground sync service | not started |
| 5 | Wallet features: subaddresses/accounts UI, `monero:` URIs, address book, view-only wallets, cold signing, encrypted backup | not started |
| 6 | Jetpack Compose UI: onboarding, dashboard, send/receive, history, node manager, security settings | not started (stage 2's `MainActivity` is a throwaway smoke test, not this UI) |
| 7 | Charts (Vico): balance/flow/fee history, opt-in price chart | not started |
| 8 | Testing: fuzzing, FFI integration tests, Compose/Espresso UI tests, MobSF scan | not started |
| 9 | Release: reproducible builds, signing, F-Droid/Play assets, SECURITY.md, privacy policy | not started (current APK is debug-signed with Gradle's default debug key — not a release artifact) |

## Repository layout

```
crates/
  wallet-core/   Key management, address derivation, output scanning (wraps monero-wallet)
  storage/       Argon2id + XChaCha20-Poly1305 encrypted wallet-file envelope
  network/       Daemon RPC trust boundary (trait + validation; transport is stage 4)
THREAT_MODEL.md  Threats mapped to mitigations, with honest done/planned status
```

```
crates/ffi/           UniFFI bindings (crate name monero_wallet_ffi) over wallet-core + storage
crates/uniffi-bindgen/ Standalone binding-generator binary (kept out of the cdylib build)
app/                   Single Android module: a throwaway Compose smoke-test screen (stage 6
                       replaces this with the real onboarding/dashboard/send/receive UI)
scripts/build-ffi.sh   Cross-compiles crates/ffi and regenerates app/'s Kotlin bindings
```

The `ui`/`security`/`data` module split from the original spec doesn't exist yet — `app` stays a
single module until stage 6 actually needs the separation; splitting it now, before there's a
real UI to split, would just be empty directories.

## What stage 1 actually gives you

- `wallet-core`: generate or restore a spend key (`SpendKey::generate` /
  `from_canonical_bytes`), derive its view pair, primary address and subaddresses, and scan
  already-fetched-and-validated blocks via `WalletScanner`. All cryptography is `monero-oxide`;
  this crate only adds zeroizing secret handling and a stable surface for the FFI layer.
- `storage`: `encrypt`/`decrypt` an opaque byte blob (e.g. a serialized `SpendKey`) under a
  password, using Argon2id key stretching and XChaCha20-Poly1305 AEAD, with KDF parameters stored
  in the file header for future upgradeability.
- `network`: the trait a real RPC client will implement, plus `check_height_monotonic`, the first
  piece of "never trust the node" policy. No actual transport yet.

Explicitly **not** in stage 1: seed phrases (25-word/Polyseed), transaction building/broadcast,
multisig, any Android/UniFFI/Compose code, Tor. These depend on either external, less-audited
crates (seed words) or stage-1 output (FFI needs a stable Rust API first), and building them
without that foundation would mean writing code against an API that's about to change.

## What stage 2 actually gives you

- `crates/ffi`: a UniFFI-exported `FfiWallet` object. By design, the raw spend-key scalar never
  crosses the FFI boundary in either direction — restoring takes a password + an encrypted
  envelope, exporting returns an encrypted envelope; decryption/encryption happen on the Rust
  side. `crates/uniffi-bindgen` is a separate tiny crate (so the `cli`/`clap` dependency it needs
  never ends up inside the Android `.so`).
- A real Android app (`app/`) with one screen: generate a wallet, see its address, save it
  encrypted to app-private storage, load it back. This exists to prove the FFI plumbing works
  on-device, not as the wallet's actual UI (that's stage 6).

## Building

Rust workspace (always available, no Android toolchain needed):

```
cargo build --workspace
cargo test --workspace
cargo clippy --workspace --all-targets
```

Android app — needs the Android SDK/NDK, `cargo-ndk`, and the Android Rust targets:

```
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018"
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.2.12479018

./scripts/build-ffi.sh   # cross-compiles crates/ffi, regenerates app/'s Kotlin bindings
gradle assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

The APK is debug-signed (Gradle's default debug keystore) and only built for arm64-v8a/x86_64, to
match what `scripts/build-ffi.sh` cross-compiles. Release signing with a hardware-protected key is
stage 9.

## Supply chain

`wallet-core`'s `monero-wallet` dependency is pinned to a specific git commit (`rev`, not a
branch), checked in in `Cargo.toml`. `cargo-audit`/`cargo-deny`/`cargo-vet` CI gating is planned
for stage 9, not wired up yet.
