package com.jrpixel666.monerowallet

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jrpixel666.monerowallet.security.FailedAttemptTracker
import com.jrpixel666.monerowallet.security.PinVault
import com.jrpixel666.monerowallet.security.RootDetection
import com.jrpixel666.monerowallet.security.SecureVault
import com.jrpixel666.monerowallet.security.WalletFiles
import com.jrpixel666.monerowallet.ui.theme.Omarchy
import com.jrpixel666.monerowallet.ui.theme.OmarchyTheme
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.monero_wallet_ffi.FfiNetwork
import uniffi.monero_wallet_ffi.FfiWallet
import uniffi.monero_wallet_ffi.WalletException
import uniffi.monero_wallet_ffi.fetchChainHeight

/**
 * Stage 1+2+3 smoke-test screen: proves the Rust core (via UniFFI) plus the Android security
 * mechanisms from THREAT_MODEL.md — Keystore-wrapped biometric unlock, PIN-routed decoy wallet,
 * root/emulator warning, process-level auto-lock, opt-in wipe-after-N-failures, sensitive
 * clipboard — are wired together and compile/run as real code paths, not just described in docs.
 *
 * This is NOT stage 6's real onboarding/dashboard/send UI; that's a from-scratch screen with
 * seed backup, address book, etc. This screen's job is to be the one place every stage-1-to-3
 * mechanism gets exercised end to end before stage 6 builds the real thing around them.
 */
class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    // A tap that reaches this activity while another app's view is drawn on top of it (a
    // tapjacking overlay) is dropped rather than delivered. See THREAT_MODEL.md #2.
    window.decorView.filterTouchesWhenObscured = true

    setContent {
      OmarchyTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          WalletScreen(activity = this)
        }
      }
    }
  }
}

private fun authenticate(
  activity: FragmentActivity,
  cipher: Cipher,
  title: String,
  onSuccess: (Cipher) -> Unit,
  onError: (String) -> Unit,
) {
  val promptInfo =
    BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setAllowedAuthenticators(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
      )
      .build()

  val prompt =
    BiometricPrompt(
      activity,
      ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          val authenticatedCipher = result.cryptoObject?.cipher
          if (authenticatedCipher != null) {
            onSuccess(authenticatedCipher)
          } else {
            onError("authentication succeeded but no cipher was returned")
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          onError(errString.toString())
        }

        override fun onAuthenticationFailed() {
          // A single wrong fingerprint/face; the system prompt stays open for another attempt,
          // so this isn't terminal by itself — see FailedAttemptTracker for the opt-in policy
          // tied to this callback at the call site below.
        }
      },
    )
  prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
}

@Composable
private fun WalletScreen(activity: FragmentActivity) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var wallet by remember { mutableStateOf<FfiWallet?>(null) }
  var address by remember { mutableStateOf("") }
  var usingDecoy by remember { mutableStateOf(false) }
  var pin by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("") }
  // A node this project verified reachable over HTTPS/443 during development — most public nodes
  // listen on 18081/18089 instead, which this sandbox's network couldn't reach (see crates/rpc).
  var daemonUrl by remember { mutableStateOf("https://xmr.kret.dev") }

  val rootSuspicious = remember { RootDetection.isDeviceSuspicious(context) }
  val pinVault = remember { PinVault(context) }
  val tracker = remember { FailedAttemptTracker(context) }

  DisposableEffect(Unit) { onDispose { wallet?.close() } }

  fun vaultAndFileFor(real: Boolean): Pair<SecureVault, File> =
    if (real) {
      SecureVault(context, SecureVault.REAL_VAULT_FILE) to File(context.filesDir, WalletFiles.REAL)
    } else {
      SecureVault(context, SecureVault.DECOY_VAULT_FILE) to File(context.filesDir, WalletFiles.DECOY)
    }

  fun unlock(real: Boolean) {
    val (vault, file) = vaultAndFileFor(real)
    if (!vault.hasStoredPassword() || !file.exists()) {
      status = "No ${if (real) "real" else "decoy"} wallet has been protected yet."
      return
    }
    val cipher =
      try {
        vault.cipherForUnlocking()
      } catch (e: Exception) {
        status = "Could not prepare unlock: ${e.message}"
        return
      }
    authenticate(
      activity = activity,
      cipher = cipher,
      title = "Unlock wallet",
      onSuccess = { authenticatedCipher ->
        scope.launch {
          try {
            val devicePassword =
              withContext(Dispatchers.Default) {
                String(vault.finishUnlocking(authenticatedCipher), Charsets.UTF_8)
              }
            val envelope = file.readBytes()
            val restored =
              withContext(Dispatchers.Default) { FfiWallet.restoreFromEncrypted(devicePassword, envelope) }
            wallet?.close()
            wallet = restored
            address = restored.primaryAddress(FfiNetwork.MAINNET)
            usingDecoy = !real
            LockState.locked = false
            tracker.onSuccess()
            status = "Unlocked the ${if (real) "real" else "decoy"} wallet."
          } catch (e: WalletException) {
            status = "Unlock failed: ${e.message}"
          }
        }
      },
      onError = { message ->
        if (tracker.onFailure()) {
          file.delete()
          vault.wipe()
          if (real) pinVault.setRealPin("") else pinVault.setDecoyPin("")
          status = "Too many failed attempts: wiped the ${if (real) "real" else "decoy"} wallet, as configured."
        } else {
          status = "Unlock error: $message"
        }
      },
    )
  }

  fun protectAndSave(real: Boolean) {
    val current = wallet
    if (current == null) {
      status = "Generate a wallet first."
      return
    }
    val (vault, file) = vaultAndFileFor(real)
    val devicePassword =
      android.util.Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
    val cipher =
      try {
        vault.cipherForStoring()
      } catch (e: Exception) {
        status = "Could not prepare protection: ${e.message}"
        return
      }
    authenticate(
      activity = activity,
      cipher = cipher,
      title = "Protect wallet with biometrics",
      onSuccess = { authenticatedCipher ->
        scope.launch {
          try {
            val envelope = withContext(Dispatchers.Default) { current.exportEncrypted(devicePassword) }
            file.writeBytes(envelope)
            withContext(Dispatchers.Default) {
              vault.finishStoring(authenticatedCipher, devicePassword.toByteArray(Charsets.UTF_8))
            }
            if (pin.isNotBlank()) {
              if (real) pinVault.setRealPin(pin) else pinVault.setDecoyPin(pin)
            }
            status = "Saved and biometrically protected the ${if (real) "real" else "decoy"} wallet."
          } catch (e: WalletException) {
            status = "Save failed: ${e.message}"
          }
        }
      },
      onError = { status = "Could not authenticate to protect wallet: $it" },
    )
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 28.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("MONERO WALLET", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
      Text(
        "stage 1-4 core smoke test",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (rootSuspicious) {
      OmarchySectionCard(borderColor = MaterialTheme.colorScheme.error) {
        Text(
          "WARNING: this device looks rooted or is an emulator.\nYour keys may be less protected here.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    if (status.isNotEmpty()) {
      Text("> $status", style = MaterialTheme.typography.bodySmall, color = Omarchy.DarkForeground)
    }

    OmarchySectionCard {
      OmarchyLabel("DAEMON (STAGE 4, NO TOR YET — SEE crates/rpc DOCS)")
      OmarchyTextField(value = daemonUrl, onValueChange = { daemonUrl = it }, label = "Daemon URL")
      OutlinedButton(
        onClick = {
          scope.launch {
            try {
              val height = withContext(Dispatchers.IO) { fetchChainHeight(daemonUrl) }
              status = "Chain height from $daemonUrl: $height"
            } catch (e: WalletException) {
              status = "Daemon request failed: ${e.message}"
            }
          }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Check live chain height")
      }
    }

    if (LockState.locked) {
      OmarchySectionCard {
        OmarchyLabel("WALLET LOCKED")
        Button(onClick = { unlock(real = true) }, modifier = Modifier.fillMaxWidth()) {
          Text("Unlock real wallet (biometrics)")
        }
        OutlinedButton(onClick = { unlock(real = false) }, modifier = Modifier.fillMaxWidth()) {
          Text("Unlock decoy wallet (biometrics)")
        }

        OmarchyTextField(value = pin, onValueChange = { pin = it }, label = "Or enter PIN")
        OutlinedButton(
          onClick = {
            when (pinVault.check(pin)) {
              PinVault.Match.REAL -> unlock(real = true)
              PinVault.Match.DECOY -> unlock(real = false)
              PinVault.Match.NONE -> status = "That PIN didn't match either wallet."
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Open with PIN")
        }
      }
    } else {
      OmarchySectionCard {
        OmarchyLabel(if (usingDecoy) "ADDRESS (DECOY WALLET)" else "ADDRESS")
        Text(
          address.ifEmpty { "(none yet)" },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(
          onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Monero address", address)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              clip.description.extras =
                PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            clipboard.setPrimaryClip(clip)
            status = "Address copied to clipboard."
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Copy address")
        }
      }

      OmarchySectionCard {
        OmarchyLabel("ACTIONS")

        Button(
          onClick = {
            scope.launch {
              val newWallet = withContext(Dispatchers.Default) { FfiWallet.generate() }
              wallet?.close()
              wallet = newWallet
              address = newWallet.primaryAddress(FfiNetwork.MAINNET)
              usingDecoy = false
              status = "Generated a new wallet. Not yet saved."
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Generate new wallet")
        }

        OmarchyTextField(value = pin, onValueChange = { pin = it }, label = "PIN to open this wallet later (optional)")

        OutlinedButton(onClick = { protectAndSave(real = true) }, modifier = Modifier.fillMaxWidth()) {
          Text("Protect & save as REAL wallet")
        }
        OutlinedButton(onClick = { protectAndSave(real = false) }, modifier = Modifier.fillMaxWidth()) {
          Text("Protect & save as DECOY wallet")
        }
        OutlinedButton(
          onClick = {
            wallet?.close()
            wallet = null
            address = ""
            LockState.locked = true
            status = "Locked."
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text("Lock now")
        }
      }
    }
  }
}

/** A flat, bordered card — Omarchy's terminal-window aesthetic, not a soft drop-shadowed one. */
@Composable
private fun OmarchySectionCard(
  borderColor: androidx.compose.ui.graphics.Color = Omarchy.Muted,
  content: @Composable () -> Unit,
) {
  OutlinedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(borderColor)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      content()
    }
  }
}

@Composable
private fun OmarchyLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OmarchyTextField(value: String, onValueChange: (String) -> Unit, label: String) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
    textStyle = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.fillMaxWidth(),
  )
}
