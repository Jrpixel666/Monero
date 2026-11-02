# Monero Android wallet — Rust core + Jetpack Compose UI

A from-scratch build of a security-hardened Android Monero wallet. The cryptographic and protocol
core is [`monero-oxide`](https://github.com/monero-oxide/monero-oxide) (the audited, Serai/Cuprate
pure-Rust Monero library) — this project never reimplements cryptography.

This is a large, multi-stage build. Each stage below ships complete, compiling, tested code before
the next starts; nothing is marked done until it actually builds and has a test exercising it.
`THREAT_MODEL.md` tracks which mitigations are real today versus planned.

## Status

**Stage 1 (Rust core) is done.** Stages 2–9 are not started.

| Stage | What it covers | Status |
|---|---|---|
| 1 | Rust workspace, `monero-oxide`-backed key management, scanning wrapper, encrypted storage, daemon trust boundary | **done** |
| 2 | UniFFI bindings (`crates/ffi`) exposing stage 1 to Kotlin; seed phrase (25-word + Polyseed) support via `monero-seed`/`monero-polyseed` | not started |
| 3 | Android security layer: Keystore-wrapped storage key, biometric/PIN unlock, `FLAG_SECURE`, root/emulator detection, auto-lock, decoy wallet | not started |
| 4 | Networking & privacy: embedded Tor (Arti), daemon RPC client wired to the `network` trust boundary, decoy selection, foreground sync service | not started |
| 5 | Wallet features: subaddresses/accounts UI, `monero:` URIs, address book, view-only wallets, cold signing, encrypted backup | not started |
| 6 | Jetpack Compose UI: onboarding, dashboard, send/receive, history, node manager, security settings | not started |
| 7 | Charts (Vico): balance/flow/fee history, opt-in price chart | not started |
| 8 | Testing: fuzzing, FFI integration tests, Compose/Espresso UI tests, MobSF scan | not started |
| 9 | Release: reproducible builds, signing, F-Droid/Play assets, SECURITY.md, privacy policy | not started |

## Repository layout

```
crates/
  wallet-core/   Key management, address derivation, output scanning (wraps monero-wallet)
  storage/       Argon2id + XChaCha20-Poly1305 encrypted wallet-file envelope
  network/       Daemon RPC trust boundary (trait + validation; transport is stage 4)
THREAT_MODEL.md  Threats mapped to mitigations, with honest done/planned status
```

`crates/ffi` (UniFFI bindings) and the `app`/`ui`/`security`/`data` Android modules don't exist yet
— they're stage 2+.

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

## Building

```
cargo build --workspace
cargo test --workspace
cargo clippy --workspace --all-targets
```

No Android toolchain or `cargo-ndk` cross-compilation is wired up yet — that lands with stage 2's
`ffi` crate.

## Supply chain

`wallet-core`'s `monero-wallet` dependency is pinned to a specific git commit (`rev`, not a
branch), checked in in `Cargo.toml`. `cargo-audit`/`cargo-deny`/`cargo-vet` CI gating is planned
for stage 9, not wired up yet.
