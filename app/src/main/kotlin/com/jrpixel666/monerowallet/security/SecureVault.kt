package com.jrpixel666.monerowallet.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps a random "device password" with a hardware-backed Android Keystore AES key that requires
 * biometric (or device-credential) authentication to use, StrongBox-backed where the device has
 * one. The wallet's actual secret-at-rest protection is still `storage::encrypt`'s Argon2id +
 * XChaCha20-Poly1305 (done in Rust, see crates/storage) — this class exists so the password fed
 * into that doesn't have to be something a human types and remembers. One instance per on-disk
 * vault file, so the real wallet and a decoy wallet (see [PinVault]) each get their own device
 * password under the same Keystore key.
 *
 * Usage (see MainActivity): get a [Cipher] from [cipherForStoring]/[cipherForUnlocking], wrap it
 * in a `BiometricPrompt.CryptoObject`, and only call [finishStoring]/[finishUnlocking] with the
 * cipher BiometricPrompt hands back in `onAuthenticationSucceeded` — never with a cipher that
 * hasn't been through that callback.
 */
class SecureVault(context: Context, fileName: String) {
  private val vaultFile = File(context.filesDir, fileName)

  fun hasStoredPassword(): Boolean = vaultFile.exists()

  /** Delete the stored, wrapped device password. Used by the opt-in wipe-after-N-failures policy. */
  fun wipe() {
    vaultFile.delete()
  }

  /** A fresh AES-GCM cipher, ready to encrypt a new device password once authenticated. */
  fun cipherForStoring(): Cipher =
    Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

  /** Persist the device password, encrypted under an already-authenticated [cipher]. */
  fun finishStoring(cipher: Cipher, devicePassword: ByteArray) {
    val ciphertext = cipher.doFinal(devicePassword)
    val iv = cipher.iv
    require(iv.size <= 255) { "unexpectedly large GCM IV" }
    vaultFile.writeBytes(byteArrayOf(iv.size.toByte()) + iv + ciphertext)
  }

  /** A cipher primed with this vault's stored IV, ready to decrypt once authenticated. */
  fun cipherForUnlocking(): Cipher {
    val bytes = vaultFile.readBytes()
    val ivLen = bytes[0].toInt()
    val iv = bytes.copyOfRange(1, 1 + ivLen)
    return Cipher.getInstance(TRANSFORMATION)
      .apply { init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv)) }
  }

  /** Recover the device password using an already-authenticated [cipher]. */
  fun finishUnlocking(cipher: Cipher): ByteArray {
    val bytes = vaultFile.readBytes()
    val ivLen = bytes[0].toInt()
    val ciphertext = bytes.copyOfRange(1 + ivLen, bytes.size)
    return cipher.doFinal(ciphertext)
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    try {
      keyGenerator.init(buildKeySpec(preferStrongBox = true))
    } catch (_: Exception) {
      // Most commonly android.security.keystore.StrongBoxUnavailableException: this device has
      // no StrongBox secure element. The TEE-backed key below is still hardware-isolated.
      keyGenerator.init(buildKeySpec(preferStrongBox = false))
    }
    return keyGenerator.generateKey()
  }

  private fun buildKeySpec(preferStrongBox: Boolean): KeyGenParameterSpec {
    val builder =
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(true)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.setUserAuthenticationParameters(
        0,
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
      )
    } else {
      @Suppress("DEPRECATION")
      builder.setUserAuthenticationValidityDurationSeconds(-1)
    }

    if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      builder.setIsStrongBoxBacked(true)
    }

    return builder.build()
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "monero_wallet_vault_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val REAL_VAULT_FILE = "vault_real.bin"
    const val DECOY_VAULT_FILE = "vault_decoy.bin"
  }
}
