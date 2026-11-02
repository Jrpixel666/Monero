package com.jrpixel666.monerowallet.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Which wallet file a typed PIN selects — the decoy-wallet feature from THREAT_MODEL.md #9. The
 * PIN is intentionally NOT the wallet's cryptographic protection: it only picks which of two
 * already independently Argon2id/XChaCha20-Poly1305-encrypted files (see `storage`) and which
 * [SecureVault] to use. An offline attacker who has pulled both encrypted files off the device
 * gains nothing by brute-forcing this hash that they didn't already have by attacking the files
 * directly, which is why a salted, moderately-stretched SHA-256 is adequate here and a dedicated
 * Argon2id FFI round-trip for a 4-8 digit PIN was not worth adding for this stage.
 */
class PinVault(context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("pin_vault", Context.MODE_PRIVATE)

  enum class Match { REAL, DECOY, NONE }

  fun hasRealPin(): Boolean = prefs.contains(KEY_REAL_HASH)

  fun hasDecoyPin(): Boolean = prefs.contains(KEY_DECOY_HASH)

  fun setRealPin(pin: String) = store(KEY_REAL_HASH, KEY_REAL_SALT, pin)

  fun setDecoyPin(pin: String) = store(KEY_DECOY_HASH, KEY_DECOY_SALT, pin)

  fun check(pin: String): Match {
    if (matches(pin, KEY_REAL_HASH, KEY_REAL_SALT)) return Match.REAL
    if (matches(pin, KEY_DECOY_HASH, KEY_DECOY_SALT)) return Match.DECOY
    return Match.NONE
  }

  private fun store(hashKey: String, saltKey: String, pin: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    prefs.edit().putString(saltKey, salt.toHex()).putString(hashKey, stretch(pin, salt).toHex()).apply()
  }

  private fun matches(pin: String, hashKey: String, saltKey: String): Boolean {
    val storedHash = prefs.getString(hashKey, null) ?: return false
    val salt = prefs.getString(saltKey, null)?.fromHex() ?: return false
    return MessageDigest.isEqual(stretch(pin, salt), storedHash.fromHex())
  }

  private fun stretch(pin: String, salt: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    var data = salt + pin.toByteArray(Charsets.UTF_8)
    repeat(STRETCH_ITERATIONS) { data = digest.digest(data) }
    return data
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

  private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  companion object {
    private const val KEY_REAL_HASH = "real_pin_hash"
    private const val KEY_REAL_SALT = "real_pin_salt"
    private const val KEY_DECOY_HASH = "decoy_pin_hash"
    private const val KEY_DECOY_SALT = "decoy_pin_salt"
    private const val STRETCH_ITERATIONS = 10_000
  }
}

/** File names for the two wallets the PIN can select between. */
object WalletFiles {
  const val REAL = "wallet.enc"
  const val DECOY = "wallet_decoy.enc"
}
