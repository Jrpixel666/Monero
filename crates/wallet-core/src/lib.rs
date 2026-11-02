//! Wallet key management, address derivation and output scanning.
//!
//! This crate is a thin, auditable wrapper around [`monero_wallet`] (part of the `monero-oxide`
//! project). It never reimplements cryptography: key generation, scalar/point arithmetic, scanning
//! and transaction construction all come from `monero-oxide`. What this crate adds is the Android
//! wallet's own secret-handling discipline (zeroizing wrappers, restricted constructors) and a
//! stable surface for the FFI layer (Stage 2) to bind against.
#![forbid(unsafe_code)]
#![warn(missing_docs)]

pub mod keys;
pub mod scanning;

pub use monero_wallet as oxide_wallet;
pub use monero_wallet::address::Network;
