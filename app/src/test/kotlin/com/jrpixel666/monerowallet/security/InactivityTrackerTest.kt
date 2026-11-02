package com.jrpixel666.monerowallet.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InactivityTrackerTest {
  @Test
  fun `disabled policy never times out regardless of elapsed time`() {
    assertFalse(InactivityTracker.hasTimedOut(nowMillis = 1_000_000, lastInteractionMillis = 0, timeoutMinutes = 0))
    assertFalse(InactivityTracker.hasTimedOut(nowMillis = 1_000_000, lastInteractionMillis = 0, timeoutMinutes = -1))
  }

  @Test
  fun `has not timed out before the threshold`() {
    val oneMinuteMs = 60_000L
    assertFalse(
      InactivityTracker.hasTimedOut(nowMillis = oneMinuteMs - 1, lastInteractionMillis = 0, timeoutMinutes = 1),
    )
  }

  @Test
  fun `has timed out at and past the threshold`() {
    val oneMinuteMs = 60_000L
    assertTrue(InactivityTracker.hasTimedOut(nowMillis = oneMinuteMs, lastInteractionMillis = 0, timeoutMinutes = 1))
    assertTrue(InactivityTracker.hasTimedOut(nowMillis = oneMinuteMs * 5, lastInteractionMillis = 0, timeoutMinutes = 1))
  }

  @Test
  fun `recordInteraction advances the tracked timestamp`() {
    InactivityTracker.recordInteraction()
    val after = InactivityTracker.lastInteractionAtMillis
    assertFalse(InactivityTracker.hasTimedOut(timeoutMinutes = 60))
    assertTrue(after > 0)
  }
}
