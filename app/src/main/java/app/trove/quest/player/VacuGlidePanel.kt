package app.trove.quest.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trove.quest.sync.ScriptRules
import app.trove.quest.sync.VacuGlideControl
import app.trove.quest.sync.VacuGlideRules
import app.trove.quest.sync.ValveKey
import app.trove.quest.ui.ResumeToyButton
import app.trove.quest.ui.StatusTag
import app.trove.quest.ui.StatusTags
import app.trove.quest.ui.TagLevel
import app.trove.quest.ui.theme.ChipRow
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StashTheme
import app.trove.quest.ui.theme.StatusPill
import kotlinx.coroutines.delay

/** Sizes of the two VacuGlide panels (metres; the panel is 700 dp per metre). */
object VacuGlidePanelSize {
  const val FULL_W_M = 1.2f
  const val FULL_H_M = 0.44f
  const val CHIP_W_M = 0.5f
  const val CHIP_H_M = 0.075f

  /** Gap between a screen's controls and the VacuGlide panel under them. */
  const val GAP_M = 0.02f
}

/**
 * The whole sub-panel (840 x 308 dp): the same controls and words as the Stash plugin "VacuGlide Sync", plus a
 * Script row when the scene has several funscripts (it takes the place of the suction hint).
 * Suction Tighter / Looser are hold-to-use (press opens the valve, release closes it); everything that
 * can end a press ends it: letting go, the pointer leaving the button or the panel, the panel going away.
 * "Stick: Volume / Suction" makes the thumbstick up / down hold Tighter / Looser (input/ControlScheme).
 */
@Composable
fun VacuGlideFullPanel(control: VacuGlideControl) {
  StashTheme {
    val vg = control.shown
    val shape = RoundedCornerShape(Radius.controlsPanel)
    // The 5 s cap of a press, and the notice's timeout.
    LaunchedEffect(control) {
      while (true) {
        delay(100)
        control.tick()
      }
    }
    DisposableEffect(control) { onDispose { control.release("panel gone") } }
    Column(
        Modifier.fillMaxSize()
            .clip(shape)
            .background(Palette.panel)
            // The pointer leaving the panel ends a press (a ray sliding off the button mid-hold).
            .pointerInput(control) {
              awaitPointerEventScope {
                while (true) {
                  val e = awaitPointerEvent(PointerEventPass.Initial)
                  if (e.type == PointerEventType.Exit) control.releaseButton("pointer left the panel")
                }
              }
            }
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
      Row(Modifier.height(Size.pillHeight + Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("VacuGlide", style = MaterialTheme.typography.titleSmall)
        StatusPill(StatusTag(VacuGlideRules.toyLine(vg), if (vg.valve != null || (vg.targetSpeed ?: 0) > 0) TagLevel.ACCENT else TagLevel.OK))
        Spacer(Modifier.weight(1f))
        QuietButton("Collapse ▾", onClick = { control.collapse() })
        QuietButton("Hide", onClick = { control.hide() }, color = Palette.muted)
      }
      val allowed = control.valvesAllowed
      Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Suction", Modifier.width(LABEL_W))
        for (k in ValveKey.entries) {
          HoldButton(k.label, pressed = control.held == k || vg.valve == k.wire, enabled = allowed, onPress = { control.press(k) }, onRelease = { control.releaseButton("up") })
        }
        Spacer(Modifier.weight(1f))
        RowLabel("Keep suction")
        SecondaryButton(VacuGlideRules.suctionLabel(vg.suction), onClick = { control.cycleSuction() }, modifier = Modifier.width(120.dp))
      }
      Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Offset", Modifier.width(LABEL_W))
        OutlineButton("−50", onClick = { control.stepOffset(-1) })
        Value(VacuGlideRules.offsetLabel(vg.offsetMs), 104.dp)
        OutlineButton("+50", onClick = { control.stepOffset(1) })
        Spacer(Modifier.weight(1f))
        RowLabel("Intensity")
        OutlineButton("−10", onClick = { control.stepIntensity(-1) })
        Value(VacuGlideRules.intensityLabel(vg.intensity), 76.dp)
        OutlineButton("+10", onClick = { control.stepIntensity(1) })
      }
      Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Speed limit", Modifier.width(LABEL_W))
        SecondaryButton(VacuGlideRules.speedLimitLabel(vg.speedLimit), onClick = { control.cycleSpeedLimit() }, modifier = Modifier.width(120.dp))
        Spacer(Modifier.weight(1f))
        // What the thumbstick up / down does while this panel is up: the app volume, or the valves (held).
        RowLabel("Stick")
        SecondaryButton(VacuGlideRules.stickLabel(control.stickSuction), onClick = { control.toggleStick() }, modifier = Modifier.width(120.dp))
        OutlineButton("Reload script", onClick = { control.reload() })
      }
      // Which of the scene's scripts drives the toy (only when there is a choice).
      val picker = control.scripts
      val sc = picker.current?.takeIf { ScriptRules.hasChoice(it.options) }
      if (sc != null) {
        Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          RowLabel("Script", Modifier.width(LABEL_W))
          val chosen = picker.chosen(sc.sceneId)
          ChipRow(
              sc.options,
              selected = sc.options.firstOrNull { it.id == chosen },
              label = ScriptRules::chipLabel,
              onPick = { picker.pick(sc.sceneId, it.id) },
              modifier = Modifier.weight(1f),
          )
        }
      }
      val scriptLine = sc?.let { picker.line(it.sceneId) }
      val problem = control.notice ?: scriptLine?.takeIf { it != ScriptRules.LOADING } ?: vg.error
      if (sc == null) Caption(VacuGlideRules.SUCTION_HINT)
      when {
        problem != null -> Caption(problem, Palette.errorText)
        scriptLine != null -> Caption(scriptLine, Palette.accent)
        else -> Caption(VacuGlideRules.offsetHint(vg.latencyMs))
      }
    }
  }
}

/** The collapsed panel: a small chip under the controls. */
@Composable
fun VacuGlideChip(control: VacuGlideControl) {
  StashTheme {
    val vg = control.shown
    val shape = RoundedCornerShape(Radius.control)
    Row(
        Modifier.fillMaxSize().clip(shape).background(Palette.panel).border(Size.borderThin, Palette.outline, shape).padding(horizontal = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
      QuietButton("VacuGlide ▸", onClick = { control.expand() })
      Text(
          if (vg.valve != null) "suction" else if ((vg.targetSpeed ?: 0) > 0) "${vg.targetSpeed} %" else "online",
          style = MaterialTheme.typography.labelMedium,
          color = Palette.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
      )
      QuietButton("Hide", onClick = { control.hide() }, color = Palette.muted)
    }
  }
}

/**
 * A button that is down while the pointer is: [onPress] at the press, [onRelease] at the release and at anything
 * else that ends it, including leaving the button (waitForUpOrCancellation reports an out-of-bounds pointer as
 * cancelled) and the composable leaving the composition (the gesture is cancelled and the finally block runs).
 */
@Composable
internal fun HoldButton(label: String, pressed: Boolean, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
  val shape = RoundedCornerShape(Radius.control)
  val fg = if (!enabled) Palette.outline else if (pressed) Palette.onAccent else Palette.text
  Box(
      modifier =
          Modifier.heightIn(min = Size.target)
              .widthIn(min = 168.dp)
              .clip(shape)
              .background(if (pressed) Palette.accent else Palette.raised)
              .border(Size.borderThin, if (enabled) Palette.outline else Palette.raised, shape)
              .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                  awaitFirstDown(requireUnconsumed = false)
                  onPress()
                  try {
                    waitForUpOrCancellation()
                  } finally {
                    onRelease()
                  }
                }
              },
      contentAlignment = Alignment.Center,
  ) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
    )
  }
}

@Composable
private fun Value(text: String, w: androidx.compose.ui.unit.Dp) {
  Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(w))
}

@Composable
private fun Caption(text: String, color: androidx.compose.ui.graphics.Color = Palette.muted) {
  Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}

private val LABEL_W = 104.dp

/**
 * The toy on a panel that is not a screen's controls (Storm): Start / Resume toy while the server offers it, and while
 * the VacuGlide is there the hold-to-use Tighter / Looser. Same press rules as the screen panel ([VacuGlideControl]).
 */
@Composable
fun VacuGlideToyRow(control: VacuGlideControl, sync: app.trove.quest.sync.SyncIndicator, labelWidth: androidx.compose.ui.unit.Dp) {
  LaunchedEffect(control) {
    while (true) {
      delay(100)
      control.tick()
    }
  }
  DisposableEffect(control) { onDispose { control.release("toy row gone") } }
  val vg = control.shown
  val allowed = control.valvesAllowed
  Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
    RowLabel("Toy", Modifier.width(labelWidth))
    if (control.available) {
      StatusPill(StatusTag(VacuGlideRules.toyLine(vg), if (vg.valve != null || (vg.targetSpeed ?: 0) > 0) TagLevel.ACCENT else TagLevel.OK))
      for (k in ValveKey.entries) {
        HoldButton(k.label, pressed = control.held == k || vg.valve == k.wire, enabled = allowed, onPress = { control.press(k) }, onRelease = { control.releaseButton("up") })
      }
    } else {
      StatusPill(StatusTag(StatusTags.sync(sync.state)?.text ?: "VacuGlide not online", TagLevel.NEUTRAL))
    }
    ResumeToyButton(sync)
    control.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.errorText, maxLines = 1) }
  }
}
