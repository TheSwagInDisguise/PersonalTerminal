package dev.personalterminal.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.theme.Term

/**
 * Full-screen lock gate (biometric or device credential). Shown by MainActivity whenever the
 * app-lock rule fires; the callback dismisses it. If the device has no screen lock at all the
 * prompt cannot run, so we say so and offer an explicit "continue anyway".
 */
@Composable
fun LockGate(onUnlock: () -> Unit) {
    val p = Term.palette
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    var canUseCredential by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }

    // Back would otherwise pop through an empty stack behind the gate.
    BackHandler { }

    fun authenticate() {
        val act = activity ?: run { onUnlock(); return }
        val prompt = BiometricPrompt(
            act,
            ContextCompat.getMainExecutor(ctx),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    error = null
                    onUnlock()
                }

                override fun onAuthenticationError(code: Int, errMsg: CharSequence) {
                    when (code) {
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        -> {
                            canUseCredential = false
                            error = "no screen lock enrolled · set a PIN / pattern in system settings"
                        }
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> Unit // dismissed – stay locked
                        else -> error = errMsg.toString()
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("personal terminal")
            .setSubtitle("terminal locked")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()
        runCatching { prompt.authenticate(info) }.onFailure {
            canUseCredential = false
            error = it.message
        }
    }

    // Fire the prompt the moment the gate appears.
    LaunchedEffect(attempt) { authenticate() }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text("~ locked", color = p.green, style = MaterialTheme.typography.headlineSmall)
            Text("personal terminal", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = p.yellow, style = MaterialTheme.typography.bodySmall) }
            TermButton(
                if (canUseCredential) "unlock" else "continue anyway",
                filled = canUseCredential,
                onClick = {
                    if (canUseCredential) authenticate()
                    else onUnlock()
                },
            )
        }
    }
}
