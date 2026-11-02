# Privacy Policy

This document describes what this app actually does today, at its current stage (see
`README.md`'s stage table) — not an aspirational end-state. It will be revised as the app's
networking behavior changes (in particular, once Tor routing lands — see `crates/rpc`'s module
docs for why it isn't yet).

## What this app does not do

- **No analytics or tracking SDKs.** None are included, and none are planned. You can verify this
  yourself: there is no dependency in `app/build.gradle.kts` for any analytics, crash-reporting,
  or advertising library.
- **No account, no sign-up, no server of ours.** This project does not operate any backend. There
  is no "our servers" for your data to reach.
- **No collection of your wallet data by us.** Your spend key, view key, seed, balance,
  transaction history, and address book never leave your device to any server we control, because
  we don't have one.

## What this app does send over the network, and to whom

As of the current stage, this app makes direct network requests to:

- **A Monero daemon**, at whatever URL you enter (defaulting to a public node this project
  verified reachable during development — see `crates/rpc`'s module docs). This connection is
  **not yet routed through Tor** (see `README.md`'s stage 4 entry for exactly why). That means:
  - The daemon operator, and anyone who can observe your network traffic (your ISP, a Wi-Fi
    operator, etc.), can see that your IP address is talking to a Monero node, and when.
  - The specific requests this app makes (current chain height, specific blocks, fee rates) are
    visible to the daemon operator, though not to anyone just watching encrypted traffic (the
    connection is HTTPS).
  - **This is a real, currently-unmitigated metadata leak**, and it's the reason Tor routing is
    planned. Until it lands, treat the daemon URL you choose as a party that can see which node
    you're querying and when — choose a daemon you're comfortable with that knowing, or run your
    own.

That's it — there is no other network destination in this app as of this stage.

## What's stored on your device

Your wallet's spend key is encrypted at rest (Argon2id + XChaCha20-Poly1305, see
`crates/storage`) in this app's private storage, which other apps cannot read without root.
Android's own backup system is disabled for this app (`allowBackup="false"`), so your wallet data
is not swept up into a cloud or local device backup. Your address book is stored in plain JSON in
the same app-private storage (not encrypted — see `README.md`'s stage 5 section for why that's a
reasonable choice for data that isn't secret the way a key is).

## Open source

Every claim above is checkable: this app's full source is in this repository. If you find this
policy doesn't match what the code actually does, please treat that as a bug and report it per
`SECURITY.md`.
