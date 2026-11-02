package com.jrpixel666.monerowallet.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootDetectionTest {
  @Test
  fun `su binary present when any known path exists`() {
    assertTrue(RootDetection.suBinaryPresent { it == "/system/bin/su" })
    assertFalse(RootDetection.suBinaryPresent { false })
  }

  @Test
  fun `test-keys build tag is suspicious, release-keys is not`() {
    assertTrue(RootDetection.buildTagsLookSuspicious("release-keys,test-keys"))
    assertFalse(RootDetection.buildTagsLookSuspicious("release-keys"))
  }

  @Test
  fun `known emulator fingerprints and hardware are detected`() {
    assertTrue(RootDetection.looksLikeEmulator("generic/sdk/generic", "sdk_gphone64_arm64", "ranchu"))
    assertTrue(RootDetection.looksLikeEmulator("google/sdk_gphone64_arm64", "Android SDK built for arm64", "ranchu"))
    assertFalse(RootDetection.looksLikeEmulator("samsung/a53x/a53x", "SM-A536B", "exynos1280"))
  }

  @Test
  fun `root management package detection delegates to the supplied predicate`() {
    assertTrue(RootDetection.rootManagementPackageInstalled { it == "com.topjohnwu.magisk" })
    assertFalse(RootDetection.rootManagementPackageInstalled { false })
  }
}
