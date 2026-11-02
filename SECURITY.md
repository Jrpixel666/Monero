# Security Policy

This project is pre-release software (see `README.md`'s stage table) handling cryptographic
wallet material. Treat anything found here as significant until proven otherwise.

## Reporting a vulnerability

**Preferred: GitHub Private Vulnerability Reporting.** Use this repository's Security tab →
"Report a vulnerability" to open a private advisory. This keeps the report out of public issues
until a fix is ready and lets us coordinate a disclosure timeline together.

**Fallback:** if private reporting isn't available to you, email the maintainer at
jrpixel6@gmail.com with `[SECURITY]` in the subject line. Please include:

- A description of the issue and its impact (what an attacker gains, and what they need to do it)
- Steps to reproduce, or a proof-of-concept
- Which stage/component is affected (see `README.md`'s stage table and `THREAT_MODEL.md`)

Please do not open a public GitHub issue for a security vulnerability before it has been fixed,
or before the maintainer has agreed with you on a disclosure date.

## Scope

In scope: anything in `crates/`, `app/`, `fuzz/`, and the build/release tooling in `scripts/` and
CI configuration. This includes (but isn't limited to):

- Key generation, derivation, storage, or export logic (`crates/wallet-core`, `crates/storage`,
  `crates/ffi`)
- The Android security layer (`app/.../security/`) — Keystore usage, biometric gating, the decoy
  wallet mechanism, root detection bypass
- The daemon RPC trust boundary (`crates/network`, `crates/rpc`) — anything that lets an
  untrusted or malicious node mislead the wallet
- Supply-chain issues: a compromised or malicious dependency, or a way to tamper with a release
  build undetected

Out of scope: vulnerabilities in `monero-oxide` itself (report those to
[monero-oxide/monero-oxide](https://github.com/monero-oxide/monero-oxide)) or in upstream
dependencies (report those to the dependency's own project) — though if you found it through
this project, we'd still like to know, so we can track whether it affects us.

## What to expect

This is presently a one-person project with no dedicated security team and no bug bounty. We
will:

- Acknowledge your report within a reasonable time
- Work with you to understand and confirm the issue
- Fix it and credit you (unless you'd prefer not to be named) in the release notes, once a fix is
  out

Response times are best-effort, not contractual, while the project is at this stage.

## A note on what's already been found

`THREAT_MODEL.md` and `README.md` ("What stage 8 actually gives you") document a real bug this
project's own fuzzing (`fuzz/`) found and fixed before this `SECURITY.md` existed: `crates/storage`
previously trusted an unbounded Argon2 memory-cost parameter read from an untrusted wallet-file
header, letting a corrupted or malicious file abort the process. We'd rather a security
researcher re-verify that it's actually fixed than take our word for it — the fix and its
regression test are in `crates/storage/src/lib.rs`.
