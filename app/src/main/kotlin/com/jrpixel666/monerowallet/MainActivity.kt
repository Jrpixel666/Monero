package com.jrpixel666.monerowallet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.monero_wallet_ffi.FfiNetwork
import uniffi.monero_wallet_ffi.FfiWallet
import uniffi.monero_wallet_ffi.WalletException

/**
 * Stage 1+2 smoke-test screen: proves the Rust core (via UniFFI) is reachable from a real Android
 * app and that generate -> encrypt -> persist -> decrypt -> restore works end to end on-device.
 *
 * This is deliberately NOT the onboarding/dashboard UI from the staged plan's stage 6 — no seed
 * backup, no biometric unlock, no address book. [`THREAT_MODEL.md`] rows 1-4 (clipboard, overlay,
 * accessibility, co-installed apps) remain `planned` until that real UI lands; the one mitigation
 * applied here is [`WindowManager.LayoutParams.FLAG_SECURE`], since any screen showing wallet
 * state should have it regardless of how placeholder the rest of the screen is.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          WalletScreen()
        }
      }
    }
  }
}

@Composable
private fun WalletScreen() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val walletFile = remember { File(context.filesDir, "wallet.enc") }

  var wallet by remember { mutableStateOf<FfiWallet?>(null) }
  var address by remember { mutableStateOf("(no wallet loaded)") }
  var password by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("") }

  // FfiWallet holds a native Rust object; close it deterministically rather than waiting on GC.
  DisposableEffect(Unit) {
    onDispose { wallet?.close() }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Monero Wallet — stage 1+2 core smoke test", style = MaterialTheme.typography.titleMedium)
    Text("Address: $address")
    if (status.isNotEmpty()) {
      Text(status, style = MaterialTheme.typography.bodySmall)
    }

    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text("Wallet password") },
      modifier = Modifier.fillMaxWidth(),
    )

    Button(
      onClick = {
        scope.launch {
          val newWallet = withContext(Dispatchers.Default) { FfiWallet.generate() }
          wallet?.close()
          wallet = newWallet
          address = newWallet.primaryAddress(FfiNetwork.MAINNET)
          status = "Generated a new wallet. Not yet saved to disk."
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Generate new wallet")
    }

    Button(
      onClick = {
        val current = wallet
        if (current == null) {
          status = "Generate a wallet first."
          return@Button
        }
        scope.launch {
          try {
            val envelope = withContext(Dispatchers.Default) { current.exportEncrypted(password) }
            walletFile.writeBytes(envelope)
            status = "Saved encrypted wallet (${envelope.size} bytes) to app-private storage."
          } catch (e: WalletException) {
            status = "Save failed: ${e.message}"
          }
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Save encrypted (with password above)")
    }

    Button(
      onClick = {
        if (!walletFile.exists()) {
          status = "No saved wallet file yet."
          return@Button
        }
        scope.launch {
          try {
            val envelope = walletFile.readBytes()
            val restored =
              withContext(Dispatchers.Default) { FfiWallet.restoreFromEncrypted(password, envelope) }
            wallet?.close()
            wallet = restored
            address = restored.primaryAddress(FfiNetwork.MAINNET)
            status = "Restored from the saved encrypted file."
          } catch (e: WalletException) {
            status = "Restore failed (wrong password?): ${e.message}"
          }
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Load from saved file (with password above)")
    }
  }
}
