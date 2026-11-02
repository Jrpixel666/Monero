package com.jrpixel666.monerowallet.security

import android.content.Context

/**
 * Opt-in wipe-after-N-failed-unlocks, per THREAT_MODEL.md #9. Off by default (`wipeAfterNFailures
 * == 0`) since an accidental trigger destroys funds-access with no recovery path beyond whatever
 * backup the user made themselves — this is a feature a user explicitly turns on, not a default
 * posture.
 *
 * BiometricPrompt already imposes its own temporary lockout after repeated OS-level failures;
 * this tracker is a separate, app-level counter layered on top of that, counting both explicit
 * failures and prompt-level errors the caller chooses to forward to [onFailure].
 */
class FailedAttemptTracker(context: Context) {
  private val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)

  var wipeAfterNFailures: Int
    get() = prefs.getInt(KEY_LIMIT, 0)
    set(value) = prefs.edit().putInt(KEY_LIMIT, value).apply()

  private var failureCount: Int
    get() = prefs.getInt(KEY_COUNT, 0)
    set(value) = prefs.edit().putInt(KEY_COUNT, value).apply()

  fun onSuccess() {
    failureCount = 0
  }

  /** Returns true once this failure has crossed the configured threshold and a wipe should run. */
  fun onFailure(): Boolean {
    val limit = wipeAfterNFailures
    if (limit <= 0) return false
    failureCount += 1
    val shouldWipe = failureCount >= limit
    if (shouldWipe) failureCount = 0
    return shouldWipe
  }

  companion object {
    private const val KEY_LIMIT = "wipe_after_n_failures"
    private const val KEY_COUNT = "failure_count"
  }
}
