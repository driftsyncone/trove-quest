@file:OptIn(UnstableApi::class)

package app.trove.quest.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.trove.quest.PanelIds
import app.trove.quest.Placement
import app.trove.quest.R
import app.trove.quest.UI_DP_PER_METER
import app.trove.quest.api.ApiException
import app.trove.quest.api.Card
import app.trove.quest.api.Projection
import app.trove.quest.api.Scene
import app.trove.quest.api.TroveApi
import app.trove.quest.api.Stream
import app.trove.quest.hold.HoldHint
import app.trove.quest.hold.HoldRules
import app.trove.quest.hold.HoldSystem
import app.trove.quest.hold.HoldTarget
import app.trove.quest.layout.Facing
import app.trove.quest.input.PanelInput
import app.trove.quest.input.PanelRender
import app.trove.quest.input.PointerRegion
import app.trove.quest.input.Row
import app.trove.quest.input.Target
import app.trove.quest.input.rectAt
import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.Vec
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.sync.VacuGlideControl
import app.trove.quest.sync.VgPanel
import app.trove.quest.ui.theme.FocusAccent
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelCreationSystem
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a screen tells the [PlayerManager]. */
interface SessionHost {
  /** Trigger on the screen or a touch on its controls. */
  fun requestFocus(s: PlayerSession)

  /** Whether [s] is the focused screen (a lone screen always is). */
  fun isFocused(s: PlayerSession): Boolean

  /** The control panel's Next random: another random scene in this screen's place. */
  fun nextRandom(s: PlayerSession) {}

  /** App volume 0..1 (control scheme: stick up / down); the screen with sound plays at this level. */
  val volumeLevel: Float
    get() = 1f

  /** The controls' Volume slider: the app volume to [level] (0..1). */
  fun setVolume(level: Float) {}

  /** The projection changed shape (flat <-> sphere). */
  fun projectionChanged(s: PlayerSession)

  /** Called once when closed; replacing = another scene is opening in its place. */
  fun closed(s: PlayerSession, replacing: Boolean)

  /** See [ScreenSet.mayUpgrade]. */
  fun mayUpgrade(s: PlayerSession): Boolean

  /** Moved, resized or tilted by hand (grip let go): save the layout. */
  fun layoutChanged()

  /** Face me is on: flat screens are kept square to the head (layout/FaceMe.kt). */
  val faceMe: Boolean
    get() = false

  /** World position of the head, for Face me; null when unknown. */
  fun headPosition(): Vector3? = null

  /** Just before the player is released (its position is still good): the resume point. */
  fun willClose(s: PlayerSession) {}

  /** The VacuGlide sub-panel (null = this host has none); every screen docks it under its controls while it is the primary one. */
  val vacuGlide: VacuGlideControl?
    get() = null

  /** The wearer hid the controls ([ChromeState]); every screen's control panel is down until they bring them back. */
  val controlsHidden: Boolean
    get() = false

  /** Something touched this screen or its controls (restarts the auto-hide clock). */
  fun chromeTouched() {}

  /** The control panel's "Hide controls". */
  fun hideControls() {}

  /** A trigger on a screen while the controls are hidden brings them back. */
  fun showControls() {}
}

/**
 * One screen: a video panel (straight to the compositor via VideoSurfacePanelRegistration), a small
 * Compose control panel and a thin focus bar over the top edge. Sessions share nothing but the HTTP
 * client, so several run side by side ("multiple monitors", milestone 2).
 *
 * The source can change while playing (focus: original <-> Stash's 720p transcode, see
 * [ScreenSet]). That is double buffered: a second ExoPlayer prepares the new source without a
 * surface (so without a decoder yet) at the time the old one will have reached when it is ready;
 * when the old one gets there, the surface moves over and the old player is released. Transcodes
 * start at `start=<s>`, so their player position counts from [offsetMs]; everything outside uses
 * [positionMs] / [durationMs], which include it.
 *
 * Changing the projection rebuilds only the video panel; the ExoPlayer and its position stay.
 */
class PlayerSession(
    private val activity: AppSystemActivity,
    private val api: TroveApi,
    private val scope: CoroutineScope,
    val card: Card,
    /** The main panel's visibility, for the control panel's Library button. */
    val library: LibraryVisibility,
    /** Sync link state, shown on the controls when it matters (reconnecting, partner STOP). */
    val sync: SyncIndicator,
    /** Passthrough / darkening behind flat/3D screens, for the control panel's View row. */
    val room: Room,
    private val host: SessionHost,
    /** Quad screens: where it goes (level pose, null = in front), how wide and how tilted. */
    initialPose: Pose? = null,
    initialWidthM: Float = MultiScreen.FIRST_WIDTH_M,
    initialRollDeg: Float = 0f,
    /** Where playback starts (restored layouts). */
    private val startMs: Long = 0L,
    private val startPlaying: Boolean = true,
    /** Pitch of a quad screen (posture layouts, see [pitchDeg]). */
    initialPitchDeg: Float = 0f,
) : ScreenHandle {
  private var player: ExoPlayer = newPlayer()

  /** Player time 0 is this scene time (transcodes started with start=). */
  private var offsetMs = 0L

  /** The current source is the original file (seekable, true duration). */
  private var onOriginal = true

  var projection by mutableStateOf(card.projectionEnum)
    private set

  /** Full scene details (streams, detected projection); null until loaded. */
  var details by mutableStateOf<Scene?>(null)
    private set

  var streamUrl by mutableStateOf(api.streamUrl(card.id))
    private set

  var isPlaying by mutableStateOf(false)
    private set

  var error by mutableStateOf<String?>(null)
    private set

  /** Focused while other screens are visible: focus bar + control panel border. */
  /** Black Out: every panel hidden and the sound off, the video keeps playing (see [setBlackout]). */
  private var blackedOut = false

  var highlighted by mutableStateOf(false)
    private set

  /**
   * Whether this screen's control panel is up: only the primary (focused / lone) screen's, and not
   * while the wearer has them hidden ([ChromeRules]). The Compose side animates on it and stops
   * polling when it is false; pointer regions and rows ignore a hidden panel.
   */
  var controlsVisible by mutableStateOf(true)
    private set

  /** The panel entity stays visible this long after [controlsVisible] flips to false, for the exit animation. */
  private var controlsFading = false
  private val finishFade = Runnable {
    controlsFading = false
    controlEntity?.setComponent(Visible(controlsEntityVisible()))
  }
  private var entitiesOn = true

  var audible by mutableStateOf(true)
    private set

  /** Hidden and paused behind a 180/360 scene (ScreenHandle.setSuspended). */
  var behindSphere by mutableStateOf(false)
    private set

  /**
   * Where this scene was left last time, offered as "Resume at mm:ss" on the controls (ResumeOffer);
   * set by [PlayerManager] when the scene opens from the start, null once taken or dismissed.
   */
  var resumeOfferMs by mutableStateOf<Long?>(null)

  /** "" for the original, "720p" while on the unfocused transcode. */
  var qualityLabel by mutableStateOf("")
    private set

  /** A stream picked by hand (Stream button) that is not the original: auto quality leaves it alone. */
  private var pinned: Stream? = null

  /** The original failed to come back after a downgrade: stay on the transcode. */
  private var originalFailed = false

  private var wantOriginal = true
  private var started = false
  /** Observable so the control panel's poll loop ends when the screen closes (review B6). */
  var closed by mutableStateOf(false)
    private set
  private var surface: Surface? = null
  private var resumeAtMs = 0L
  private var resumePlaying = true

  private var pending: Pending? = null
  private var swapJob: Job? = null

  private var videoPanelId = 0
  private var videoEntity: Entity? = null
  private val controlPanelId = PanelIds.next()
  private var controlEntity: Entity? = null
  private val barPanelId = PanelIds.next()
  private var barEntity: Entity? = null

  // The VacuGlide sub-panel under the controls (two eye-buffer panels, only one is ever visible; created when first needed).
  private val vgFullPanelId = PanelIds.next()
  private val vgChipPanelId = PanelIds.next()
  private var vgFullEntity: Entity? = null
  private var vgChipEntity: Entity? = null

  /** Which VacuGlide panel this screen shows now (NONE unless the toy is there and these are the primary screen's controls). */
  var vgShown: VgPanel = VgPanel.NONE
    private set

  val vacuGlide: VacuGlideControl?
    get() = host.vacuGlide
  private val main = Handler(Looper.getMainLooper())

  /** Where a quad screen sits (level, without tilt); spheres ignore it. Kept so a projection switch keeps the screen put. */
  private var screenPose: Pose? = initialPose

  /** Quad screen width in metres; the thumbstick changes it while the screen is held (README "Moving panels"). */
  var widthM by mutableStateOf(initialWidthM.coerceIn(HoldRules.SCREEN_WIDTH_M.start, HoldRules.SCREEN_WIDTH_M.endInclusive))
    private set

  /** Width the current video panel was registered with: other widths are a Scale on top. */
  private var panelWidthM = widthM

  /** Width the focus bar was registered with. */
  private val barWidthM = widthM

  /** Width / height of the current quad. */
  private var aspect = 16f / 9f

  /** Scale of a sphere's floating controls (quad screens' controls always keep their size). */
  private var controlsScale = 1f

  /** The row open on the controls (View / Stream / Markers / Timer); hoisted here so B can close it. */
  var extra by mutableStateOf(Extra.NONE)

  /** The rows B can close on this screen's controls (none while it is hidden behind a sphere). */
  fun openRows(): List<Row> =
      if (behindSphere || closed || !controlsVisible) emptyList()
      else (when (extra) {
        Extra.NONE -> emptyList()
        Extra.VIEW -> listOf(Row(Row.Kind.VIEW, syncId))
        Extra.STREAM -> listOf(Row(Row.Kind.STREAM, syncId))
        Extra.MARKERS -> listOf(Row(Row.Kind.MARKERS, syncId))
        Extra.TIMER -> listOf(Row(Row.Kind.TIMER, syncId))
      }) + (if (vgShown == VgPanel.FULL) listOf(Row(Row.Kind.VACUGLIDE, syncId)) else emptyList())

  /** "Stick: size" etc. while held, shown on the control panel. */
  val holdHint = HoldHint()

  /** Grip + thumbstick on a quad screen: distance (the grab), width, tilt. */
  private val screenHold =
      object : HoldTarget() {
        override val entity: Entity?
          get() = videoEntity?.takeIf { !isSphere && !behindSphere && !closed }

        override val sizeRange = HoldRules.SCREEN_WIDTH_M

        override var size: Float
          get() = widthM
          set(v) = resize(v)

        override fun hint(text: String) = holdHint.show(text)

        override fun released() = host.layoutChanged()

        override val hasPitch: Boolean
          get() = true

        override fun faceHead(): Vector3? = if (host.faceMe) host.headPosition() else null
      }

  /** Grip + thumbstick on a sphere's floating controls: distance, scale, tilt. */
  private val controlsHold =
      object : HoldTarget() {
        override val entity: Entity?
          get() = controlEntity?.takeIf { isSphere && !closed }

        override val sizeRange = HoldRules.PANEL_SCALE

        override var size: Float
          get() = controlsScale
          set(v) {
            controlsScale = v
            controlEntity?.setComponent(Scale(Vector3(v)))
            anchorVg()
          }

        override fun hint(text: String) = holdHint.show(text)
      }

  /** Height of the quad in metres (width / aspect). */
  val heightM: Float
    get() = widthM / aspect

  /** Held with grip right now (the layout never moves a held screen). */
  val isHeld: Boolean
    get() = screenHold.held

  /** Tilt of a quad screen in degrees (+ = clockwise), saved with the layout. */
  val rollDeg: Float
    get() = screenHold.rollDeg

  /** Pitch of a quad screen in degrees (+ = facing up and away: overhead, tipped down at you); saved with the layout. */
  val pitchDeg: Float
    get() = screenHold.pitchDeg

  init {
    screenHold.rollDeg = HoldRules.wrap(initialRollDeg)
    screenHold.pitchDeg = initialPitchDeg.coerceIn(-85f, 85f)
  }

  private val holdSystem: HoldSystem?
    get() = activity.systemManager.tryFindSystem<HoldSystem>()

  override val isSphere: Boolean
    get() = ScreenLayout.of(projection).isSphere

  private val bigSource: Boolean
    get() = MultiScreen.needsLowRes(card.width, card.height)

  override val canDowngrade: Boolean
    get() = bigSource && pinned == null && !originalFailed

  override val heavyNow: Boolean
    get() = bigSource && onOriginal && !behindSphere && started && !closed

  // ---- lifecycle ----

  fun start() {
    started = true
    holdSystem?.register(screenHold)
    holdSystem?.register(controlsHold)
    registerControlPanel()
    registerBarPanel()
    registerVacuGlidePanels()
    buildVideoPanel()
    if (behindSphere) {
      // Opened (restored) behind a sphere: load when it comes back.
      resumeAtMs = startMs
      resumePlaying = startPlaying
      setEntitiesVisible(false)
    } else {
      loadNow(startMs, startPlaying)
    }
    scope.launch {
      try {
        val s = api.scene(card.id)
        details = s
        val p = Projection.fromId(s.projection)
        if (p != projection) setProjection(p, save = false)
      } catch (e: Exception) {
        if (e is ApiException && e.status == 401) error = "Session ended. Log in again."
        Log.w(TAG, "scene details for ${card.id} failed", e)
      }
    }
  }

  /** Another random scene in this screen's place (control panel). */
  fun nextRandom() = host.nextRandom(this)

  fun close(replacing: Boolean = false) {
    if (closed) return
    closed = true
    main.removeCallbacks(finishFade)
    host.willClose(this)
    holdSystem?.unregister(screenHold)
    holdSystem?.unregister(controlsHold)
    dropPending()
    swapJob?.cancel()
    player.clearVideoSurface()
    player.release()
    // The VacuGlide panels hang on the controls: they go first, and a press on them ends.
    if (vgShown == VgPanel.FULL) host.vacuGlide?.release("screen closed")
    vgShown = VgPanel.NONE
    vgFullEntity?.destroy()
    vgFullEntity = null
    vgChipEntity?.destroy()
    vgChipEntity = null
    controlEntity?.destroy()
    controlEntity = null
    barEntity?.destroy()
    barEntity = null
    destroyVideoPanel()
    unregisterPanel(controlPanelId)
    unregisterPanel(barPanelId)
    unregisterPanel(vgFullPanelId)
    unregisterPanel(vgChipPanelId)
    host.closed(this, replacing)
  }

  // ---- playback controls (main thread) ----

  /** Scene time in ms (includes a transcode's start offset). */
  val positionMs: Long
    get() = if (behindSphere || !started || closed) resumeAtOrStart() else offsetMs + player.currentPosition.coerceAtLeast(0)

  private fun resumeAtOrStart() = if (started) resumeAtMs else startMs

  /** Scene length in ms: the player's for the original, else the server's (transcodes don't know it). */
  val durationMs: Long
    get() {
      if (closed) return (card.duration * 1000).toLong().coerceAtLeast(0)
      val d = player.duration
      if (onOriginal && d > 0) return d
      return (card.duration * 1000).toLong().coerceAtLeast(0)
    }

  /** This screen's id on the sync channel (Trove server/sync.ts); the console's remote control names it. */
  val syncId: String = "screen-$controlPanelId"

  /**
   * The picture advances right now: not paused, buffering, seeking, ended or behind a sphere. The
   * sync server runs the toy from the reported time only while this is true.
   */
  val advancing: Boolean
    get() = started && !closed && !behindSphere && player.isPlaying

  /** Playback speed (the server only drives the toy at 1x). */
  val playbackRate: Float
    get() = player.playbackParameters.speed

  /** Playing or about to (buffering); what pause/resume and the saved layout remember. */
  val wantsToPlay: Boolean
    get() = if (behindSphere) resumePlaying else player.playWhenReady

  fun togglePlay() {
    if (behindSphere) return
    if (player.isPlaying) player.pause()
    else {
      if (player.playbackState == Player.STATE_ENDED) seekTo(0)
      player.play()
    }
  }

  fun play() {
    if (!behindSphere) player.play()
  }

  fun pause() {
    if (behindSphere) resumePlaying = false else player.pause()
  }

  fun seekBy(ms: Long) = seekTo(positionMs + ms)

  fun seekTo(ms: Long) {
    if (behindSphere) return
    val dur = durationMs
    val t = if (dur > 0) ms.coerceIn(0, dur) else ms.coerceAtLeast(0)
    if (onOriginal) {
      dropPending()
      player.seekTo(t)
    } else {
      // Transcodes restart at the new time; the old one keeps playing until the new one is ready.
      load(original = false, atMs = t, waitForOld = false)
    }
  }

  /** Switches stream by hand (Stream button), e.g. a transcode when the device can't decode the original. */
  fun selectStream(stream: Stream) {
    val direct = stream == details?.streams?.firstOrNull()
    pinned = if (direct) null else stream
    if (direct) originalFailed = false
    error = null
    if (behindSphere) return
    load(original = direct, atMs = positionMs, waitForOld = false)
  }

  /** [save] stores it on the server for everyone (null when it equals the detected one, like the web UI). */
  fun setProjection(p: Projection, save: Boolean) {
    if (closed) return
    if (p != projection) {
      val wasSphere = isSphere
      projection = p
      buildVideoPanel()
      if (behindSphere) setEntitiesVisible(false)
      if (wasSphere != isSphere) host.projectionChanged(this)
    }
    if (save) {
      val detected = details?.detectedProjection?.let(Projection::fromId)
      scope.launch {
        try {
          api.setProjection(card.id, if (p == detected) null else p)
        } catch (e: Exception) {
          error = "Could not save view: ${e.message}"
        }
      }
    }
  }

  fun requestFocus() {
    if (!closed) host.requestFocus(this)
  }

  /**
   * What a ray can hit on this screen, for the stick rule (control scheme section 2a): the video
   * (quad screens; a sphere is everywhere and needs no region) and its controls, which scroll only
   * while a chip row is open ([Target.PANEL]) and otherwise let the stick seek ([Target.CONTROLS]).
   */
  fun pointerRegions(): List<PointerRegion> {
    if (closed || behindSphere || blackedOut) return emptyList()
    val controls = if (extra != Extra.NONE) Target.PANEL else Target.CONTROLS
    if (isSphere) {
      if (!controlsVisible) return emptyList()
      val c = controlEntity?.tryGetComponent<Transform>()?.transform ?: return emptyList()
      val ctl = PointerRegion(controls, rectAt(c, Vector3(0f, 0f, 0f), CONTROLS_WIDTH_M * controlsScale / 2f, CONTROLS_HEIGHT_M * controlsScale / 2f))
      val vg = vgBox()?.let { (off, w, h) -> PointerRegion(Target.PANEL, rectAt(c, Vector3(off.x * controlsScale, off.y * controlsScale, 0f), w * controlsScale / 2f, h * controlsScale / 2f)) }
      return listOfNotNull(ctl, vg)
    }
    val v = videoEntity?.tryGetComponent<Transform>()?.transform ?: return emptyList()
    val half = widthM / aspect / 2f
    val video = PointerRegion(Target.VIDEO, rectAt(v, Vector3(0f, 0f, 0f), widthM / 2f, half))
    // A hidden control panel is not there for the ray (the stick seeks as on the video).
    if (!controlsVisible) return listOf(video)
    val ctlY = -half - CONTROLS_HEIGHT_M / 2f - CONTROLS_GAP_M
    // The VacuGlide panel under the controls: the stick scrolls / does nothing there, it must not seek.
    val vg = vgBox()?.let { (off, w, h) -> PointerRegion(Target.PANEL, rectAt(v, Vector3(off.x, ctlY + off.y, 0f), w / 2f, h / 2f)) }
    return listOfNotNull(
        video,
        PointerRegion(controls, rectAt(v, Vector3(0f, ctlY, 0f), CONTROLS_WIDTH_M / 2f, CONTROLS_HEIGHT_M / 2f)),
        vg,
    )
  }

  /** Centre of the VacuGlide panel shown now, relative to the controls' centre (unscaled), and its size; null when none is up. */
  private fun vgBox(): Triple<Vector3, Float, Float>? =
      when (vgShown) {
        VgPanel.NONE -> null
        VgPanel.FULL -> Triple(Vector3(0f, vgOffsetY(VacuGlidePanelSize.FULL_H_M), 0f), VacuGlidePanelSize.FULL_W_M, VacuGlidePanelSize.FULL_H_M)
        VgPanel.CHIP ->
            Triple(Vector3(-(CONTROLS_WIDTH_M - VacuGlidePanelSize.CHIP_W_M) / 2f, vgOffsetY(VacuGlidePanelSize.CHIP_H_M), 0f), VacuGlidePanelSize.CHIP_W_M, VacuGlidePanelSize.CHIP_H_M)
      }

  private fun vgOffsetY(h: Float) = -(CONTROLS_HEIGHT_M / 2f + VacuGlidePanelSize.GAP_M + h / 2f)

  /** World pose of a quad screen (after grabs), level: the tilt is [rollDeg]. */
  fun currentPose(): Pose? =
      videoEntity?.takeIf { !isSphere }?.getComponent<Transform>()?.transform?.let(HoldSystem::levelPose) ?: screenPose

  /**
   * Puts a quad screen at [pose] (level: its yaw) with a pitch and tilt of its own: the posture
   * layouts, "Bring screens to me" and the layout relaxation. With Face me on, yaw and pitch are
   * replaced by the direction from the head ([Facing]), the roll stays. Spheres have no pose and
   * ignore it. A held screen is left to the grab (the next call after it is let go wins).
   */
  fun placeAt(pose: Pose, pitchDeg: Float, rollDeg: Float) {
    if (isSphere || closed || screenHold.held) return
    var pose = pose
    var pitch = pitchDeg
    val head = if (host.faceMe) host.headPosition() else null
    if (head != null) {
      val f = faced(pose.t, head)
      pose = Pose(pose.t, f.first)
      pitch = f.second
    }
    screenHold.pitchDeg = pitch.coerceIn(-85f, 85f)
    screenHold.rollDeg = HoldRules.wrap(rollDeg)
    screenPose = pose
    videoEntity?.setComponent(Transform(Pose(pose.t, HoldSystem.tilted(pose.q, screenHold.pitchDeg, screenHold.rollDeg))))
  }

  /** Level yaw rotation and pitch (degrees) that face [pos] to [head]. */
  private fun faced(pos: Vector3, head: Vector3): Pair<Quaternion, Float> =
      HoldSystem.levelYaw(Facing.yawDeg(pos.x - head.x, pos.z - head.z)) to Facing.pitchDeg(pos.x - head.x, pos.y - head.y, pos.z - head.z)

  /**
   * "Bring screens to me": turns this screen about the vertical axis through [head] by [deltaDeg]
   * (a sphere's floating controls too); distance, height, pitch and tilt stay.
   */
  fun turnAbout(head: Vector3, deltaDeg: Float) {
    if (closed) return
    if (isSphere) {
      val c = controlEntity?.getComponent<Transform>()?.transform ?: return
      val np = PostureLayout.rotateAbout(Vec(head.x, head.y, head.z), Vec(c.t.x, c.t.y, c.t.z), deltaDeg)
      val yaw = HoldSystem.yawDegOf(c.q) + deltaDeg
      controlEntity?.setComponent(
          Transform(Pose(Vector3(np.x, np.y, np.z), HoldSystem.tilted(HoldSystem.levelYaw(yaw), controlsHold.pitchDeg, controlsHold.rollDeg))))
      return
    }
    val p = currentPose() ?: return
    val np = PostureLayout.rotateAbout(Vec(head.x, head.y, head.z), Vec(p.t.x, p.t.y, p.t.z), deltaDeg)
    placeAt(Pose(Vector3(np.x, np.y, np.z), HoldSystem.levelYaw(HoldSystem.yawDegOf(p.q) + deltaDeg)), pitchDeg, rollDeg)
  }

  /** New quad width: the video panel scales, the focus bar follows, the controls keep their size. */
  private fun resize(width: Float) {
    widthM = width
    if (isSphere) return
    videoEntity?.setComponent(Scale(Vector3(widthM / panelWidthM)))
    anchorChildren()
  }

  // ---- ScreenHandle (called by ScreenSet via PlayerManager) ----

  override fun applyFocus(audible: Boolean, highlighted: Boolean) {
    this.audible = audible
    this.highlighted = highlighted
    refreshChrome()
    applyVolume()
    barEntity?.setComponent(Visible(highlighted && !behindSphere && !isSphere && !blackedOut))
  }

  /** Control panel up or down for the current focus and hidden flag; the focus bar is unaffected (it is the minimal indicator). */
  fun refreshChrome() {
    val show = ChromeRules.showsControls(isPrimary = audible && !behindSphere, hidden = host.controlsHidden)
    if (show == controlsVisible) return
    controlsVisible = show
    main.removeCallbacks(finishFade)
    if (!show) {
      // Keep the panel entity up while the Compose exit animation plays, then hide it (input and drawing stop).
      controlsFading = true
      main.postDelayed(finishFade, FADE_MS)
    } else {
      controlsFading = false
    }
    controlEntity?.setComponent(Visible(controlsEntityVisible()))
    refreshVg()
  }

  private fun controlsEntityVisible() = entitiesOn && !blackedOut && (controlsVisible || controlsFading)

  /** Control panel button "Hide controls". */
  fun hideControls() = host.hideControls()

  /** Any touch on this screen's controls restarts the auto-hide clock. */
  fun chromeTouched() = host.chromeTouched()

  /** The app volume changed (stick up / down, a Volume slider). */
  fun refreshVolume() = applyVolume()

  /** App volume 0..1 (Compose state), for the controls' Volume slider. */
  val volumeLevel: Float
    get() = host.volumeLevel

  /** The controls' Volume slider: the app volume for every screen and Storm. */
  fun setVolume(level: Float) = host.setVolume(level)

  private fun applyVolume() {
    val volume = if (audible && !blackedOut) host.volumeLevel else 0f
    player.volume = volume
    pending?.player?.volume = volume
  }

  /**
   * Black Out on / off, on the main thread: hides (not destroys) the video, controls and focus bar
   * and mutes; playback is untouched. Off is the exact inverse: the volume comes from [audible]
   * again and visibility from the sphere / focus rules.
   */
  fun setBlackout(on: Boolean) {
    blackedOut = on
    applyVolume()
    setEntitiesVisible(!behindSphere)
  }

  override fun applyQuality(original: Boolean) {
    wantOriginal = original
    if (!started || behindSphere || closed || pinned != null) return
    val target = original || !canDowngrade
    if (pending?.original == target) return
    if (target == onOriginal) {
      dropPending() // focus came back before the swap: keep what is playing
      return
    }
    // A transcode takes a few seconds to start on Stash, so ask for one ahead of now; the original
    // seeks, so a short lead is enough. A paused screen swaps at once to the same frame.
    val lead = if (!player.isPlaying) 0L else if (target) MultiScreen.ORIGINAL_LEAD_MS else MultiScreen.TRANSCODE_LEAD_MS
    load(original = target, atMs = positionMs + lead, waitForOld = true)
  }

  override fun setSuspended(suspended: Boolean) {
    if (suspended == behindSphere) return
    if (suspended) {
      resumeAtMs = positionMs
      resumePlaying = if (started) player.playWhenReady else startPlaying
      dropPending()
      behindSphere = true
      // stop() frees the decoder (the sphere wants it); the media item stays for later.
      player.pause()
      player.stop()
      setEntitiesVisible(false)
    } else {
      behindSphere = false
      setEntitiesVisible(true)
      if (started && !closed) loadNow(resumeAtMs, resumePlaying)
    }
  }

  // ---- sources ----

  private data class Source(val url: String, val offsetMs: Long, val original: Boolean)

  private class Pending(
      val player: ExoPlayer,
      val source: Source,
      val targetMs: Long,
      val waitForOld: Boolean,
      val since: Long,
  ) {
    val original: Boolean
      get() = source.original
  }

  private fun sourceFor(original: Boolean, atMs: Long): Source {
    val start = MultiScreen.startOffsetMs(atMs)
    pinned?.let { s -> return Source(api.resolve(MultiScreen.withStart(s.url, atMs)), start, original = false) }
    if (original || !canDowngrade) return Source(api.streamUrl(card.id), 0L, original = true)
    return Source(api.resolve(MultiScreen.transcodePath(card.id, details?.streams, atMs)), start, original = false)
  }

  /** Straight onto the current player (start, back from suspension): nothing on screen to keep. */
  private fun loadNow(atMs: Long, play: Boolean) {
    dropPending()
    val src = sourceFor(wantOriginal, atMs)
    setSource(src)
    player.setMediaItem(MediaItem.fromUri(src.url), if (src.original) atMs else 0L)
    player.prepare()
    player.playWhenReady = play
  }

  private fun setSource(src: Source) {
    streamUrl = src.url
    offsetMs = src.offsetMs
    onOriginal = src.original
    qualityLabel = if (src.original) "" else if (pinned != null) "Transcode" else "720p"
  }

  /** Double-buffered switch to another source at [atMs] (see the class comment). */
  private fun load(original: Boolean, atMs: Long, waitForOld: Boolean) {
    dropPending()
    val src = sourceFor(original, atMs)
    val p = newPlayer()
    p.volume = player.volume
    p.setMediaItem(MediaItem.fromUri(src.url), if (src.original) atMs else 0L)
    p.playWhenReady = false
    p.prepare()
    pending = Pending(p, src, atMs, waitForOld, SystemClock.elapsedRealtime())
    if (swapJob?.isActive != true) swapJob = scope.launch { swapLoop() }
  }

  private suspend fun swapLoop() {
    while (!closed) {
      val p = pending ?: return
      val age = SystemClock.elapsedRealtime() - p.since
      val err = p.player.playerError
      if (err != null || age > SWAP_TIMEOUT_MS) {
        Log.w(TAG, "scene ${card.id}: new ${if (p.original) "original" else "transcode"} not ready, keeping the old stream", err)
        if (p.original && !onOriginal && pinned == null) {
          originalFailed = true
          error = "The original stream did not load; staying on the transcode."
        }
        dropPending()
        continue
      }
      val ready = p.player.playbackState == Player.STATE_READY
      val reached = !p.waitForOld || !player.isPlaying || positionMs >= p.targetMs - 80
      // Back to a big original: wait while the old focus still decodes its own (decoder budget).
      val budgetOk = !p.original || !bigSource || host.mayUpgrade(this) || age > UPGRADE_WAIT_MS
      if (ready && reached && budgetOk) commit(p) else delay(100)
    }
  }

  private fun commit(p: Pending) {
    val old = player
    val play = old.playWhenReady
    // The new original was slow and the old stream ran past its start: catch up (originals seek).
    val now = positionMs
    if (p.original && play && now > p.targetMs + 1000) p.player.seekTo(now)
    pending = null
    old.clearVideoSurface()
    player = p.player
    setSource(p.source)
    surface?.let { player.setVideoSurface(it) }
    player.playWhenReady = play
    isPlaying = player.isPlaying
    old.release()
  }

  private fun dropPending() {
    val p = pending ?: return
    pending = null
    p.player.release()
  }

  private fun newPlayer(): ExoPlayer {
    val p = MediaPlayers.create(activity, api.http)
    p.addListener(
        object : Player.Listener {
          override fun onIsPlayingChanged(playing: Boolean) {
            if (p === player) isPlaying = playing
          }

          override fun onPlayerError(e: PlaybackException) {
            if (p !== player) return // a pending source: the swap loop deals with it
            Log.w(TAG, "playback error for scene ${card.id}", e)
            error = e.cause?.message?.let { "${e.errorCodeName}: $it" } ?: e.errorCodeName
          }
        })
    return p
  }

  // ---- panels ----

  private fun buildVideoPanel() {
    val layout = ScreenLayout.of(projection)
    val scene = activity.scene
    if (videoEntity != null && !layout.isSphere) {
      screenPose = currentPose() ?: screenPose
    }
    destroyVideoPanel()

    val id = PanelIds.next()
    videoPanelId = id
    aspect = layout.eyeAspect(card.width, card.height)
    val width = widthM
    panelWidthM = width
    val height = width / aspect
    activity.registerPanel(
        VideoSurfacePanelRegistration(
            id,
            surfaceConsumer = { _, s ->
              surface = s
              if (!closed) player.setVideoSurface(s)
            },
            settingsCreator = {
              MediaPanelSettings(
                  shape =
                      when (layout.shape) {
                        Shape.QUAD -> QuadShapeOptions(width, height)
                        Shape.EQUIRECT_180 -> Equirect180ShapeOptions(radius = SPHERE_RADIUS_180)
                        Shape.EQUIRECT_360 -> Equirect360ShapeOptions(radius = SPHERE_RADIUS_360)
                        Shape.FISHEYE -> FisheyeShapeOptions(layout.fovDeg, FISHEYE_RADIUS)
                      },
                  display = PixelDisplayOptions(videoPixels(card.width, 3840), videoPixels(card.height, 2160)),
                  rendering =
                      MediaPanelRenderOptions(
                          stereoMode = layout.stereo.toSdk(),
                          // Spheres go behind every other layer so the UI stays usable.
                          zIndex = if (layout.isSphere) -1 else 0,
                      ),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  input = PanelInput.triggersOnly,
              )
            },
        ))

    val entity =
        if (layout.isSphere) {
          Entity.create(listOf(Panel(id), Transform(Placement.aroundHead(scene))))
        } else {
          var pose = screenPose ?: Placement.inFront(scene, MultiScreen.FIRST_DISTANCE_M, dy = 0.1f)
          if (host.faceMe) {
            host.headPosition()?.let { head ->
              val f = faced(pose.t, head)
              pose = Pose(pose.t, f.first)
              screenHold.pitchDeg = f.second.coerceIn(-85f, 85f)
            }
          }
          screenPose = pose
          // Grip on the screen moves it together with its controls and focus bar (+ thumbstick: HoldSystem).
          Entity.create(
              listOf(
                  Panel(id),
                  Transform(Pose(pose.t, HoldSystem.tilted(pose.q, screenHold.pitchDeg, screenHold.rollDeg))),
                  Grabbable(true, GrabbableType.PIVOT_Y),
              ))
        }
    videoEntity = entity
    listenForTrigger(entity)
    attachControls(layout)
  }

  private fun destroyVideoPanel() {
    val old = videoEntity ?: return
    controlEntity?.setComponent(TransformParent()) // detach before the parent goes
    barEntity?.setComponent(TransformParent())
    player.clearVideoSurface()
    surface = null
    old.destroy()
    videoEntity = null
    unregisterPanel(videoPanelId)
  }

  /**
   * Trigger on the video: focuses an unfocused screen, plays / pauses the focused one ([ScreenClick]).
   * The controls and the focus bar are separate panels, so a click on them never lands here. Video panels have no Compose content to click, so this
   * listens on the panel's scene object (every listener sees every event; returning false leaves the
   * grip to GrabbableSystem). The scene object appears a frame or so after the entity: retry briefly.
   */
  private fun listenForTrigger(entity: Entity) {
    val listener =
        object : InputListener {
          override fun onInput(
              receiver: SceneObject,
              hitInfo: HitInfo,
              sourceOfInput: Entity,
              changed: Int,
              clicked: Int,
              downTime: Long,
          ): Boolean {
            if ((changed and clicked and TRIGGERS) != 0) {
              // Grip on the clicking hand = the start of a grab, not a click.
              val grip = ((sourceOfInput.tryGetComponent<Controller>()?.buttonState ?: 0) and GRIPS) != 0
              main.post { onVideoClick(grip) }
            }
            return false
          }
        }
    scope.launch {
      repeat(50) {
        if (closed || videoEntity !== entity) return@launch
        val future = activity.systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)
        if (future != null) {
          future.thenAccept { so -> so?.addInputListener(listener) }
          return@launch
        }
        delay(50)
      }
      Log.w(TAG, "no scene object for scene ${card.id}'s video panel: trigger focus is off")
    }
  }

  private var lastClickMs = 0L

  private fun onVideoClick(gripHeld: Boolean) {
    if (closed || behindSphere || blackedOut) return
    val now = SystemClock.elapsedRealtime()
    host.chromeTouched()
    if (host.controlsHidden) {
      // Pointing at a screen and pulling the trigger brings the controls back; it is not also a play / pause.
      if (!host.isFocused(this)) requestFocus()
      lastClickMs = now
      host.showControls()
      return
    }
    when (ScreenClick.decide(host.isFocused(this), gripHeld, now - lastClickMs)) {
      ScreenClick.Action.FOCUS -> requestFocus()
      ScreenClick.Action.TOGGLE -> togglePlay()
      ScreenClick.Action.NONE -> return
    }
    lastClickMs = now
  }

  private fun registerControlPanel() {
    activity.registerPanel(
        ComposeViewPanelRegistration(
            controlPanelId,
            composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { ControlPanel(this@PlayerSession) } } },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = CONTROLS_WIDTH_M, height = CONTROLS_HEIGHT_M),
                  rendering = PanelRender.eyeBuffer,
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                  input = PanelInput.triggersOnly,
              )
            },
        ))
  }

  private fun registerVacuGlidePanels() {
    fun reg(id: Int, w: Float, h: Float, content: @androidx.compose.runtime.Composable (VacuGlideControl) -> Unit) {
      activity.registerPanel(
          ComposeViewPanelRegistration(
              id,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { host.vacuGlide?.let { content(it) } } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = w, height = h),
                    // Eye buffer, never a compositor layer: the headset has 16 and every video panel takes one.
                    rendering = PanelRender.eyeBuffer,
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                    input = PanelInput.triggersOnly,
                )
              },
          ))
    }
    reg(vgFullPanelId, VacuGlidePanelSize.FULL_W_M, VacuGlidePanelSize.FULL_H_M) { VacuGlideFullPanel(it) }
    reg(vgChipPanelId, VacuGlidePanelSize.CHIP_W_M, VacuGlidePanelSize.CHIP_H_M) { VacuGlideChip(it) }
  }

  /**
   * Shows the VacuGlide panel that belongs under this screen's controls now: only while these are the
   * primary screen's controls, the toy is available and nothing hides it ([VacuGlideControl.panelFor]).
   * The entities are made the first time they are needed. A press on the whole panel ends when it goes.
   */
  fun refreshVg() {
    val c = host.vacuGlide
    val want =
        if (c == null || closed || !entitiesOn || blackedOut || behindSphere || controlEntity == null) VgPanel.NONE
        else c.panelFor(controlsVisible)
    if (want == vgShown) return
    if (vgShown == VgPanel.FULL) c?.release("panel hidden")
    vgShown = want
    val ctl = controlEntity
    if (want != VgPanel.NONE && ctl != null) {
      if (vgFullEntity == null) vgFullEntity = Entity.create(listOf(Panel(vgFullPanelId), Transform(), Visible(false)))
      if (vgChipEntity == null) vgChipEntity = Entity.create(listOf(Panel(vgChipPanelId), Transform(), Visible(false)))
      anchorVg()
    }
    vgFullEntity?.setComponent(Visible(want == VgPanel.FULL))
    vgChipEntity?.setComponent(Visible(want == VgPanel.CHIP))
  }

  /** Both VacuGlide panels hang under the controls (a sphere's floating controls carry their scale along). */
  private fun anchorVg() {
    val ctl = controlEntity ?: return
    val s = if (isSphere) controlsScale else 1f
    fun place(e: Entity?, x: Float, y: Float) {
      e?.setComponents(listOf(TransformParent(ctl), Transform(Pose(Vector3(x * s, y * s, 0f), Quaternion())), Scale(Vector3(s))))
    }
    place(vgFullEntity, 0f, vgOffsetY(VacuGlidePanelSize.FULL_H_M))
    place(vgChipEntity, -(CONTROLS_WIDTH_M - VacuGlidePanelSize.CHIP_W_M) / 2f, vgOffsetY(VacuGlidePanelSize.CHIP_H_M))
  }

  /** The focus highlight: a thin accent bar just above the screen's top edge, shown when highlighted. */
  private fun registerBarPanel() {
    activity.registerPanel(
        ComposeViewPanelRegistration(
            barPanelId,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(FocusAccent)) }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = barWidthM, height = BAR_HEIGHT_M),
                  rendering = PanelRender.eyeBuffer,
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                  input = PanelInput.triggersOnly,
              )
            },
        ))
  }

  /**
   * Quad screens carry their controls just below them and the focus bar just above (one grab moves
   * all three). Spheres have nothing to hang them on, so the controls float in front of the user and
   * can be grabbed on their own.
   */
  private fun attachControls(layout: ScreenLayout) {
    val parent = videoEntity ?: return
    val ctl = controlEntity ?: Entity.create(listOf(Panel(controlPanelId), Transform())).also { controlEntity = it }
    val bar = barEntity ?: Entity.create(listOf(Panel(barPanelId), Transform(), Visible(false))).also { barEntity = it }
    if (layout.isSphere) {
      val p = Placement.inFront(activity.scene, 0.9f, dy = -0.4f)
      ctl.setComponents(
          listOf(
              TransformParent(),
              Transform(Pose(p.t, HoldSystem.tilted(p.q, controlsHold.rollDeg))),
              Scale(Vector3(controlsScale)),
              Grabbable(true, GrabbableType.PIVOT_Y),
          ))
      bar.setComponents(listOf(TransformParent(), Visible(false)))
    } else {
      ctl.setComponents(listOf(Grabbable(false), TransformParent(parent), Scale(Vector3(1f))))
      bar.setComponents(listOf(TransformParent(parent), Visible(highlighted && !behindSphere && !blackedOut)))
      anchorChildren()
    }
    if (blackedOut) setEntitiesVisible(false) // a rebuilt panel (projection change) stays hidden
    else if (!controlsVisible) ctl.setComponent(Visible(controlsEntityVisible()))
    anchorVg()
    refreshVg()
  }

  /**
   * Controls just below a quad screen and the focus bar just above it, for its current width. Child
   * offsets are not scaled by the parent's Scale (the SDK applies scale per object), so they are
   * recomputed on every resize; the controls keep their size so they stay readable at any width,
   * and turn with the screen when it is tilted (they are its children).
   */
  private fun anchorChildren() {
    if (isSphere) return
    val half = widthM / aspect / 2f
    controlEntity?.setComponent(
        Transform(Pose(Vector3(0f, -half - CONTROLS_HEIGHT_M / 2f - CONTROLS_GAP_M, 0f), Quaternion())))
    barEntity?.setComponents(
        listOf(
            Transform(Pose(Vector3(0f, half + BAR_HEIGHT_M / 2f + BAR_GAP_M, 0f), Quaternion())),
            Scale(Vector3(widthM / barWidthM, 1f, 1f)),
        ))
  }

  private fun setEntitiesVisible(visible: Boolean) {
    entitiesOn = visible
    videoEntity?.setComponent(Visible(visible && !blackedOut))
    controlEntity?.setComponent(Visible(controlsEntityVisible()))
    barEntity?.setComponent(Visible(visible && !blackedOut && highlighted && !isSphere))
    refreshVg()
  }

  private fun unregisterPanel(id: Int) {
    activity.panelRegistrations.remove(id)
    activity.systemManager.findSystem<PanelCreationSystem>().panelCreator.remove(id)
  }

  private fun Stereo.toSdk(): StereoMode =
      when (this) {
        Stereo.NONE -> StereoMode.None
        Stereo.LEFT_RIGHT -> StereoMode.LeftRight
        Stereo.UP_DOWN -> StereoMode.UpDown
      }

  /** Surface size = the file's size (capped at 8K); the compositor samples it directly. */
  private fun videoPixels(v: Int, fallback: Int) = if (v > 0) v.coerceAtMost(8192) else fallback

  companion object {
    private const val TAG = "PlayerSession"
    const val CONTROLS_WIDTH_M = 1.2f
    const val CONTROLS_HEIGHT_M = 0.36f
    /** Gap between a quad screen and its controls. */
    const val CONTROLS_GAP_M = 0.04f
    /** Exit animation of the control panel ([ControlPanel]); the entity is hidden after it. */
    const val FADE_MS = 200L
    const val BAR_HEIGHT_M = 0.012f
    const val BAR_GAP_M = 0.015f
    const val SPHERE_RADIUS_180 = 50f
    const val SPHERE_RADIUS_360 = 300f
    /** The fisheye dome is a mesh in the eye buffer, not a layer: keep it well inside the far plane. */
    const val FISHEYE_RADIUS = 20f
    /** A new source that isn't ready by then is dropped (the old one keeps playing). */
    const val SWAP_TIMEOUT_MS = 30_000L
    /** An upgrade waits at most this long for the old focus to give up its big decoder. */
    const val UPGRADE_WAIT_MS = 10_000L
    private val TRIGGERS = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
    private val GRIPS = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR
  }
}
