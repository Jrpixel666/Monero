package com.jrpixel666.monerowallet.security

import android.content.Context

/**
 * The inactivity-timeout auto-lock setting (THREAT_MODEL.md #9): separate from
 * [WalletApplication]/`LockState`'s lock-on-backgrounding, which fires regardless of this value.
 * This one fires while the app stays foregrounded but the user hasn't touched it — tracked via
 * [InactivityTracker], driven by `Activity.onUserInteraction()`.
 *
 * Off by default (0 minutes = disabled): like the wipe-after-N-failures policy, an aggressive
 * default here is itself a usability/denial-of-access risk, not a safe thing to assume everyone
 * wants.
 */
object SecuritySettings {
  private const val PREFS = "security_settings"
  private const val KEY_INACTIVITY_MINUTES = "inactivity_timeout_minutes"

  fun inactivityTimeoutMinutes(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INACTIVITY_MINUTES, 0)

  fun setInactivityTimeoutMinutes(context: Context, minutes: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putInt(KEY_INACTIVITY_MINUTES, minutes.coerceAtLeast(0))
      .apply()
  }
}

/** The timestamp of the last user interaction (touch, key, trackball) the whole process has seen,
 * updated from `Activity.onUserInteraction()`. A plain `@Volatile var`, not Compose state: it's
 * polled from a timer loop, not observed directly by a composable. */
object InactivityTracker {
  @Volatile var lastInteractionAtMillis: Long = System.currentTimeMillis()
    private set

  fun recordInteraction() {
    lastInteractionAtMillis = System.currentTimeMillis()
  }

  /** True if [timeoutMinutes] have elapsed with no recorded interaction. `timeoutMinutes <= 0`
   * means the policy is disabled and this always returns false. */
  fun hasTimedOut(timeoutMinutes: Int): Boolean =
    hasTimedOut(nowMillis = System.currentTimeMillis(), lastInteractionMillis = lastInteractionAtMillis, timeoutMinutes = timeoutMinutes)

  /** The pure time arithmetic behind [hasTimedOut], separated out so it's unit-testable without
   * needing to fake the wall clock or reach into private singleton state. */
  fun hasTimedOut(nowMillis: Long, lastInteractionMillis: Long, timeoutMinutes: Int): Boolean {
    if (timeoutMinutes <= 0) return false
    return (nowMillis - lastInteractionMillis) >= timeoutMinutes * 60_000L
  }
}
