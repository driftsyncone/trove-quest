package app.trove.quest.player

import android.content.SharedPreferences
import app.trove.quest.api.SceneQuery
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.Placement
import app.trove.quest.gaze.GazeFocus
import app.trove.quest.hold.HoldSystem
import app.trove.quest.layout.ScreenArranger
import app.trove.quest.posture.PostureController
import app.trove.quest.api.Card
import app.trove.quest.api.TroveApi
import app.trove.quest.play.ActivityReporter
import android.util.Log
import app.trove.quest.BuildConfig
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.sync.VacuGlideControl
import app.trove.quest.sync.VacuGlideRules
import app.trove.quest.sync.VgPanel
import app.trove.quest.storm.DecoderBudget
import app.trove.quest.storm.LayerBudget
import app.trove.quest.storm.StormHost
import app.trove.quest.storm.StormManager
import app.trove.quest.sync.SyncScreen
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns every open screen (milestone 2: up to [MultiScreen.MAX_SCREENS], "multiple monitors").
 * Focus, sound, stream quality and sphere exclusivity are [ScreenSet]'s rules; this class creates
 * and places the sessions, runs the dwell timer and keeps the layout in SharedPreferences.
 */
class PlayerManager(
    private val activity: AppSystemActivity,
    private val api: TroveApi,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    /** Shows (true) or hides the main panel; see [LibraryVisibility]. */
    onLibraryVisible: (Boolean) -> Unit,
    /** Sync link state for the screens' control panels. */
    override val sync: SyncIndicator = SyncIndicator(),
    /** The VacuGlide sub-panel's state ([VacuGlideControl]); null = none (tests). */
    override val vacuGlide: VacuGlideControl? = null,
) : SessionHost, StormHost {
  /** Open screens, for Compose (same order as the screen set). */
  val sessions = mutableStateListOf<PlayerSession>()
  private val set = ScreenSet<PlayerSession>(MultiScreen.MAX_SCREENS) { SystemClock.elapsedRealtime() }
  /**
   * "Hide controls" and its auto-hide ([ChromeState]): only the focused screen shows a control panel,
   * and the wearer can hide that one too. The library coming up brings the controls back.
   */
  val chrome = ChromeState(prefs.getInt(PREF_AUTO_HIDE, 0), saveAutoHide = ::saveAutoHide, onChange = ::applyChrome)

  val library = LibraryVisibility { visible ->
    onLibraryVisible(visible)
    storm.layersChanged()
    if (visible) chrome.show(SystemClock.elapsedRealtime())
  }

  /** App volume, remembered (control scheme: stick up / down while watching). */
  val appVolume = AppVolume(prefs.getFloat(PREF_VOLUME, 1f)) { prefs.edit().putFloat(PREF_VOLUME, it).apply() }

  override val volumeLevel: Float
    get() = appVolume.level

  override val faceMe: Boolean
    get() = arrange.faceMe.enabled

  override fun headPosition(): Vector3 = Placement.head(activity.scene).first

  /** Passthrough and darkening behind flat/3D screens (control panel, View). */
  override val room = Room({ activity.scene }, prefs)

  /** Storm mode: a wall of cycling clips (storm/, README "Storm"). */
  val storm = StormManager(activity, api, scope, prefs, this)

  /** Look to focus and the console's "Looking at" (gaze/GazeFocus.kt). */
  val gaze = GazeFocus(activity, scope, prefs, sessions = { sessions }, focusOn = ::requestFocus, isFocused = ::isFocused, blocked = ::gazeBlocked, postureId = { posture.current.id })

  /** Posture layouts and "Bring screens to me" (posture/PostureController.kt). */
  val posture = PostureController(activity, prefs, sessions = { sessions }, focused = { set.focused }, storm = storm, saveLayout = { saveLayout(); arrange.kick() })

  /** Face me, no-overlap relaxation, free spots and Tidy (layout/ScreenArranger.kt). */
  val arrange: ScreenArranger = ScreenArranger(activity, prefs, sessions = { sessions }, focused = { set.focused }, posture = posture, saveLayout = ::saveLayout)

  /** Resume points per scene, local to the headset (see [ResumeStore]). */
  val resume = ResumeStore(prefs)

  /** Reports play time / position to the server (play/ActivityReporter.kt); `players.playReports.flush()` on backgrounding. */
  val playReports = ActivityReporter(scope, api::activity, api::scene, { sessions }, ::isFocused, resume)

  /** Wall header toggle: a tapped card opens in a new screen instead of replacing the focused one. */
  var openInNewScreen by mutableStateOf(false)

  /** The last saved layout, offered by the wall's "Restore last screens" while nothing is open. */
  var savedLayout by mutableStateOf(LayoutCodec.decode(prefs.getString(LayoutCodec.PREF_KEY, null)))
    private set

  val isFull: Boolean
    get() = set.isFull

  /** A card dropped into the room replaces a screen instead of adding one (6/6, or a sphere owns the view). */
  val dropReplaces: Boolean
    get() = sessions.isNotEmpty() && (set.isFull || set.activeSphere != null)

  private var qualityJob: Job? = null
  private var saveJob: Job? = null
  private var closingAll = false
  private var lastSaved: String? = null

  /** A card tapped on the wall: replaces the focused screen, or a new screen (header toggle). */
  fun open(card: Card) = open(card, newScreen = openInNewScreen)

  /** What Random draws from: the wall's current view (tab, search, facets), kept current by the wall. */
  var randomSource: SceneQuery = SceneQuery()

  /** A Random pick is loading: the buttons say so and ignore repeats. */
  var randomBusy by mutableStateOf(false)
    private set

  /**
   * Random: one scene from [randomSource] with a fresh shuffle seed. [into] replaces that screen (its
   * Next random button), never with the scene it already plays; null opens like a tapped card (the
   * wall's Open in toggle).
   */
  fun openRandom(into: PlayerSession? = null) {
    if (randomBusy) return
    randomBusy = true
    scope.launch {
      try {
        val card = pickRandom(into?.card?.id) ?: return@launch
        if (into != null && into in sessions) {
          requestFocus(into)
          open(card, newScreen = false)
        } else {
          open(card)
        }
      } catch (e: Exception) {
        android.util.Log.w("TroveRandom", "random pick failed", e)
      } finally {
        randomBusy = false
      }
    }
  }

  /** One scene from [randomSource] with a fresh shuffle seed, never [avoid]; null when the view is empty. */
  suspend fun pickRandom(avoid: String? = null): Card? {
    for (attempt in 1..3) {
      val q = randomSource.copy(sort = "random", dir = null, seed = kotlin.random.Random.nextInt(1, Int.MAX_VALUE), page = 1, perPage = 2)
      api.scenes(q).scenes.firstOrNull { it.id != avoid }?.let { return it }
    }
    return null
  }

  override fun nextRandom(s: PlayerSession) = openRandom(into = s)

  /**
   * [newScreen] adds a screen next to the others (the wall stays up, to add more); otherwise, and
   * when 6 are open or a sphere owns the view (spheres are single-scene), it replaces the focused
   * screen in place and hides the wall. [startMs]: start there (a marker, a resume point). [at]: put
   * a new flat screen exactly there (a card dropped into the room); replacing keeps the old pose.
   * [startPaused]: open it without starting (Director commands while STOPPED).
   */
  fun open(card: Card, newScreen: Boolean, startMs: Long = 0L, at: Pose? = null, startPaused: Boolean = false): PlayerSession {
    // A marker of the scene the focused screen already plays: just seek there.
    val same = set.focused?.takeIf { !newScreen && startMs > 0 && it.card.id == card.id && !it.behindSphere }
    if (same != null) {
      same.seekTo(startMs)
      if (!startPaused) same.play()
      library.sceneOpened()
      return same
    }
    val target =
        when {
          sessions.isEmpty() -> null
          !newScreen -> set.focused
          set.activeSphere != null -> set.activeSphere
          set.isFull -> set.focused
          else -> null
        }
    val hideWall = sessions.isEmpty() || target != null
    val keep = target?.takeIf { !it.isSphere }
    val keepPose = keep?.currentPose()
    val keepWidth = keep?.widthM
    val keepRoll = keep?.rollDeg ?: 0f
    val keepPitch = keep?.pitchDeg ?: 0f
    target?.close(replacing = true)
    val (pose, width) = if (keepPose != null && keepWidth != null) keepPose to keepWidth else placeNew(card, at)
    val s = create(card, pose, width, if (keepPose != null) keepRoll else 0f, startMs, playing = !startPaused, focus = true, pitchDeg = if (keepPose != null) keepPitch else 0f)
    if (startMs == 0L) {
      s.resumeOfferMs = resume.get(card.id)
      playReports.refreshOffer(s)
    }
    if (hideWall) library.sceneOpened()
    saveLayout()
    return s
  }

  /** Opens the saved layout again (the focused screen first, so it is the one on the original stream). */
  fun restoreLayout() {
    val layout = savedLayout ?: return
    if (sessions.isNotEmpty()) return
    val order = layout.screens.indices.sortedBy { if (it == layout.focus) -1 else it }
    for (i in order) {
      val sc = layout.screens[i]
      val sphere = ScreenLayout.of(sc.card.projectionEnum).isSphere
      val pose =
          if (sphere) null
          else Pose(Vector3(sc.pos[0], sc.pos[1], sc.pos[2]), Quaternion(sc.rot[0], sc.rot[1], sc.rot[2], sc.rot[3]))
      create(sc.card, pose, sc.widthM, sc.rollDeg, sc.timeMs, sc.playing, focus = i == layout.focus, pitchDeg = sc.pitchDeg)
    }
    library.sceneOpened()
  }

  private fun create(
      card: Card,
      pose: Pose?,
      width: Float,
      rollDeg: Float,
      startMs: Long,
      playing: Boolean,
      focus: Boolean,
      pitchDeg: Float = 0f,
  ): PlayerSession {
    val layout = ScreenLayout.of(card.projectionEnum)
    val fitted = if (layout.isSphere) width else MultiScreen.fitWidth(width, layout.eyeAspect(card.width, card.height))
    val s = PlayerSession(activity, api, scope, card, library, sync, room, this, pose, fitted, rollDeg, startMs, playing, pitchDeg)
    sessions += s
    chrome.show(SystemClock.elapsedRealtime()) // a new screen opens with its controls up
    if (room.blackout) s.setBlackout(true)
    set.add(s, focus) // focus, sound and the starting stream are set before anything loads
    s.start()
    updateRoom()
    scheduleQuality()
    startAutosave()
    ensureChromeTimer()
    playReports.ensureRunning()
    arrange.kick()
    return s
  }

  /**
   * The first screen goes in front as in milestone 1. Further ones are smaller and take the free
   * spot nearest to where you look (or where the card was dropped): [ScreenArranger.freeSpot].
   */
  private fun placeNew(card: Card, at: Pose? = null): Pair<Pose?, Float> {
    val quads = sessions.filter { !it.isSphere && !it.behindSphere }
    if (quads.isEmpty()) return at to MultiScreen.FIRST_WIDTH_M
    val aspect = ScreenLayout.of(card.projectionEnum).eyeAspect(card.width, card.height)
    val width = MultiScreen.fitWidth(MultiScreen.EXTRA_WIDTH_M, aspect)
    val (pos, _) = arrange.freeSpot(width, aspect, dropAt = at?.t) ?: return at to width
    val head = Placement.head(activity.scene).first
    return Pose(pos, Quaternion.lookRotationAroundY(pos - head)) to width
  }

  // ---- SessionHost ----

  override fun requestFocus(s: PlayerSession) {
    chromeTouched()
    if (s == set.focused || s !in sessions) return
    set.focus(s)
    scheduleQuality()
    saveLayout()
  }

  override fun isFocused(s: PlayerSession): Boolean = s === set.focused

  // ---- controls chrome (ChromeState) ----

  override val controlsHidden: Boolean
    get() = chrome.hidden

  override fun chromeTouched() = chrome.touch(SystemClock.elapsedRealtime())

  override fun hideControls() = chrome.hide()

  override fun showControls() = chrome.show(SystemClock.elapsedRealtime())

  /** Long Menu. Nothing without a screen (Storm has no control panels). */
  fun toggleControls() {
    if (screensInView > 0) chrome.toggle(SystemClock.elapsedRealtime())
  }

  private fun applyChrome() {
    for (s in sessions) s.refreshChrome()
  }

  private fun saveAutoHide(secs: Int) {
    prefs.edit().putInt(PREF_AUTO_HIDE, secs).apply()
    ensureChromeTimer()
  }

  private var chromeJob: Job? = null

  /** Once a second while auto-hide is on and a screen is open (nothing runs otherwise). */
  private fun ensureChromeTimer() {
    if (chromeJob?.isActive == true || chrome.autoHideSecs <= 0 || sessions.isEmpty()) return
    chromeJob =
        scope.launch {
          while (chrome.autoHideSecs > 0 && sessions.isNotEmpty()) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            if (library.visible) chrome.touch(now) else chrome.tick(now)
          }
        }
  }

  override fun projectionChanged(s: PlayerSession) {
    set.changed(s)
    arrange.kick()
    updateRoom()
    scheduleQuality()
    saveLayout()
  }

  override fun closed(s: PlayerSession, replacing: Boolean) {
    set.remove(s)
    sessions.remove(s)
    pausedByUs.remove(s)
    updateRoom()
    arrange.kick()
    if (sessions.isEmpty()) {
      if (!replacing && !storm.active) library.allClosed()
      saveJob?.cancel()
    } else {
      scheduleQuality()
      if (!closingAll) saveLayout()
    }
  }

  override fun mayUpgrade(s: PlayerSession): Boolean = set.mayUpgrade(s)

  override fun layoutChanged() {
    saveLayout()
    arrange.kick() // let go or resized: push overlapping screens apart
  }

  /** Spheres keep passthrough off; the wall on its own follows its Room toggle ([RoomView.passthroughOn]). */
  private fun updateRoom() = room.apply(screensOpen = sessions.isNotEmpty() || storm.active, sphereActive = set.activeSphere != null)

  override fun willClose(s: PlayerSession) {
    recordResume(s)
    playReports.closing(s)
  }

  private fun recordResume(s: PlayerSession) = resume.record(s.card.id, s.positionMs, s.durationMs)

  private fun scheduleQuality() {
    val due = set.qualityDueAt ?: return
    qualityJob?.cancel()
    qualityJob =
        scope.launch {
          delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
          set.applyQualityIfDue()
        }
  }

  /** Look to focus waits while the hands or the pointer are busy, the wall is up or Black Out is on. */
  private fun gazeBlocked(): Boolean =
      library.visible || room.blackout || activity.systemManager.tryFindSystem<HoldSystem>()?.holding == true

  // ---- control scheme (input/InputRouter) ----

  /** The focused screen, unless it is hidden behind a sphere. */
  fun focusedSession(): PlayerSession? = set.focused?.takeIf { !it.behindSphere }

  /** Video screens in view (those behind a sphere don't count). */
  val screensInView: Int
    get() = sessions.count { !it.behindSphere }

  val sphereOpen: Boolean
    get() = sessions.any { it.isSphere && !it.behindSphere }

  /** A / X: play or pause the focused screen. */
  fun toggleFocusedPlay() {
    chromeTouched()
    focusedSession()?.togglePlay()
  }

  /** Stick left / right: seek the focused screen, never the pointed-at one. */
  fun seekFocused(ms: Long) {
    chromeTouched()
    focusedSession()?.seekBy(ms)
  }

  /** Long A / X: every screen (also ones behind a sphere) and Storm pause. It never plays anything. */
  fun pauseEverything() {
    for (s in sessions) s.pause()
    storm.holdAll(true)
    saveLayout()
  }

  /** Stick up / down: one step louder (+1) or quieter; says the level on the focused controls and on Storm's panel. */
  fun changeVolume(dir: Int) {
    appVolume.step(dir)
    chromeTouched()
    applyAppVolume()
    val text = "Volume ${appVolume.percent} %"
    focusedSession()?.holdHint?.show(text)
    storm.volumeHint.show(text)
  }

  /** A Volume slider (screen controls, Storm panel): the slider shows the level itself, no hint. */
  override fun setVolume(level: Float) {
    val before = appVolume.level
    if (appVolume.set(level) == before) return
    chromeTouched()
    applyAppVolume()
  }

  private fun applyAppVolume() {
    for (s in sessions) s.refreshVolume()
    storm.refreshVolume()
  }

  /** What a ray can hit on the screens and on Storm (input/PointerTargets). */
  fun pointerRegions(): List<app.trove.quest.input.PointerRegion> =
      sessions.flatMap { it.pointerRegions() } + storm.pointerRegions()

  init {
    vacuGlide?.onChange = ::refreshVg
  }

  /** The VacuGlide panel follows the toy's availability, the focus and the controls: every screen decides for itself. */
  fun refreshVg() {
    for (s in sessions) s.refreshVg()
    auditVg()
  }

  /** Debug builds: logs an error when more than one VacuGlide panel is up, or one without the controls it hangs on (tag TroveVg). */
  private fun auditVg() {
    if (!BuildConfig.DEBUG || vacuGlide == null) return
    val problems =
        VacuGlideRules.auditProblems(
            full = sessions.count { it.vgShown == VgPanel.FULL },
            chip = sessions.count { it.vgShown == VgPanel.CHIP },
            controlsUp = sessions.count { it.controlsVisible },
        )
    if (problems.isNotEmpty()) Log.e("TroveVg", "INVARIANT BROKEN: ${problems.joinToString("; ")}")
  }

  /** Rows open on the screens' controls (View / Stream / Markers, the VacuGlide panel). */
  fun openRows(): List<app.trove.quest.input.Row> = sessions.flatMap { it.openRows() }

  // ---- funscript sync (sync/SyncClient) ----

  /** The open screens as the sync server wants them; the focused one carries the toy's clock. */
  fun syncScreens(): List<SyncScreen> {
    // Storm reports no scene clock: the random clips must never run the toy, so while it is on no
    // screen counts as focused (the server then holds the toy at "no focused scene").
    val focused = set.focused.takeIf { !storm.active }
    return sessions.map { s ->
      SyncScreen(
          id = s.syncId,
          sceneId = s.card.id,
          title = s.card.title,
          positionMs = s.positionMs,
          durationMs = s.durationMs.takeIf { it > 0 },
          playing = s.advancing,
          rate = s.playbackRate,
          focused = s === focused && !set.isSuspended(s),
          projection = s.projection.id,
      )
    }
  }

  /**
   * STOP ALL from the partner (or the server shutting down): every screen paused, and the ones the
   * Meta menu paused are forgotten so closing the menu doesn't start them again. Nothing blocks
   * pressing Play afterwards: the server's latch keeps the toy stopped until the partner resumes.
   */
  fun stopAll() {
    pausedByUs.clear()
    for (s in sessions) s.pause()
    playReports.flush()
    storm.pauseAll()
    saveLayout()
  }

  /** Partner remote control: [screenId] names a screen, null (or an unknown one) = the focused screen. */
  fun remote(cmd: String, screenId: String?, deltaMs: Long) {
    val s = sessions.firstOrNull { it.syncId == screenId } ?: set.focused ?: return
    when (cmd) {
      "play" -> s.play()
      "pause" -> s.pause()
      "seek" -> if (deltaMs != 0L) s.seekBy(deltaMs)
    }
  }

  /** Black Out for every open screen (the wall and passthrough are the activity's / [room]'s). */
  fun setBlackout(on: Boolean) {
    room.blackout = on
    for (s in sessions) s.setBlackout(on)
    storm.setBlackout(on)
  }

  // ---- Meta menu, logout, shutdown ----

  /** Remembers which were playing so [resumeAll] can pick them up again. */
  private val pausedByUs = mutableSetOf<PlayerSession>()

  fun pauseAll() {
    for (s in sessions) if (!s.behindSphere && s.wantsToPlay) {
      pausedByUs += s
      s.pause()
    }
    for (s in sessions) recordResume(s)
    playReports.flush()
    storm.menuOpened()
    saveLayoutNow(pausedByUs) // the menu may be the way out of the app
  }

  fun resumeAll() {
    for (s in pausedByUs) if (s in sessions) s.play()
    pausedByUs.clear()
    storm.menuClosed()
  }

  /** Saves the layout first, so closing them one by one doesn't shrink it. */
  fun closeAll() {
    storm.close()
    saveLayoutNow(pausedByUs)
    closingAll = true
    try {
      for (s in sessions.toList()) s.close()
    } finally {
      closingAll = false
    }
    pausedByUs.clear()
  }

  // ---- storm (StormHost) ----

  override fun screenLoad(): Pair<Int, Long> =
      sessions.size to
          DecoderBudget.screenBlocks(
              sessions.map { it.card.width to it.card.height }, sessions.indexOf(set.focused))

  /** Every open screen is a video layer; the library wall is one while shown (the other panels are eye-buffer panels). */
  override fun otherLayers(): Int = LayerBudget.nonTileLayers(sessions.size, library.visible)

  override fun soloScreen(card: Card, startMs: Long) {
    open(card, newScreen = true, startMs = startMs)
  }

  override fun toggleLibrary() = library.toggle()

  override fun stormChanged(active: Boolean) {
    if (active) library.sceneOpened() else if (sessions.isEmpty()) library.allClosed()
    updateRoom()
  }

  // ---- layout persistence ----

  /** While screens are open: every few seconds (times, grabbed poses), besides every change. */
  private fun startAutosave() {
    if (saveJob?.isActive == true) return
    saveJob =
        scope.launch {
          while (sessions.isNotEmpty()) {
            delay(AUTOSAVE_MS)
            saveLayout()
            for (s in sessions) recordResume(s)
          }
        }
  }

  private fun saveLayout() = saveLayoutNow(emptySet())

  /** [stillPlaying]: paused by us (Meta menu), saved as playing. Nothing open = nothing saved. */
  private fun saveLayoutNow(stillPlaying: Set<PlayerSession>) {
    if (sessions.isEmpty()) return
    val screens =
        sessions.map { s ->
          val pose = s.currentPose()
          val t = pose?.t ?: Vector3(0f, 0f, 0f)
          val q = pose?.q ?: Quaternion(1f, 0f, 0f, 0f)
          SavedScreen(
              card = s.card.copy(projection = s.projection.id),
              pos = listOf(t.x, t.y, t.z),
              rot = listOf(q.w, q.x, q.y, q.z),
              widthM = s.widthM,
              rollDeg = s.rollDeg,
              pitchDeg = s.pitchDeg,
              timeMs = s.positionMs,
              playing = s.wantsToPlay || s in stillPlaying,
          )
        }
    val layout = SavedLayout(screens, focus = sessions.indexOf(set.focused).coerceAtLeast(0))
    val text = LayoutCodec.encode(layout)
    if (text == lastSaved) return
    lastSaved = text
    prefs.edit().putString(LayoutCodec.PREF_KEY, text).apply()
    savedLayout = layout
  }

  private companion object {
    const val PREF_VOLUME = "app_volume"
    const val PREF_AUTO_HIDE = "controls_auto_hide_s"
    const val AUTOSAVE_MS = 5000L
  }
}
