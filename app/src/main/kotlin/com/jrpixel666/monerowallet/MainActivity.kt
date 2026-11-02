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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jrpixel666.monerowallet.security.FailedAttemptTracker
import com.jrpixel666.monerowallet.security.PinVault
import com.jrpixel666.monerowallet.security.RootDetection
import com.jrpixel666.monerowallet.security.SecureVault
import com.jrpixel666.monerowallet.security.WalletFiles
import com.jrpixel666.monerowallet.ui.components.TerminalButton
import com.jrpixel666.monerowallet.ui.components.TerminalOutlineButton
import com.jrpixel666.monerowallet.ui.components.TerminalPanel
import com.jrpixel666.monerowallet.ui.components.TerminalPrompt
import com.jrpixel666.monerowallet.ui.components.TerminalStatusLine
import com.jrpixel666.monerowallet.ui.components.TerminalTextField
import com.jrpixel666.monerowallet.ui.components.ThemePicker
import com.jrpixel666.monerowallet.ui.components.WallpaperBackground
import com.jrpixel666.monerowallet.ui.components.WaybarTopBar
import com.jrpixel666.monerowallet.ui.theme.Omarchy
import com.jrpixel666.monerowallet.ui.theme.OmarchyTheme
import com.jrpixel666.monerowallet.ui.theme.ThemePreferences
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.monero_wallet_ffi.FfiNetwork
import uniffi.monero_wallet_ffi.FfiViewOnlyWallet
import uniffi.monero_wallet_ffi.FfiWallet
import uniffi.monero_wallet_ffi.WalletException
import uniffi.monero_wallet_ffi.fetchChainHeight
import uniffi.monero_wallet_ffi.parseMoneroUri

/**
 * Stage 1-5 smoke-test screen: proves the Rust core (via UniFFI) plus the Android security
 * mechanisms from THREAT_MODEL.md — Keystore-wrapped biometric unlock, PIN-routed decoy wallet,
 * root/emulator warning, process-level auto-lock, opt-in wipe-after-N-failures, sensitive
 * clipboard, subaddresses, view-only wallets, `monero:` URI parsing, a local address book — are
 * wired together and compile/run as real code paths, not just described in docs.
 *
 * Visually, this screen is Omarchy-themed deliberately beyond a color swap: real JetBrains Mono
 * (Omarchy's actual default font, confirmed from its own manual — see ui/theme/Theme.kt), a
 * from-scratch tiling-WM/terminal widget set (ui/components/TerminalWidgets.kt) in place of
 * reskinned Material3 cards/buttons/text fields, a selectable set of Omarchy's own themes with
 * their exact palettes and one of their own per-theme wallpapers each (ui/theme/Color.kt,
 * ui/theme/ThemePreferences.kt), persisted across restarts.
 *
 * This is NOT stage 6's real onboarding/dashboard/send UI; that's a from-scratch screen. This
 * screen's job is to be the one place every stage-1-to-5 mechanism gets exercised end to end
 * before stage 6 builds the real thing around them. Explicitly missing, and why: transaction
 * building/broadcast (needs decoy selection + monero-wallet's send module — a major separate
 * undertaking), fee priorities in the UI (meaningless without a send flow to attach them to,
 * though `rpc::DaemonClient::fee_rate` is live-tested at the Rust layer), cold signing via
 * animated QR/UR codes, and multisig (both substantial, separately-scoped future work).
 */
class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    // A tap that reaches this activity while another app's view is drawn on top of it (a
    // tapjacking overlay) is dropped rather than delivered. See THREAT_MODEL.md #2.
    window.decorView.filterTouchesWhenObscured = true
    // Before the first frame, not after: otherwise the default theme flashes before the saved
    // one takes over.
    ThemePreferences.restore(applicationContext)

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

  var subaddressIndex by remember { mutableStateOf(0u) }
  var subaddresses by remember { mutableStateOf(listOf<String>()) }

  var uriInput by remember { mutableStateOf("") }
  var uriResult by remember { mutableStateOf("") }

  var addressBookLabel by remember { mutableStateOf("") }
  var addressBookAddress by remember { mutableStateOf("") }
  val addressBook = remember { AddressBook(context) }
  var addressBookEntries by remember { mutableStateOf(addressBook.all()) }

  var voSpendKeyHex by remember { mutableStateOf("") }
  var voViewKeyHex by remember { mutableStateOf("") }
  var voAddress by remember { mutableStateOf("") }

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

  Box(modifier = Modifier.fillMaxSize()) {
    WallpaperBackground()

    Column(modifier = Modifier.fillMaxSize()) {
      WaybarTopBar(locked = LockState.locked, subtitle = "stage 1-5")

      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        TerminalPrompt("status --wallet")

        TerminalPanel(title = "theme — omarchy, selectable") {
          ThemePicker(
            onSelect = { palette ->
              Omarchy.select(palette)
              ThemePreferences.save(context, palette)
            },
          )
        }

        if (rootSuspicious) {
          TerminalPanel(title = "security warning", accentColor = Omarchy.Red) {
            Text(
              "This device looks rooted or is an emulator.\nYour keys may be less protected here.",
              style = MaterialTheme.typography.bodySmall,
              color = Omarchy.Red,
            )
          }
        }

        TerminalStatusLine(status)

        TerminalPanel(title = "daemon — no tor yet, see crates/rpc") {
          TerminalTextField(value = daemonUrl, onValueChange = { daemonUrl = it }, label = "daemon url")
          TerminalOutlineButton(
            label = "Check live chain height",
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
          )
        }

        TerminalPanel(title = "view-only wallet (watch-only)") {
          TerminalTextField(value = voSpendKeyHex, onValueChange = { voSpendKeyHex = it }, label = "public spend key (hex)")
          TerminalTextField(value = voViewKeyHex, onValueChange = { voViewKeyHex = it }, label = "private view key (hex)")
          TerminalOutlineButton(
            label = "Import view-only wallet",
            onClick = {
              try {
                val viewOnly = FfiViewOnlyWallet.fromKeys(voSpendKeyHex, voViewKeyHex)
                voAddress = viewOnly.primaryAddress(FfiNetwork.MAINNET)
                status = "Imported view-only wallet."
              } catch (e: WalletException) {
                status = "View-only import failed: ${e.message}"
              }
            },
            modifier = Modifier.fillMaxWidth(),
          )
          if (voAddress.isNotEmpty()) {
            Text(voAddress, style = MaterialTheme.typography.bodyMedium, color = Omarchy.BrightForeground)
          }
          Text(
            "tip: unlock the real wallet below, then use \"export view-only keys\" to fill these in",
            style = MaterialTheme.typography.bodySmall,
            color = Omarchy.DarkForeground,
          )
        }

        if (LockState.locked) {
          TerminalPanel(title = "wallet locked", accentColor = Omarchy.Red) {
            TerminalButton(label = "Unlock real wallet (biometrics)", onClick = { unlock(real = true) }, modifier = Modifier.fillMaxWidth())
            TerminalOutlineButton(
              label = "Unlock decoy wallet (biometrics)",
              onClick = { unlock(real = false) },
              modifier = Modifier.fillMaxWidth(),
            )

            TerminalTextField(value = pin, onValueChange = { pin = it }, label = "or enter pin")
            TerminalOutlineButton(
              label = "Open with PIN",
              onClick = {
                when (pinVault.check(pin)) {
                  PinVault.Match.REAL -> unlock(real = true)
                  PinVault.Match.DECOY -> unlock(real = false)
                  PinVault.Match.NONE -> status = "That PIN didn't match either wallet."
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        } else {
          TerminalPanel(title = if (usingDecoy) "address (decoy wallet)" else "address", accentColor = Omarchy.Green) {
            Text(address.ifEmpty { "(none yet)" }, style = MaterialTheme.typography.bodyMedium, color = Omarchy.BrightForeground)
            TerminalOutlineButton(
              label = "Copy address",
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
            )
            TerminalOutlineButton(
              label = "Export view-only keys",
              onClick = {
                wallet?.let {
                  voSpendKeyHex = it.publicSpendKeyHex()
                  voViewKeyHex = it.exportPrivateViewKeyHex()
                  status = "Filled the view-only wallet fields above from this wallet's keys."
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }

          TerminalPanel(title = "subaddresses — account 0") {
            if (subaddresses.isNotEmpty()) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                subaddresses.forEachIndexed { i, addr ->
                  Text("[${i + 1}] $addr", style = MaterialTheme.typography.bodySmall, color = Omarchy.Foreground)
                }
              }
            }
            TerminalOutlineButton(
              label = "Derive next subaddress",
              onClick = {
                val current = wallet ?: return@TerminalOutlineButton
                subaddressIndex += 1u
                try {
                  val sub = current.subaddress(FfiNetwork.MAINNET, 0u, subaddressIndex)
                  subaddresses = subaddresses + sub
                } catch (e: WalletException) {
                  status = "Subaddress derivation failed: ${e.message}"
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }

          TerminalPanel(title = "parse monero: uri") {
            TerminalTextField(value = uriInput, onValueChange = { uriInput = it }, label = "monero:<address>?tx_amount=...")
            TerminalOutlineButton(
              label = "Parse",
              onClick = {
                try {
                  val parsed = parseMoneroUri(uriInput)
                  val xmr =
                    parsed.amountPiconero?.let {
                      it.toString().padStart(13, '0').let { s -> "${s.dropLast(12)}.${s.takeLast(12)}" }
                    }
                  uriResult =
                    buildString {
                      append("address: ${parsed.address}\n")
                      append("amount: ${xmr ?: "(none)"}\n")
                      append("description: ${parsed.description ?: "(none)"}\n")
                      append("recipient: ${parsed.recipientName ?: "(none)"}")
                    }
                } catch (e: WalletException) {
                  uriResult = ""
                  status = "URI parse failed: ${e.message}"
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )
            if (uriResult.isNotEmpty()) {
              Text(uriResult, style = MaterialTheme.typography.bodySmall, color = Omarchy.Foreground)
            }
          }

          TerminalPanel(title = "address book — local, unencrypted") {
            if (addressBookEntries.isNotEmpty()) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                addressBookEntries.forEach { entry ->
                  Text("${entry.label}: ${entry.address}", style = MaterialTheme.typography.bodySmall, color = Omarchy.Foreground)
                }
              }
            }
            TerminalTextField(value = addressBookLabel, onValueChange = { addressBookLabel = it }, label = "label")
            TerminalTextField(value = addressBookAddress, onValueChange = { addressBookAddress = it }, label = "address")
            TerminalOutlineButton(
              label = "Add to address book",
              onClick = {
                if (addressBookLabel.isBlank() || addressBookAddress.isBlank()) {
                  status = "Enter both a label and an address."
                  return@TerminalOutlineButton
                }
                addressBook.add(addressBookLabel, addressBookAddress)
                addressBookEntries = addressBook.all()
                addressBookLabel = ""
                addressBookAddress = ""
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }

          TerminalPanel(title = "actions") {
            TerminalButton(
              label = "Generate new wallet",
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
            )

            TerminalTextField(value = pin, onValueChange = { pin = it }, label = "pin to open this wallet later (optional)")

            TerminalOutlineButton(label = "Protect & save as REAL wallet", onClick = { protectAndSave(real = true) }, modifier = Modifier.fillMaxWidth())
            TerminalOutlineButton(label = "Protect & save as DECOY wallet", onClick = { protectAndSave(real = false) }, modifier = Modifier.fillMaxWidth())
            TerminalOutlineButton(
              label = "Lock now",
              danger = true,
              onClick = {
                wallet?.close()
                wallet = null
                address = ""
                LockState.locked = true
                status = "Locked."
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
  }
}
