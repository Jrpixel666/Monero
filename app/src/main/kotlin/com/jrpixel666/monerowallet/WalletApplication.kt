package com.jrpixel666.monerowallet

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Registers the app-wide auto-lock trigger: the moment the whole process (not just this
 * activity) leaves the foreground — backgrounded, task-switched away from, screen off — any
 * unlocked wallet state is considered locked again and must be re-authenticated. See
 * THREAT_MODEL.md #9.
 *
 * [LockState] is a single process-wide flag rather than a proper ViewModel/DI-scoped service
 * because this app has exactly one screen (see MainActivity's doc comment); stage 6's real app
 * should replace this with a scoped session/auth service rather than copy the pattern.
 */
class WalletApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    ProcessLifecycleOwner.get().lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
          LockState.locked = true
        }
      },
    )
  }
}

object LockState {
  // Compose-observable: any @Composable reading this recomposes automatically the moment
  // WalletApplication flips it on process backgrounding, with no manual onResume plumbing needed.
  var locked by mutableStateOf(true)
}
