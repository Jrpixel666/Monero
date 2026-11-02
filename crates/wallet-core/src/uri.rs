//! `monero:` payment URI parsing.
//!
//! This is the de facto scheme used by `monero-wallet-cli`/wallet2-derived wallets and documented
//! at <https://github.com/monero-project/meta/blob/master/URI_Scheme.md>:
//! `monero:<address>[?tx_amount=<decimal-XMR>&tx_description=<text>&recipient_name=<text>]`.
//!
//! This only parses structure and validates the embedded address (via `monero-oxide`); it does
//! not build or send a transaction, and it does not fetch `tx_payment_id` (deprecated upstream)
//! or `tx_recipient` iterated keys that some wallets add nonstandard support for.
#![forbid(unsafe_code)]

use monero_wallet::address::{AddressError, MoneroAddress};
use percent_encoding::percent_decode_str;
use thiserror::Error;

/// Atomic units ("piconero") per whole XMR: `10^12`.
const ATOMIC_UNITS_PER_XMR: u64 = 1_000_000_000_000;
/// Monero amounts have at most 12 decimal places.
const MAX_DECIMAL_PLACES: usize = 12;

/// An error parsing a `monero:` URI.
#[derive(Debug, Error)]
pub enum UriError {
  /// The string didn't start with `monero:`.
  #[error("not a monero: URI")]
  WrongScheme,
  /// The address portion wasn't a valid, decodable Monero address.
  #[error("invalid address in URI: {0}")]
  InvalidAddress(#[from] AddressError),
  /// `tx_amount` wasn't a valid non-negative decimal XMR amount.
  #[error("invalid tx_amount: {0:?}")]
  InvalidAmount(String),
}

/// A parsed `monero:` URI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MoneroUri {
  /// The destination address. Its network is whatever the address itself encodes — the URI
  /// scheme carries no separate network field, so callers who care should check
  /// `address.network()` against what they expect before trusting this.
  pub address: MoneroAddress,
  /// The requested amount, in atomic units (piconero), if the URI specified one.
  pub amount_piconero: Option<u64>,
  /// A human-readable description of the payment, if the URI specified one.
  pub description: Option<String>,
  /// The recipient's name, if the URI specified one.
  pub recipient_name: Option<String>,
}

/// Parse a `monero:` URI.
pub fn parse(uri: &str) -> Result<MoneroUri, UriError> {
  let rest = uri.strip_prefix("monero:").ok_or(UriError::WrongScheme)?;
  let (address_part, query) = match rest.split_once('?') {
    Some((address, query)) => (address, Some(query)),
    None => (rest, None),
  };

  let address = MoneroAddress::from_str_with_unchecked_network(address_part)?;

  let mut amount_piconero = None;
  let mut description = None;
  let mut recipient_name = None;

  if let Some(query) = query {
    for pair in query.split('&').filter(|p| !p.is_empty()) {
      let Some((key, raw_value)) = pair.split_once('=') else { continue };
      let value = percent_decode_str(raw_value).decode_utf8_lossy().into_owned();
      match key {
        "tx_amount" => amount_piconero = Some(parse_xmr_amount(&value)?),
        "tx_description" => description = Some(value),
        "recipient_name" => recipient_name = Some(value),
        // Unknown parameters are ignored, not rejected: forwards-compatible with wallets that
        // add their own extension fields, matching the documented scheme's own guidance.
        _ => {}
      }
    }
  }

  Ok(MoneroUri { address, amount_piconero, description, recipient_name })
}

/// Parse a decimal XMR amount (e.g. `"1.5"`) into atomic units, as fixed-point integer math —
/// never via a float, which cannot represent most decimal fractions exactly and would silently
/// misstate a payment amount.
fn parse_xmr_amount(s: &str) -> Result<u64, UriError> {
  let invalid = || UriError::InvalidAmount(s.to_owned());

  if s.is_empty() || s.matches('.').count() > 1 {
    return Err(invalid());
  }
  let (whole_str, frac_str) = s.split_once('.').unwrap_or((s, ""));
  if frac_str.len() > MAX_DECIMAL_PLACES || (whole_str.is_empty() && frac_str.is_empty()) {
    return Err(invalid());
  }
  if !whole_str.bytes().all(|b| b.is_ascii_digit()) || !frac_str.bytes().all(|b| b.is_ascii_digit()) {
    return Err(invalid());
  }

  let whole: u64 = if whole_str.is_empty() { 0 } else { whole_str.parse().map_err(|_| invalid())? };
  let mut frac_padded = frac_str.to_owned();
  frac_padded.push_str(&"0".repeat(MAX_DECIMAL_PLACES - frac_str.len()));
  let frac: u64 = if frac_padded.is_empty() { 0 } else { frac_padded.parse().map_err(|_| invalid())? };

  whole.checked_mul(ATOMIC_UNITS_PER_XMR).and_then(|v| v.checked_add(frac)).ok_or_else(invalid)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::keys::SpendKey;
  use monero_wallet::address::Network;

  // Generated at test time rather than hardcoded: a Monero address's trailing bytes are a
  // Keccak checksum over the rest of the payload, so a hand-typed fake address would almost
  // certainly fail to decode. A real, freshly-derived one sidesteps needing to get that right.
  fn test_address() -> String {
    SpendKey::generate().primary_address(Network::Mainnet).unwrap().to_string()
  }

  #[test]
  fn rejects_non_monero_scheme() {
    assert!(matches!(parse("bitcoin:1abc"), Err(UriError::WrongScheme)));
  }

  #[test]
  fn parses_bare_address_with_no_query() {
    let address = test_address();
    let uri = format!("monero:{address}");
    let parsed = parse(&uri).unwrap();
    assert_eq!(parsed.address, MoneroAddress::from_str(Network::Mainnet, &address).unwrap());
    assert_eq!(parsed.amount_piconero, None);
    assert_eq!(parsed.description, None);
  }

  #[test]
  fn parses_amount_description_and_recipient_name() {
    let address = test_address();
    let uri = format!(
      "monero:{address}?tx_amount=1.5&tx_description=Coffee%20%26%20tea&recipient_name=Cafe"
    );
    let parsed = parse(&uri).unwrap();
    assert_eq!(parsed.amount_piconero, Some(1_500_000_000_000));
    assert_eq!(parsed.description.as_deref(), Some("Coffee & tea"));
    assert_eq!(parsed.recipient_name.as_deref(), Some("Cafe"));
  }

  #[test]
  fn amount_parsing_never_uses_floating_point_and_is_exact() {
    // 0.1 + 0.2 != 0.3 in binary floating point; this must still parse exactly.
    assert_eq!(parse_xmr_amount("0.1").unwrap(), 100_000_000_000);
    assert_eq!(parse_xmr_amount("0.2").unwrap(), 200_000_000_000);
    assert_eq!(parse_xmr_amount("0.3").unwrap(), 300_000_000_000);
    assert_eq!(parse_xmr_amount("123").unwrap(), 123 * ATOMIC_UNITS_PER_XMR);
    assert_eq!(parse_xmr_amount("0.000000000001").unwrap(), 1);
  }

  #[test]
  fn rejects_malformed_amounts() {
    assert!(parse_xmr_amount("").is_err());
    assert!(parse_xmr_amount("1.2.3").is_err());
    assert!(parse_xmr_amount("abc").is_err());
    assert!(parse_xmr_amount("1.0000000000001").is_err()); // 13 decimal places
  }

  #[test]
  fn rejects_an_invalid_address() {
    assert!(matches!(parse("monero:not-a-real-address"), Err(UriError::InvalidAddress(_))));
  }

  #[test]
  fn ignores_unknown_query_parameters() {
    let uri = format!("monero:{}?future_field=xyz&tx_amount=2", test_address());
    let parsed = parse(&uri).unwrap();
    assert_eq!(parsed.amount_piconero, Some(2 * ATOMIC_UNITS_PER_XMR));
  }
}
