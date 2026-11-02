# Monero Android wallet — Rust core + Jetpack Compose UI

A from-scratch build of a security-hardened Android Monero wallet. The cryptographic and protocol
core is [`monero-oxide`](https://github.com/monero-oxide/monero-oxide) (the audited, Serai/Cuprate
pure-Rust Monero library) — this project never reimplements cryptography.

This is a large, multi-stage build. Each stage below ships complete, compiling, tested code before
the next starts; nothing is marked done until it actually builds and has a test exercising it.
`THREAT_MODEL.md` tracks which mitigations are real today versus planned.

## Status

**Stages 1–5 are done**, and stage 8 has a real, if partial, start — there is a real, installable
debug APK (see "Building the Android app" below) with a Rust core, Keystore-backed biometric
unlock, root detection, a decoy wallet, a real daemon-RPC client tested live against the actual
Monero mainnet, subaddresses, view-only wallets, `monero:` URI parsing and a local address book,
all wired through to a Compose screen on-device. **None of the Android-only security code has
been run on a real device or emulator** — this sandbox has no Android runtime, only
`gradle assembleDebug` and JVM unit tests (see THREAT_MODEL.md rows 5/9). Stages 6, 7 and 9 are
not started.

| Stage | What it covers | Status |
|---|---|---|
| 1 | Rust workspace, `monero-oxide`-backed key management, scanning wrapper, encrypted storage, daemon trust boundary | **done** |
| 2 | UniFFI bindings (`crates/ffi`) exposing stage 1 to Kotlin; cross-compiled `.so` for arm64-v8a/x86_64; minimal Compose screen proving generate→encrypt→persist→decrypt→restore works on-device | **done** (seed phrase support (25-word + Polyseed) via `monero-seed`/`monero-polyseed` deferred — see `crates/wallet-core/src/keys.rs`) |
| 3 | Android security layer: Keystore-wrapped storage key, biometric/PIN unlock, root/emulator detection, auto-lock, decoy wallet | **done, unverified on-device** (not done: `dataExtractionRules` — turned out to be a no-op given `allowBackup=false`, see the manifest's comment; Play Integrity, which needs a Play Console project only the app's owner can create; inactivity-timeout auto-lock, only backgrounding is wired up; a settings UI for the wipe-after-N-failures toggle) |
| 4 | Networking & privacy: embedded Tor (Arti), daemon RPC client wired to the `network` trust boundary, decoy selection, foreground sync service | **daemon RPC: done, tested live against real mainnet** (`crates/rpc`, cross-compiles for Android with tokio/hyper/rustls); **Tor: deliberately not attempted** — see `crates/rpc`'s module docs for why (this sandbox's network cannot reach Tor relays, so embedding Arti here could never be verified); decoy selection and a foreground sync service: not started |
| 5 | Wallet features: subaddresses/accounts UI, `monero:` URIs, address book, view-only wallets, cold signing, encrypted backup | subaddresses, `monero:` URI parsing, view-only wallets, a local address book: **done**, see "What stage 5 actually gives you"; fee priorities: live-tested at the Rust layer (`rpc::DaemonClient::fee_rate`) but not surfaced in the UI (no send flow to attach it to); cold signing (animated QR/UR), multisig: not started — both substantial, separately-scoped future work |
| 6 | Jetpack Compose UI: onboarding, dashboard, send/receive, history, node manager, security settings | not started (stage 2's `MainActivity` is a throwaway smoke test, not this UI — though as of stage 4 it's themed, see "UI theme" below) |
| 7 | Charts (Vico): balance/flow/fee history, opt-in price chart | not started |
| 8 | Testing: fuzzing, FFI integration tests, Compose/Espresso UI tests, MobSF scan | **fuzzing: started and already paid for itself** — `fuzz/` (3 targets: URI parsing, address parsing, wallet-file decryption) found and fixed a real bug, see "What stage 8 actually gives you"; FFI integration tests exist as part of `crates/ffi`'s own test suite; Compose/Espresso UI tests and MobSF: not started (need a running emulator/device, which this sandbox doesn't have) |
| 9 | Release: reproducible builds, signing, F-Droid/Play assets, SECURITY.md, privacy policy | not started (current APK is debug-signed with Gradle's default debug key — not a release artifact) |

## Repository layout

```
crates/
  wallet-core/   Key management, address derivation, output scanning (wraps monero-wallet)
  storage/       Argon2id + XChaCha20-Poly1305 encrypted wallet-file envelope
  network/       Daemon RPC trust boundary (trait + validation; real transport is crates/rpc)
  rpc/           Real daemon RPC client (monero-oxide's own monero-simple-request-rpc);
                 live-tested against a real Monero mainnet node (tests/live_node.rs, --ignored
                 by default — see "Building" below to opt in)
THREAT_MODEL.md  Threats mapped to mitigations, with honest done/planned status
```

```
crates/ffi/           UniFFI bindings (crate name monero_wallet_ffi) over wallet-core, storage
                       and rpc
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

## What stage 3 actually gives you

- `security/SecureVault.kt`: wraps a random 32-byte "device password" with a hardware-backed
  (StrongBox-preferred, TEE fallback) Android Keystore AES-GCM key requiring biometric or device-
  credential authentication. The wallet's real secret-at-rest protection is still stage 1's
  Argon2id + XChaCha20-Poly1305 (`crates/storage`, done in Rust) — this is what lets a human
  unlock that without typing or remembering the random password themselves.
- `security/PinVault.kt` + `WalletFiles`: a typed PIN selects between two independently-protected
  wallet files (real/decoy); biometric authentication still gates the actual key unwrap either
  way, so the PIN alone never bypasses hardware-backed auth.
- `security/RootDetection.kt`: unit-tested root/emulator heuristics (`su` binary paths, build
  tags, known root-manager packages, emulator fingerprints), surfaced as a non-blocking warning.
- `security/FailedAttemptTracker.kt`: opt-in (default off) wipe of a wallet's files after N
  consecutive failed unlocks.
- `WalletApplication.kt` + `LockState`: the whole process leaving the foreground re-locks the
  wallet; reopening it requires authenticating again.
- Manifest/UI hardening: `FLAG_SECURE`, `filterTouchesWhenObscured`, `MARK_AS_SENSITIVE` on the
  one clipboard copy this app does, no `INTERNET` permission (there's nothing to use it for yet).

**What this does not include**, and why: `dataExtractionRules` (a no-op once `allowBackup=false`
is set — see the manifest comment); Play Integrity (needs a Google Play Console project that only
the app's owner can create — not something buildable without their credentials); an inactivity-
*timeout* auto-lock as opposed to lock-on-backgrounding (needs a timer + settings UI, stage 6); a
settings screen to actually turn the wipe-after-N-failures policy on (it defaults to off and
nothing in this smoke-test screen exposes the toggle yet).

## What stage 4 actually gives you

- `crates/rpc`: a `DaemonClient` implementing `network::DaemonRpc` against `monero-oxide`'s own
  `monero-simple-request-rpc` — no custom RPC decoding. Fetches the chain height and validated,
  scannable blocks; the latter feed directly into `wallet_core::scanning::WalletScanner`, so
  real daemon data runs through real `monero-oxide` validation into our own scanner, not a mock.
  This is tested **live against a real Monero mainnet node** (`crates/rpc/tests/live_node.rs`),
  not just unit-tested against fixtures — see "Building" for how to opt into that test.
- `network::HeightTracker`: a small stateful wrapper so a sync loop can reject a daemon reporting
  a height that regresses, without hand-threading "the last height I saw" everywhere.
- The Android app gained a "Check live chain height" button (`fetchChainHeight`, an async UniFFI
  export) and the `INTERNET` permission it needs. `crates/rpc`'s full dependency tree — tokio,
  hyper, rustls, ring — cross-compiles cleanly for both arm64-v8a and x86_64 via `cargo-ndk`; that
  was the real open question stage 4 had to answer about whether a from-scratch Android wallet
  can carry this networking stack at all, and it can.
- **Tor was deliberately not attempted.** Arti needs real, arbitrary-port TCP to Tor relays and
  directory authorities to bootstrap a circuit; this project's development sandbox only permits
  HTTPS CONNECT to an allowlisted set of hosts on port 443 (confirmed: every non-443 connection
  attempt came back as an immediate reset). Embedding a privacy-critical anonymity tool behind a
  dependency that could never once be exercised — in an environment that can't tell a working
  circuit from a silently broken one — would be worse than not shipping it. See `crates/rpc`'s
  module docs for exactly where the integration seam is for whoever picks this up next, somewhere
  that can actually reach the Tor network.

## What stage 5 actually gives you

- `wallet_core::keys::ViewOnlyWallet`: a watch-only companion built from a public spend key +
  private view key (both exportable from a full `SpendKey`). Can derive addresses; cannot sign —
  there is no spend key in it to sign with. Exposed over FFI as `FfiViewOnlyWallet`.
- Subaddresses: `SpendKey::subaddress`/`ViewOnlyWallet::subaddress` were already in `wallet-core`
  since stage 1; this stage is what exposes them over FFI and gives the app a button to derive
  and list them, instead of only deriving the primary address.
- `wallet_core::uri`: `monero:` payment URI parsing (address + `tx_amount`/`tx_description`/
  `recipient_name`), per the de facto scheme documented at
  [monero-project/meta](https://github.com/monero-project/meta/blob/master/URI_Scheme.md).
  Amounts are parsed as fixed-point integer math, never through a float — `0.1 + 0.2 != 0.3` in
  binary floating point, which is not a mistake a payment amount parser gets to make.
- `rpc::DaemonClient::fee_rate`: live-tested against the real node alongside height and block
  fetching. Not yet surfaced in the app — a fee priority picker means nothing without a send flow
  to spend it on, and building that (decoy selection + `monero-wallet`'s `send` module) is a
  separate, larger undertaking than this stage's scope.
- `app/.../AddressBook.kt`: a local, labeled address book. Plain JSON in app-private storage, not
  `storage::encrypt`-protected — addresses and labels aren't secret the way a spend key is (an
  address is already public the moment it's shared), so app-private storage's existing protection
  against other apps (THREAT_MODEL.md #1) is the actual threat this needs to resist, not a reason
  to pull Argon2id into a labeled-contacts feature.

## What stage 8 actually gives you

`fuzz/` (a standard `cargo-fuzz` project, excluded from the main workspace since it needs
nightly-only sanitizer flags — see its own `Cargo.toml`) has three targets:

- `fuzz_uri_parse`: `wallet_core::uri::parse` against arbitrary bytes.
- `fuzz_address_parse`: `monero-oxide`'s own address decoder, as exercised through this project's
  pinned dependency — its own upstream fuzzing/audits cover the decoder itself; this is cheap
  insurance that this project's specific version + feature flags don't add a panic path of their
  own.
- `fuzz_storage_decrypt`: `storage::decrypt` against arbitrary bytes, standing in for a corrupted
  or maliciously crafted wallet backup file.

**This already found and fixed a real bug.** `fuzz_storage_decrypt` found that `m_cost` (Argon2id
memory cost) was read straight from the envelope's header bytes — untrusted input from whatever
file gets handed to `decrypt` — with no upper bound, then passed directly to
`argon2::Params::new`. A crafted or corrupted wallet file could set it high enough to make Argon2
attempt a multi-terabyte allocation, aborting the process. Fixed in `crates/storage/src/lib.rs`
(`MAX_M_COST_KIB`/`MAX_T_COST`/`MAX_P_COST` ceilings, checked before key derivation ever runs),
with a regression test. Run it yourself:

```sh
rustup toolchain install nightly
cargo install cargo-fuzz
cargo +nightly fuzz run fuzz_storage_decrypt -- -max_total_time=30
cargo +nightly fuzz run fuzz_uri_parse -- -max_total_time=30
cargo +nightly fuzz run fuzz_address_parse -- -max_total_time=30
```

What's not done: this is three targets covering this project's own hand-written parsing code, run
for seconds-to-minutes locally, not a continuously-running fuzzing setup (e.g. OSS-Fuzz) that
accumulates corpus and coverage over weeks; Compose/Espresso UI tests and a MobSF scan both need
a running emulator or device, which this sandbox doesn't have.

## UI theme

The smoke-test screen is styled after [Omarchy](https://github.com/basecamp/omarchy)'s
"Tokyo Night" theme — dark, flat, bordered cards instead of soft drop-shadowed ones, monospace
type (`FontFamily.Monospace`, so no font file needs bundling/licensing) — see
`app/src/main/kotlin/.../ui/theme/`. This is a cosmetic choice for today's one throwaway screen,
not a design system; stage 6's real UI should make its own call on visual direction rather than
inherit this one by default. Omarchy ships 20+ themes (gruvbox, nord, catppuccin, matte-black,
...); swapping `Color.kt`'s palette for another of its `colors.toml` files is a small, contained
change if a different one is wanted.

## Building

Rust workspace (always available, no Android toolchain needed):

```
cargo build --workspace
cargo test --workspace
cargo clippy --workspace --all-targets

# Live network tests are --ignored by default (they hit a real public Monero node — see
# crates/rpc's module docs for why that node runs on port 443 instead of the usual 18081/18089).
# Opt in explicitly:
cargo test -p rpc -p ffi -- --ignored
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
