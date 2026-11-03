package app.trove.quest.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trove.quest.api.Projection
import app.trove.quest.gaze.panelPointer
import app.trove.quest.hold.HoldState
import app.trove.quest.hold.LocalPanelHeld
import app.trove.quest.hold.holdHintText
import app.trove.quest.sync.SessionTimer
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.ui.ResumeToyButton
import app.trove.quest.ui.StatusTags
import app.trove.quest.ui.fmtDuration
import app.trove.quest.ui.theme.ChipRow
import app.trove.quest.ui.theme.FocusAccent
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StashTheme
import app.trove.quest.ui.theme.StatusPill
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The row open on a screen's controls, instead of the primary + secondary rows (hoisted into [PlayerSession.extra] so B can close it). */
enum class Extra {
  NONE,
  VIEW,
  STREAM,
  MARKERS,

  /** Session timer: 15 / 30 / 45 / 60 / 90 min, +10, Cancel (sync/SessionTimer.kt). */
  TIMER,
}

/**
 * A screen's controls (840 x 252 dp): title + one status area; marker ticks, then elapsed / seek bar /
 * total / app volume; the primary row (-10 s, Play / Pause, +10 s, the resume offer); the secondary row (View,
 * Stream, Markers, Timer on the left; Library, Close on the right). View / Stream / Markers / Timer swap the two
 * bottom rows for their chips, in the same height. Status pills: [StatusTags.controls].
 */
@Composable
fun ControlPanel(session: PlayerSession) {
  StashTheme {
   // Only the primary screen's panel is up (ChromeRules); it fades / slides in and out as focus moves.
   // Leaving the composition when hidden also ends the position poll below.
   AnimatedVisibility(
       visible = session.controlsVisible && !session.closed,
       enter = fadeIn(tween(PlayerSession.FADE_MS.toInt())) + slideInVertically(tween(PlayerSession.FADE_MS.toInt())) { -it / 8 },
       exit = fadeOut(tween(PlayerSession.FADE_MS.toInt())) + slideOutVertically(tween(PlayerSession.FADE_MS.toInt())) { -it / 8 },
   ) {
   CompositionLocalProvider(LocalPanelHeld provides HoldState.any) {
    var position by remember { mutableLongStateOf(0L) }
    // Wall clock for the session timer's tag (the server's endsAt is epoch ms).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val markers = session.details?.markers.orEmpty()
    // Markers and the resume offer (SeekExtras.kt): a jump also drops the offer.
    val jump = { ms: Long ->
      session.resumeOfferMs = null
      session.seekTo(ms)
    }
    val shape = RoundedCornerShape(Radius.controlsPanel)

    // ExoPlayer has no position callback; poll it while the panel is up (the effect ends with the
    // composition when the panel is hidden) and the screen is open (review B6).
    LaunchedEffect(session) {
      while (!session.closed) {
        position = session.positionMs
        duration = session.durationMs
        now = System.currentTimeMillis()
        delay(250)
      }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(shape)
                .background(Palette.panel)
                // The focused screen's controls get the accent border (with the bar over the screen).
                .border(Size.borderFocus, if (session.highlighted) FocusAccent else Color.Transparent, shape)
                // Any touch on the controls focuses this screen (sound + full stream), then acts as usual.
                .pointerInput(session) {
                  awaitPointerEventScope {
                    while (true) {
                      val e = awaitPointerEvent(PointerEventPass.Initial)
                      // Pointing at the controls keeps look-to-focus quiet (gaze/).
                      if (e.type == PointerEventType.Press || e.type == PointerEventType.Move || e.type == PointerEventType.Enter) {
                        panelPointer.touch()
                        session.chromeTouched()
                      }
                      if (e.type == PointerEventType.Press) session.requestFocus()
                    }
                  }
                }
                .padding(horizontal = Space.lg, vertical = Space.sm + Space.xs / 2),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
      // Title and the one status area.
      val tags =
          StatusTags.controls(
              sync = session.sync.state,
              error = session.error,
              // While held with grip: what the thumbstick does (README "Moving panels").
              hint = holdHintText(session.holdHint),
              highlighted = session.highlighted,
              audible = session.audible,
              behindSphere = session.behindSphere,
              quality = session.qualityLabel,
              timer = SessionTimer.tagText(session.sync.state.timer, SessionTimer.serverNow(now, session.sync.state.clockOffsetMs)),
              timerWarn = SessionTimer.warning(session.sync.state.timer, SessionTimer.serverNow(now, session.sync.state.clockOffsetMs)),
          )
      Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            session.card.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        for (t in tags) StatusPill(t)
        // Start / Resume toy from the headset, only while the server offers it.
        ResumeToyButton(session.sync)
        // The VacuGlide panel was hidden: bring it back (only while the toy is there).
        session.vacuGlide?.takeIf { it.canRestore }?.let { OutlineButton("VacuGlide", onClick = { it.restore() }) }
      }

      // Seek bar with the marker ticks above it, elapsed on the left, total on the right.
      Row(Modifier.height(TICK_STRIP), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(TIME_W))
        Box(Modifier.weight(1f)) { MarkerTicks(markers, duration, onSeek = jump) }
        Spacer(Modifier.width(TIME_W))
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        TimeText(fmtDuration(position / 1000.0), TextAlign.End)
        Slider(
            value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = {
              dragging = true
              dragValue = it
            },
            onValueChangeFinished = {
              if (duration > 0) jump((dragValue * duration).toLong())
              dragging = false
            },
            modifier = Modifier.weight(1f),
        )
        TimeText(fmtDuration(duration / 1000.0), TextAlign.Start)
        // The app-wide volume (the stick's up / down sets the same level, and this follows it).
        VolumeSlider(session.volumeLevel, onChange = { session.setVolume(it) }, modifier = Modifier.width(VOLUME_W))
      }

      Column(Modifier.fillMaxWidth().height(BOTTOM_H), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        when (session.extra) {
          Extra.NONE -> {
            // Primary: what you do all the time.
            Row(Modifier.height(Size.targetLarge), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
              OutlineButton("−10 s", onClick = { session.seekBy(-10_000) })
              PrimaryButton(
                  if (session.isPlaying) "Pause" else "Play",
                  onClick = { session.togglePlay() },
                  modifier = Modifier.widthIn(min = 140.dp),
                  large = true,
              )
              OutlineButton("+10 s", onClick = { session.seekBy(10_000) })
              Spacer(Modifier.weight(1f))
              ResumeOffer(session)
              QuietButton("Hide controls", onClick = { session.hideControls() }, color = Palette.muted)
            }
            // Secondary: how it looks, what it plays, where the library is.
            Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
              SecondaryButton("View: ${session.projection.short}", onClick = { session.extra = Extra.VIEW })
              if ((session.details?.streams?.size ?: 0) > 1) SecondaryButton("Stream", onClick = { session.extra = Extra.STREAM })
              if (markers.isNotEmpty()) SecondaryButton("Markers (${markers.size})", onClick = { session.extra = Extra.MARKERS })
              SecondaryButton("Timer", onClick = { session.extra = Extra.TIMER })
              Spacer(Modifier.weight(1f))
              SecondaryButton("Next random", onClick = { session.nextRandom() })
              SecondaryButton(if (session.library.visible) "Hide library" else "Library", onClick = { session.library.toggle() })
              OutlineButton("Close", onClick = { session.close() })
            }
          }
          Extra.VIEW -> {
            ExtraRow("View", onDone = { session.extra = Extra.NONE }) {
              val detected = session.details?.detectedProjection?.let(Projection::fromId)
              ChipRow(
                  options = Projection.entries,
                  selected = session.projection,
                  label = { it.short + if (it == detected) " (auto)" else "" },
                  onPick = {
                    session.setProjection(it, save = true)
                    session.extra = Extra.NONE
                  },
                  modifier = Modifier.weight(1f),
              )
            }
            // Passthrough behind flat/3D screens (spheres keep it off); stays open to compare levels.
            if (!session.isSphere) {
              ExtraRow("Behind", onDone = null) {
                ChipRow(
                    options = RoomView.entries,
                    selected = session.room.view,
                    label = { it.label },
                    onPick = { session.room.select(it) },
                    modifier = Modifier.weight(1f),
                )
              }
            }
          }
          Extra.TIMER ->
              ExtraRow("Timer", onDone = { session.extra = Extra.NONE }) {
                TimerChips(session.sync, Modifier.weight(1f)) { session.extra = Extra.NONE }
              }
          Extra.MARKERS ->
              ExtraRow("Markers", onDone = { session.extra = Extra.NONE }) {
                MarkerChips(markers, onSeek = jump, modifier = Modifier.weight(1f))
              }
          Extra.STREAM ->
              ExtraRow("Stream", onDone = { session.extra = Extra.NONE }) {
                val streams = session.details?.streams.orEmpty()
                ChipRow(
                    options = streams,
                    // Transcode URLs carry start=<s>; compare without it.
                    selected =
                        streams.firstOrNull {
                          MultiScreen.withStart(session.streamUrl, 0).endsWith(MultiScreen.withStart(it.url, 0))
                        },
                    label = { it.label },
                    onPick = {
                      session.selectStream(it)
                      session.extra = Extra.NONE
                    },
                    modifier = Modifier.weight(1f),
                )
              }
        }
      }
    }
   }
   }
  }
}

/** A labelled row of chips of the View / Stream / Markers modes, with Done at the right when [onDone] is set. */
@Composable
private fun ExtraRow(label: String, onDone: (() -> Unit)?, chips: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
  Row(Modifier.height(Size.target), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
    RowLabel(label, Modifier.width(LABEL_W))
    chips()
    if (onDone != null) QuietButton("Done", onClick = onDone)
  }
}

/**
 * The session timer's chips: the presets, and +10 / Cancel while one runs. A pick goes to the server
 * (which owns the timer and answers in its status), then [onPicked] closes the row.
 */
@Composable
fun TimerChips(sync: SyncIndicator, modifier: Modifier = Modifier, onPicked: () -> Unit) {
  ChipRow(
      options = SessionTimer.choices(running = sync.state.timer != null),
      selected = null,
      label = { it.label },
      onPick = {
        sync.onTimer(it)
        onPicked()
      },
      modifier = modifier,
  )
}

@Composable
private fun TimeText(text: String, align: TextAlign) {
  Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.muted, textAlign = align, maxLines = 1, modifier = Modifier.width(TIME_W).padding(horizontal = Space.xs))
}

/**
 * The app volume as a slider in 10 % steps with its value ("70 %"); [level] 0..1. Used on the screen controls
 * (next to the seek bar) and on the Storm panel (the Clip row).
 */
@Composable
fun VolumeSlider(level: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
    Text("Vol", style = MaterialTheme.typography.labelMedium, color = Palette.muted, maxLines = 1)
    Slider(
        value = level,
        onValueChange = onChange,
        valueRange = 0f..1f,
        steps = AppVolume.SLIDER_STEPS,
        modifier = Modifier.weight(1f),
    )
    Text(
        "${(level * 100).roundToInt()} %",
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(48.dp),
    )
  }
}

private val TIME_W = 72.dp

/** The volume slider with its label and value, right of the total time. */
private val VOLUME_W = 220.dp
private val LABEL_W = 72.dp

/** Primary row (large target) + gap + secondary row. */
private val BOTTOM_H = Size.targetLarge + Space.xs + Size.target
