package app.trove.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trove.quest.api.PairingState
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space

/**
 * Login: the server (shared by both ways in), then "log in with your phone" (code + QR, the usual
 * way in a headset) beside the name and password fallback.
 */
@Composable
fun LoginScreen(model: AppModel, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.lg), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Trove", style = MaterialTheme.typography.titleLarge)
    Text("A Stash VR Companion", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
    OutlinedTextField(
        value = model.serverUrl,
        onValueChange = { model.serverUrl = it },
        label = { Text("Server") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.width(PANEL_W * 2 + GAP).panelIme(),
    )
    model.loginError?.let { Text(it, color = Palette.errorText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
      LoginCard(Modifier.width(PANEL_W)) { PairingCard(model) }
      LoginCard(Modifier.width(PANEL_W)) { PasswordForm(model) }
    }
  }
}

@Composable
private fun LoginCard(modifier: Modifier, content: @Composable () -> Unit) {
  Box(modifier.clip(RoundedCornerShape(Radius.card)).background(Palette.surface).padding(Space.xl)) { content() }
}

/** Device pairing: a code and a QR code; a phone approves on <server>/pair. */
@Composable
private fun PairingCard(model: AppModel) {
  Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(Space.md),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Log in with your phone", style = MaterialTheme.typography.titleMedium)
    when (val p = model.pairing) {
      PairingState.Starting -> {
        Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Text("Getting a code…", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
      }
      is PairingState.Waiting -> {
        QrCode(p.start.url, QR_SIZE)
        Text(
            p.start.code.chunked(3).joinToString(" "),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 48.sp,
            letterSpacing = 4.sp,
        )
        Text(
            "1. Scan the QR code, or open ${p.start.url.substringBefore("?")} and type the code\n" +
                "2. Log in on the phone\n" +
                "3. Tap Approve",
            color = Palette.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      is PairingState.Trouble -> {
        Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Text(p.message, color = Palette.errorText, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Text("Trying again…", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
      }
      PairingState.Denied -> {
        Text("The phone denied this headset.", color = Palette.errorText, style = MaterialTheme.typography.bodyLarge)
        PrimaryButton("New code", onClick = { model.startPairing() })
      }
      PairingState.Stopped -> {
        Text("The code expired.", color = Palette.muted, style = MaterialTheme.typography.bodyLarge)
        PrimaryButton("New code", onClick = { model.startPairing() })
      }
      is PairingState.Approved -> Text("Approved. Logging in…", style = MaterialTheme.typography.bodyLarge)
    }
    if (model.pairing is PairingState.Waiting || model.pairing is PairingState.Trouble) {
      QuietButton("New code", onClick = { model.startPairing() })
    }
  }
}

@Composable
private fun PasswordForm(model: AppModel) {
  var name by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
    Text("Or with name and password", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().panelIme(),
    )
    // Password keyboard (no suggestions or learning). Earlier "its keystrokes never reach the field"
    // was the second-field keyboard binding bug that PanelIme fixes, not the keyboard type.
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = KeyboardActions(onGo = { model.login(name, password) }),
        trailingIcon = { QuietButton(if (showPassword) "Hide" else "Show", onClick = { showPassword = !showPassword }) },
        modifier = Modifier.fillMaxWidth().panelIme(),
    )
    PrimaryButton(
        if (model.busy) "Logging in…" else "Log in",
        onClick = { model.login(name, password) },
        enabled = !model.busy && name.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

private val QR_SIZE = 260.dp
private val PANEL_W = 460.dp
private val GAP = Space.xl
