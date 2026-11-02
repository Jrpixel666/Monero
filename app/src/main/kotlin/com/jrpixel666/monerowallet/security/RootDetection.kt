package com.jrpixel666.monerowallet.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Heuristic root/emulator detection. This is a warning, not a gate: a sufficiently sophisticated
 * attacker can hide root from heuristics like these, and plenty of legitimate power users root
 * their own phones on purpose. See THREAT_MODEL.md #5 — the point is to inform, not to punish.
 *
 * The individual checks are plain functions over primitive inputs so they're unit-testable
 * without an Android runtime; [isDeviceSuspicious] wires them to real `Build`/`PackageManager`
 * values and is the one the app actually calls.
 */
object RootDetection {
  private val SU_PATHS =
    listOf(
      "/system/bin/su",
      "/system/xbin/su",
      "/sbin/su",
      "/system/sd/xbin/su",
      "/system/bin/failsafe/su",
      "/data/local/xbin/su",
      "/data/local/bin/su",
      "/data/local/su",
    )

  private val ROOT_MANAGEMENT_PACKAGES =
    listOf(
      "com.topjohnwu.magisk",
      "eu.chainfire.supersu",
      "com.noshufou.android.su",
      "com.koushikdutta.superuser",
      "com.kingouser.com",
    )

  /** True if any well-known `su` binary path exists. */
  fun suBinaryPresent(pathExists: (String) -> Boolean = { File(it).exists() }): Boolean =
    SU_PATHS.any(pathExists)

  /** True if the build is tagged `test-keys`, as unofficial/custom ROMs typically are. */
  fun buildTagsLookSuspicious(tags: String): Boolean = tags.contains("test-keys")

  /** True if the device identifies as an emulator rather than real hardware. */
  fun looksLikeEmulator(fingerprint: String, model: String, hardware: String): Boolean =
    fingerprint.startsWith("generic") ||
      fingerprint.startsWith("unknown") ||
      model.contains("google_sdk") ||
      model.contains("Emulator") ||
      model.contains("Android SDK built for") ||
      hardware.contains("goldfish") ||
      hardware.contains("ranchu")

  /** True if a known root-management app (Magisk, SuperSU, ...) is installed. */
  fun rootManagementPackageInstalled(isPackageInstalled: (String) -> Boolean): Boolean =
    ROOT_MANAGEMENT_PACKAGES.any(isPackageInstalled)

  /** The real-world check the app calls, wiring the pure functions above to this device. */
  fun isDeviceSuspicious(context: Context): Boolean =
    suBinaryPresent() ||
      buildTagsLookSuspicious(Build.TAGS ?: "") ||
      looksLikeEmulator(Build.FINGERPRINT ?: "", Build.MODEL ?: "", Build.HARDWARE ?: "") ||
      rootManagementPackageInstalled { pkg ->
        try {
          context.packageManager.getPackageInfo(pkg, 0)
          true
        } catch (_: PackageManager.NameNotFoundException) {
          false
        }
      }
}
