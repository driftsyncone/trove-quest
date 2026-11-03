package app.trove.quest.update

import app.trove.quest.sync.SyncIndicator
import app.trove.quest.ui.ResumeToyButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space
import android.os.SystemClock
import app.trove.quest.player.ChromeRules
import app.trove.quest.player.ChromeState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Header button: who is logged in and the installed version ("alice · v0.2.8"); opens the account
 * panel (version, updates, Log out). A dot means an update is waiting that you put off with Later.
 */
@Composable
fun AccountButton(updates: UpdateModel, userName: String) {
  val dot = if (updates.offered != null && !updates.cardVisible) " •" else ""
  val who = if (userName.isEmpty()) "" else "$userName · "
  QuietButton("${who}v${updates.installedVersionName}$dot", onClick = { updates.aboutOpen = !updates.aboutOpen }, color = Palette.muted)
}

/** Account panel (when open) and the update card (when there is one). Goes under the wall header. */
@Composable
fun UpdateArea(updates: UpdateModel, serverUrl: String, chrome: ChromeState, onLogout: () -> Unit, sync: SyncIndicator = SyncIndicator()) {
  if (updates.aboutOpen) AccountPanel(updates, serverUrl, chrome, onLogout, sync)
  if (updates.cardVisible) UpdateCard(updates)
}

@Composable
private fun AccountPanel(updates: UpdateModel, serverUrl: String, chrome: ChromeState, onLogout: () -> Unit, sync: SyncIndicator) {
  Row(
      modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Palette.surface)
          .padding(horizontal = Space.lg, vertical = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Space.md),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
          "Trove ${updates.installedVersionName} (build ${updates.installedVersionCode}) · $serverUrl",
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
      )
      Text(
          aboutStatus(updates),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          color =
              if (updates.state is UpdateState.Failed && updates.offered == null) Palette.errorText
              else Palette.muted,
      )
    }
    OutlineButton(
        if (updates.state == UpdateState.Checking) "Checking…" else "Check for updates",
        onClick = { updates.checkNow() },
        enabled = updates.state != UpdateState.Checking,
    )
    OutlineButton("Button hints: ${if (updates.buttonHints) "Auto" else "Off"}", onClick = { updates.toggleButtonHints() })
    OutlineButton(
        "Auto-hide controls: ${ChromeRules.autoHideLabel(chrome.autoHideSecs)}",
        onClick = { chrome.cycleAutoHide(SystemClock.elapsedRealtime()) },
    )
    ResumeToyButton(sync)
    OutlineButton("Log out", onClick = onLogout)
    QuietButton("Close", onClick = { updates.aboutOpen = false }, color = Palette.muted)
  }
}

private fun aboutStatus(updates: UpdateModel): String {
  val last =
      if (updates.lastCheckMs <= 0) "never checked"
      else "last checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
          .format(Date(updates.lastCheckMs))
  val s = updates.state
  val what =
      when {
        s == UpdateState.Checking -> "Checking…"
        s == UpdateState.UpToDate -> "Up to date"
        s == UpdateState.NoRelease -> "No update published on the server yet"
        s == UpdateState.Unsupported -> "This server has no app updates (update the Trove server)"
        s is UpdateState.Failed && s.info == null -> s.message
        updates.offered != null -> "Trove ${updates.offered!!.label} is available"
        else -> "Checks automatically at most every 6 hours"
      }
  return "$what · $last"
}

@Composable
private fun UpdateCard(updates: UpdateModel) {
  val info = updates.offered ?: return
  val s = updates.state
  val edge =
      when (s) {
        is UpdateState.Failed -> Palette.alert
        is UpdateState.Installed -> Palette.ok
        else -> Palette.accent
      }
  val shape = RoundedCornerShape(Radius.card)
  Column(
      modifier =
          Modifier.fillMaxWidth().clip(shape).background(Palette.raised).border(Size.borderThin, edge, shape)
              .padding(horizontal = Space.lg, vertical = Space.md),
      verticalArrangement = Arrangement.spacedBy(Space.sm),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
      Column(Modifier.weight(1f)) {
        Text("Trove ${info.label} is available", style = MaterialTheme.typography.titleMedium)
        Text(
            "You have ${updates.installedVersionName}" +
                (if (info.sizeBytes > 0) " · download ${mb(info.sizeBytes)}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.muted,
        )
      }
      when (s) {
        is UpdateState.Available -> {
          QuietButton("Later", onClick = { updates.dismiss() }, color = Palette.muted)
          PrimaryButton("Install", onClick = { updates.install() })
        }
        is UpdateState.Failed -> {
          QuietButton("Later", onClick = { updates.dismiss() }, color = Palette.muted)
          PrimaryButton("Retry", onClick = { updates.install() })
        }
        else -> Unit
      }
    }

    when (s) {
      is UpdateState.Downloading -> {
        val frac = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp))
        Text(
            "Downloading ${(frac * 100).toInt()} % (${mb(s.done)} of ${mb(s.total)})",
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      is UpdateState.Installing ->
          Text(
              if (s.waitingForUser) "Confirm with Update (or Install) in the system dialog; the first time it may " +
                  "ask you to allow Trove to install apps first. Trove closes while it updates; open it " +
                  "again from the App Library. If Trove seems stuck after a system window, close it and open it again."
              else "Installing…",
              style = MaterialTheme.typography.bodyMedium,
          )
      is UpdateState.Installed ->
          Text("Installed ${info.label}. Android closes the old version; open Trove again from the App Library.",
              style = MaterialTheme.typography.bodyMedium, color = Palette.ok)
      is UpdateState.Failed ->
          Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Palette.errorText)
      else -> Unit
    }

    if (info.notes.isNotBlank() && s !is UpdateState.Downloading) {
      Text(
          UpdateRules.displayNotes(info.notes),
          style = MaterialTheme.typography.bodySmall,
          color = Palette.muted,
          modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
      )
    }
  }
}

private fun mb(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
