package app.trove.quest

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.SharedPrefsCookieStorage
import app.trove.quest.api.TroveApi
import app.trove.quest.data.PrefNames
import app.trove.quest.drag.CardDrag
import app.trove.quest.drag.CardDragSystem
import app.trove.quest.drag.GhostPanel
import app.trove.quest.drag.PanelRect
import app.trove.quest.drag.V3
import app.trove.quest.hold.BlackoutGripSystem
import app.trove.quest.hold.HoldHint
import app.trove.quest.hold.HoldRules
import app.trove.quest.hold.HoldSystem
import app.trove.quest.layout.LayoutSystem
import app.trove.quest.hold.HoldTarget
import app.trove.quest.input.ActionSink
import app.trove.quest.input.ButtonLegend
import app.trove.quest.input.ButtonLegendPanel
import app.trove.quest.input.Hand
import app.trove.quest.input.HandEscape
import app.trove.quest.input.HandEscapeSystem
import app.trove.quest.input.InputRouter
import app.trove.quest.input.PanelInput
import app.trove.quest.input.PanelRender
import app.trove.quest.input.PointerRegion
import app.trove.quest.input.Row
import app.trove.quest.input.RowStack
import app.trove.quest.input.Snapshot
import app.trove.quest.input.Target
import app.trove.quest.player.DirectorExecutor
import app.trove.quest.player.Extra
import app.trove.quest.player.PlayerManager
import app.trove.quest.posture.Posture
import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.Vec
import app.trove.quest.posture.WallAccess
import app.trove.quest.sync.AwayGate
import app.trove.quest.sync.BatteryReader
import app.trove.quest.sync.BlackoutState
import app.trove.quest.sync.HeadsetStatus
import app.trove.quest.sync.PersonStopEdge
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SyncClient
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.sync.VacuGlideControl
import app.trove.quest.sync.ValveKey
import app.trove.quest.sync.VgOut
import app.trove.quest.sync.VgUi
import app.trove.quest.sync.SessionTimer
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.DirectorCommand
import app.trove.quest.sync.WearerEscapeState
import app.trove.quest.ui.AppModel
import app.trove.quest.ui.PanelIme
import app.trove.quest.ui.Screen
import app.trove.quest.ui.MainPanel
import app.trove.quest.update.UpdateClient
import app.trove.quest.update.UpdateModel
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TroveActivity : AppSystemActivity() {
  private val scope = MainScope()

  // Lazy: panels are registered during super.onCreate, but their content is only built later.
  private val prefs by lazy {
    getSharedPreferences(PrefNames.PREFS, Context.MODE_PRIVATE)
  }

  private val api by lazy {
    prefs
    val cookies = getSharedPreferences(PrefNames.COOKIE_PREFS, Context.MODE_PRIVATE)
    TroveApi(
        prefs.getString(AppModel.PREF_SERVER, null) ?: TroveApi.DEFAULT_SERVER,
        PersistentCookieJar(SharedPrefsCookieStorage(cookies)),
        // Shown on the server's /pair page, e.g. "Trove/0.1.0 (Quest 3; Android 14)".
        userAgent = "Trove/${BuildConfig.VERSION_NAME} (${Build.MODEL}; Android ${Build.VERSION.RELEASE})",
    )
  }

  /** Thumbnails go through the same OkHttp client, so they carry the session cookie. */
  private val images by lazy {
    ImageLoader.Builder(this)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.http })) }
        // Coil's defaults (25 % of the heap for bitmaps) compete with the players' buffers (review B5).
        .memoryCache { MemoryCache.Builder().maxSizeBytes(IMAGE_MEMORY_CACHE_BYTES).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(IMAGE_DISK_CACHE_BYTES).build() }
        .build()
  }

  private val syncIndicator = SyncIndicator()

  /**
   * The VacuGlide sub-panel (README "VacuGlide panel"): its controls go out over the sync socket. Built before
   * [players] and [sync], which it reaches only when a button is pressed.
   */
  private val vacuGlide: VacuGlideControl by lazy {
    VacuGlideControl(
        syncIndicator,
        object : VgOut {
          override fun valve(key: ValveKey, on: Boolean) = sync.sendVgValve(key, on)

          override fun set(offsetMs: Int?, intensity: Int?, speedLimit: Int?, suction: Int?) = sync.sendVgSet(offsetMs, intensity, speedLimit, suction)

          override fun reload() = sync.sendVgReload()

          override fun script(sceneId: String, id: String) = sync.sendVgScript(sceneId, id)
        },
        clock = SystemClock::elapsedRealtime,
        initialUi = VgUi.parse(prefs.getString(PREF_VG_UI, null)),
        saveUi = { prefs.edit().putString(PREF_VG_UI, it.pref).apply() },
        blackout = { blackout.on },
        initialStickSuction = prefs.getBoolean(PREF_VG_STICK, false),
        saveStickSuction = { prefs.edit().putBoolean(PREF_VG_STICK, it).apply() },
    )
  }

  private val players: PlayerManager by lazy {
    PlayerManager(this, api, scope, prefs, onLibraryVisible = ::setLibraryVisible, sync = syncIndicator, vacuGlide = vacuGlide)
  }

  init {
    // The wall's and the controls' "Start toy" / "Resume toy" send this (the server decides; see ToyResume).
    syncIndicator.onResume = { confirm -> sync.sendResume(confirm) }
    // The Timer rows (controls, Storm panel) ask the server, which owns the session timer.
    syncIndicator.onTimer = { op -> sync.sendTimer(op).also { if (!it) showNotice("Timer: no link to the server") } }
  }

  /**
   * Funscript sync "player" (README "Funscript sync"): reports the screens and headset status to
   * the server, which drives the toy and the partner console; obeys STOP ALL and remote control.
   * Connected while logged in and the activity is started (visible).
   */
  private val sync: SyncClient by lazy {
    SyncClient(
        api, scope,
        screens = { players.gaze.decorate(players.syncScreens(), players.sessions) },
        headset = ::headsetStatus,
        onCommand = ::obey,
        clock = SystemClock::elapsedRealtime,
        blackout = { blackout.on },
        view = { players.gaze.headView() },
        // Never sit in black without the partner link: a dropped socket lifts it here (and lets go of a held valve).
        onLinkLost = {
          vacuGlide.release("link lost")
          applyBlackout(false)
        },
        onVgAck = { vacuGlide.onAck(it) },
        onTimer = ::timerMessage,
        onDirector = ::directorCommand,
        onEscapeDropped = {
          // Off the socket thread. The status that dropped it equals the previous state, so the
          // collector above does not run: release the STOP ALL room here.
          escape.dropped()
          runOnUiThread { releaseStopPassthrough() }
        },
    )
  }

  /** Director commands (partner console / "Send to headset"): opens, swaps, closes; see [DirectorExecutor]. */
  private val director by lazy {
    // Only a real stop (the partner's STOP ALL or the wearer's escape) opens sent scenes paused; the
    // "not started yet" latch every session begins with does not (user's choice: Send to headset used
    // alone shouldn't need a Resume first). The toy still waits for Resume either way.
    DirectorExecutor(players, api, scope, stopped = { sync.state.value.let { it.byPartner || it.byWearer } || escape.active })
  }

  /** The wearer's own "I pulled the escape" state: new scenes open paused until the partner resumed. */
  private val escape = WearerEscapeState()

  /** The hands-only escape's hold timer (input/HandEscape.kt); fires the same [wearerEscape] as the grips. */
  private val handEscape = HandEscape()

  /** Uptime of the last BODY_TRACKING permission check, and whether it was asked this run ([handsSeen]). */
  private var bodyTrackingCheckMs = Long.MIN_VALUE / 2
  private var bodyTrackingAsked = false

  /** Black Out (README "Black Out"): see [applyBlackout]. */
  private val blackout = BlackoutState()
  private var libraryVisible = true
  private var noticeJob: Job? = null

  private val battery by lazy { BatteryReader(this, SystemClock::elapsedRealtime) }
  private var loggedIn = false
  private var started = false
  private var resumed = false

  /** XR session VISIBLE but not FOCUSED: the Meta menu (or a system dialog) has the input. */
  private var menuOpen = false

  /** Passthrough was switched on by the partner's STOP ALL: off again when the partner resumes. */
  private var passthroughForStop = false

  /** Headset off / app paused / Meta menu: everything paused by us until back (see [updateAway]). */
  private val away = AwayGate()

  /** A STOP ALL seen in the server's status (also after a link gap): treated as `pause-all`. */
  private val personStop = PersonStopEdge()

  /** Self-update from the Trove server (update/, README "Updates"). */
  private val updates by lazy {
    UpdateModel(
        this, UpdateClient(api), prefs, scope,
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        installedVersionName = BuildConfig.VERSION_NAME,
    )
  }

  /** The main panel (login / wall). */
  private var mainEntity: Entity? = null

  /** Grip + thumbstick (README "Moving panels"); screens register their own targets. */
  private val holdSystem = HoldSystem()

  /** "Stick: size" etc. on the wall's grip pill while the wall is held. */
  private val wallHint = HoldHint()

  // ---- control scheme (input/, docs/control-scheme.md) ----

  private val rowStack = RowStack()

  /** The button legend panel (stick press; the coach card on the first launch of this version). */
  private var legendEntity: Entity? = null

  private val legend = ButtonLegend({ legendEntity }, { Placement.inFront(scene, LEGEND_DISTANCE_M, dy = LEGEND_DY_M) })

  /** The router's snapshot of the last frame, for the legend. */
  private var lastSnapshot = Snapshot()

  /** What the router can make the app do. */
  private val controlSink =
      object : ActionSink {
        override fun putBack(hand: Hand) = holdSystem.putBack(hand)

        override fun cancelDrag() {
          cardDrag.cancelRequested = true
        }

        override fun closeKeyboard() {
          PanelIme.last?.let {
            it.focusManager?.clearFocus()
            it.hide()
          }
        }

        override fun closeRow(row: Row) {
          when (row.kind) {
            Row.Kind.SORT -> model.sortOpen = false
            Row.Kind.ACCOUNT -> updates.aboutOpen = false
            Row.Kind.VACUGLIDE -> vacuGlide.collapse()
            Row.Kind.TIMER ->
                if (row.owner == Row.STORM) players.storm.timerOpen = false
                else players.sessions.firstOrNull { it.syncId == row.owner }?.extra = Extra.NONE
            else -> players.sessions.firstOrNull { it.syncId == row.owner }?.extra = Extra.NONE
          }
        }

        override fun closeScreen() {
          players.focusedSession()?.close()
        }

        override fun closeDetails() = model.closeDetails()

        override fun closePage() = model.closePage()

        override fun showLibrary() {
          if (!players.library.visible) players.library.toggle()
        }

        override fun hideLibrary() {
          if (players.library.visible) players.library.toggle()
        }

        override fun toggleLibrary() = players.library.toggle()

        override fun toggleControls() = players.toggleControls()

        override fun showControls() = players.showControls()

        override fun home() {
          for (s in players.sessions) s.extra = Extra.NONE
          players.storm.timerOpen = false
          vacuGlide.collapseIfOpen()
          model.sortOpen = false
          updates.aboutOpen = false
          model.closeDetails()
          model.closePage()
          // Hidden: shown in front of you. Already up: brought in front of you again.
          if (players.library.visible) wallAccess.replace() else players.library.toggle()
        }

        override fun togglePlay() = players.toggleFocusedPlay()

        override fun stormTogglePlay() = players.storm.holdAll(!players.storm.paused)

        override fun pauseAll() = players.pauseEverything()

        override fun toggleLegend(hand: Hand?) = legend.toggle(SystemClock.uptimeMillis(), lastSnapshot)

        override fun toggleHoldMode(hand: Hand) = holdSystem.toggleMode(hand)

        override fun seek(ms: Long) = players.seekFocused(ms)

        override fun volume(dir: Int) = players.changeVolume(dir)

        override fun stormNextClip() = players.storm.nextClip()

        override fun stormSeek(ms: Long) = players.storm.seekCentreBy(ms)

        override fun showStormPanel() = players.storm.showPanel()

        override fun exitStorm() = players.storm.close()

        override fun suctionHold(hand: Hand, tighter: Boolean) {
          if (!vacuGlide.stickPress(if (tighter) ValveKey.TIGHTER else ValveKey.LOOSER, hand.name)) return
          players.focusedSession()?.holdHint?.show(if (tighter) "Suction tighter" else "Suction looser")
        }

        override fun suctionRelease(hand: Hand) = vacuGlide.stickRelease(hand.name)
      }

  /** The app state the control scheme works from (without the pointer; the router adds that). */
  private fun controlSnapshot(): Snapshot {
    val open = ArrayList<Row>(4)
    open += players.openRows()
    open += players.storm.openRows()
    if (model.sortOpen) open += Row(Row.Kind.SORT, Row.WALL)
    if (updates.aboutOpen) open += Row(Row.Kind.ACCOUNT, Row.WALL)
    return Snapshot(
        loggedIn = model.screen == Screen.WALL,
        blackout = blackout.on,
        keyboardOpen = PanelIme.last?.editing == true,
        dragging = cardDrag.busy,
        holding = holdSystem.holdingHand,
        heldIsWall = wallHold.held,
        rows = rowStack.update(open),
        detailsOpen = model.detail != null,
        pageOpen = model.page != null,
        libraryVisible = players.library.visible,
        controlsHidden = players.chrome.hidden,
        screens = players.screensInView,
        sphere = players.sphereOpen,
        storm = players.storm.active,
        stormPanelHidden = players.storm.anyPanelHidden,
        stopped = sync.state.value.stopped,
        stickSuction = vacuGlide.stickActive,
    )
  }

  /** Everything a ray can hit that the stick rule cares about: the wall, the screens and their controls, Storm. */
  private fun pointerRegions(): List<PointerRegion> {
    val out = ArrayList<PointerRegion>(8)
    wallRect()?.let { out += PointerRegion(Target.PANEL, it) }
    out += players.pointerRegions()
    return out
  }

  /**
   * The wall's size (scale of 1.8 m) and distance are kept across launches; its tilt only for this
   * run, since the wall is put level in front of you again at every launch anyway.
   */
  private var wallScale = 1f
  private var wallDistance = MAIN_DISTANCE_M

  private val wallHold =
      object : HoldTarget() {
        override val entity: Entity?
          get() = mainEntity

        override val sizeRange = HoldRules.PANEL_SCALE

        override var size: Float
          get() = wallScale
          set(v) {
            wallScale = v
            mainEntity?.setComponent(Scale(Vector3(v)))
          }

        override fun hint(text: String) = wallHint.show(text)

        override fun released() {
          val t = mainEntity?.getComponent<Transform>()?.transform?.t ?: return
          wallDistance =
              t.distanceTo(Placement.head(scene).first).coerceIn(HoldRules.MIN_DISTANCE_M, HoldRules.MAX_DISTANCE_M)
          prefs.edit().putFloat(PREF_WALL_SCALE, wallScale).putFloat(PREF_WALL_DISTANCE, wallDistance).apply()
        }
      }

  /** Dragging a wall card into the room (README "Drag a card into the room"). */
  private val cardDrag by lazy { CardDrag(players) }

  /** The ghost that follows the ray during a drag; hidden otherwise. */
  private var ghostEntity: Entity? = null

  /** The wall panel's world rectangle for the drag's ray test; null while it is hidden. */
  private fun wallRect(): PanelRect? {
    val e = mainEntity ?: return null
    if (!libraryVisible || blackout.on) return null
    val p = e.tryGetComponent<Transform>()?.transform ?: return null
    val right = p.q.times(Vector3(1f, 0f, 0f))
    val up = p.q.times(Vector3(0f, 1f, 0f))
    val normal = p.q.times(Vector3(0f, 0f, 1f))
    return PanelRect(
        V3(p.t.x, p.t.y, p.t.z),
        V3(right.x, right.y, right.z),
        V3(up.x, up.y, up.z),
        V3(normal.x, normal.y, normal.z),
        MAIN_WIDTH_M * wallScale / 2f,
        MAIN_HEIGHT_M * wallScale / 2f,
    )
  }

  private val model by lazy {
    AppModel(
        api, prefs, scope,
        onPlay = { players.open(it) },
        onLoggedOut = { players.closeAll() },
        onSessionLost = {
          players.stopAll()
          sync.reportNow()
        },
        // The next wall page's covers into the disk cache (the grid decodes them at its own size).
        prefetchCovers = { cards ->
          for (c in cards) {
            images.enqueue(ImageRequest.Builder(this).data(api.thumbUrl(c)).memoryCachePolicy(CachePolicy.DISABLED).build())
          }
        },
    )
  }

  /**
   * SIMPLE_CONTROLLER input, as in Meta's media_view / focus showcases: trigger clicks panel UI and
   * grip (squeeze) anywhere on a Grabbable panel moves it (toolkit GrabbableSystem). The 0.14
   * default, INTERACTION_SDK, unregisters that system; its panels only move by an invisible
   * hover-only border outside the panel edge, which nobody could find.
   */
  override fun registerFeatures(): List<SpatialFeature> =
      listOf(VRFeature(this, inputSystemType = VrInputSystemType.SIMPLE_CONTROLLER), ComposeFeature())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    wallScale = prefs.getFloat(PREF_WALL_SCALE, 1f).let { if (it.isFinite()) it.coerceIn(HoldRules.PANEL_SCALE) else 1f }
    wallDistance =
        prefs.getFloat(PREF_WALL_DISTANCE, MAIN_DISTANCE_M).let {
          if (it.isFinite()) it.coerceIn(HoldRules.MIN_DISTANCE_M, HoldRules.MAX_DISTANCE_M) else MAIN_DISTANCE_M
        }
    holdSystem.debug = BuildConfig.DEBUG
    // The router first: it reads the buttons once per frame and tells HoldSystem the resize stick.
    systemManager.registerSystem(
        InputRouter(
            snapshot = ::controlSnapshot,
            regions = ::pointerRegions,
            sink = controlSink,
            onFrame = { now, snap ->
              lastSnapshot = snap
              players.storm.pointerFrame(now, snap.pointer.containsValue(Target.CENTRE))
              legend.tick(now, snap)
              // The 5 s cap of a press, also while the panel is folded into its chip (the stick still drives it).
              vacuGlide.tick()
            },
        ).also { it.debug = BuildConfig.DEBUG })
    systemManager.registerSystem(holdSystem)
    // Pushes overlapping screens apart (layout/); idle unless a screen was just moved or is held.
    systemManager.registerSystem(LayoutSystem().also { players.arrange.attach(it) })
    // The wearer's escape works in every state, so this runs every frame (not only during Black Out).
    systemManager.registerSystem(
        BlackoutGripSystem { now, both ->
          if (blackout.gripTick(now, both)) wearerEscape()
        })
    // Its hands-only twin: both open hands up in front of the face, 2 s (README "Wearer escape").
    systemManager.registerSystem(
        HandEscapeSystem(
                onFrame = { now, frame -> if (handEscape.tick(now, frame)) wearerEscape() },
                onHandsSeen = ::handsSeen,
            )
            .also { it.debug = BuildConfig.DEBUG })
    holdSystem.register(wallHold)
    players.posture.wall = wallAccess
    players.gaze.start()
    systemManager.registerSystem(
        CardDragSystem(cardDrag, wall = ::wallRect, head = { Placement.head(scene).first }, ghost = { ghostEntity }))
    players.storm.onShowInLibrary = { f ->
      model.closeDetails()
      model.addFacet(f)
      if (!players.library.visible) players.library.toggle()
    }
    model.start()
    // First launch of this version: show what the buttons do, once (docs/control-scheme.md section 6).
    scope.launch {
      snapshotFlow { model.screen == Screen.WALL }.first { it }
      delay(FIRST_RUN_DELAY_MS)
      if (!prefs.getBoolean(PREF_CONTROLS_SEEN, false)) {
        prefs.edit().putBoolean(PREF_CONTROLS_SEEN, true).apply()
        if (updates.buttonHints) legend.showCoach(SystemClock.uptimeMillis())
      }
    }
    if (BuildConfig.DEBUG) registerImeDebugReceiver()
    scope.launch {
      snapshotFlow { model.user != null }.collect {
        loggedIn = it
        updateSync()
      }
    }
    scope.launch {
      sync.state.collect { st ->
        syncIndicator.state = st.copy(notice = syncIndicator.state.notice)
        vacuGlide.onStatus()
        escape.onStatus(st.stopped, st.link == SyncLink.CONNECTED, sync.escapeQueued)
        if (personStop.rose(st)) stopForPerson()
        releaseStopPassthrough()
      }
    }
  }

  override fun onStart() {
    super.onStart()
    started = true
    updateSync()
  }

  override fun onStop() {
    started = false
    applyBlackout(false)
    updateSync()
    super.onStop()
  }

  /** Headset taken off (the proximity sensor pauses the activity), another app in front: pause everything. */
  override fun onPause() {
    resumed = false
    updateAway() // pauses everything (each screen sends its final play report) and reports at once
    players.playReports.flush()
    super.onPause()
  }

  /**
   * [AwayGate]: going away pauses every screen and Storm (what played is remembered, the Meta-menu
   * pattern); coming back resumes only that. A STOP ALL meanwhile forgets it ([PlayerManager.stopAll]).
   * Reports at once either way, so the server stops the toy on `playing: false, inOurApp: false`
   * without waiting for the next heartbeat (it stops it on `inOurApp: false` alone too).
   */
  private fun updateAway() {
    // Headset off / app paused / Meta menu: nobody is holding anything any more.
    if (!resumed || menuOpen) vacuGlide.release("away")
    when (away.update(resumed, menuOpen)) {
      true -> players.pauseAll()
      false -> players.resumeAll()
      null -> Unit
    }
    sync.reportNow()
  }

  private fun updateSync() = sync.setActive(loggedIn && started)

  private fun headsetStatus(): Map<String, Any> {
    val b = battery.read()
    return HeadsetStatus(
            inOurApp = resumed && !menuOpen,
            battery = b.percent,
            charging = b.charging,
            tempC = b.tempC,
            app = BuildConfig.VERSION_NAME,
            storm = players.storm.syncLabel(),
        )
        .toMap()
  }

  /** Commands from the sync server: STOP ALL / shutdown pause everything; the console's remote control. */
  private fun obey(c: ServerMessage.Command) {
    when (c.cmd) {
      "blackout" -> {
        val was = blackout.on
        blackout.set(c.on, c.seq)
        applyBlackout(c.on)
        // Answer at once (the console shows "confirmed by headset" and the latency from this).
        sync.sendBlackout(c.on, c.seq)
        if (was && !c.on) showNotice()
      }
      // Only a STOP ALL, not a server restart, shows the room. The session timer's end pauses
      // everything too (the server has latched the toy), without passthrough: it is no emergency.
      "pause-all" ->
          when (c.reason) {
            SyncProtocol.REASON_STOP_ALL -> stopForPerson()
            SessionTimer.REASON -> {
              players.stopAll()
              if (SessionTimer.showNotice(blackout.on)) timerNotice(SessionTimer.ENDED_NOTICE)
            }
            else -> players.stopAll()
          }
      "play", "pause", "seek" -> players.remote(c.cmd, c.screen, c.deltaMs)
    }
  }

  /**
   * A person's STOP ALL (the partner's, or the wearer's escape as the server reports it): Black Out
   * lifted (STOP ALL always shows the room), every screen and Storm paused, passthrough on. From the
   * `pause-all` command and from the rising edge in the status; running it twice changes nothing.
   */
  private fun stopForPerson() {
    vacuGlide.release("STOP ALL")
    applyBlackout(false)
    players.stopAll()
    setStopPassthrough(true)
  }

  /**
   * Black Out on the scene, all in one go on the main thread so it lands in one frame: the wall and
   * every screen hidden (not destroyed), sound off, passthrough off. Off is the inverse. [blackout]
   * and the scene are kept in step here, whoever asked (server, wearer, link loss, STOP ALL).
   */
  private fun applyBlackout(on: Boolean) {
    blackout.set(on)
    vacuGlide.onBlackout(on)
    mainEntity?.setComponent(Visible(libraryVisible && !on))
    players.setBlackout(on)
  }

  /**
   * The wearer's universal escape, both grips held 2 s in ANY state (README "Wearer escape"). Everything
   * local happens at once and does not wait for the link: Black Out lifted, every screen and Storm
   * paused, the room shown (passthrough, undimmed). Then the server is told (queued while the link is
   * down, see [SyncClient.sendWearerStop]); it latches STOPPED "by the wearer" and stops the toy. Nothing
   * is resumed here: Resume stays the partner's (or the wearer's own console's) action.
   */
  private fun wearerEscape() {
    vacuGlide.release("wearer escape")
    escape.trigger()
    applyBlackout(false)
    players.stopAll()
    setStopPassthrough(true)
    sync.sendWearerStop()
    showNotice(ESCAPE_NOTICE)
  }

  /**
   * Both hands are tracked: the hands escape needs the body tracking permission (its hand joints).
   * Asked once per run and only with no screen or Storm open, since the system dialog pauses the app
   * (and so every screen and the toy). Denied: the hands escape stays off; grips and STOP ALL still work.
   */
  private fun handsSeen() {
    if (bodyTrackingAsked) return
    val now = SystemClock.uptimeMillis()
    if (now - bodyTrackingCheckMs < BODY_TRACKING_CHECK_MS) return
    bodyTrackingCheckMs = now
    if (checkSelfPermission(BODY_TRACKING) == PackageManager.PERMISSION_GRANTED) {
      bodyTrackingAsked = true
      return
    }
    if (players.sessions.isNotEmpty() || players.storm.active) return
    bodyTrackingAsked = true
    runOnUiThread { requestPermissions(arrayOf(BODY_TRACKING), REQ_BODY_TRACKING) }
  }

  /** The session timer's warnings (5 / 1 min left) and a refused change, as a short notice; never during Black Out. */
  private fun timerMessage(m: ServerMessage) {
    if (!SessionTimer.showNotice(blackout.on)) return
    when (m) {
      is ServerMessage.TimerWarn -> timerNotice(SessionTimer.warnText(m.minutesLeft))
      is ServerMessage.TimerAck -> if (!m.ok) showNotice("Timer: ${m.error ?: "refused"}")
      else -> Unit
    }
  }

  /**
   * A timer warning / "Timer ended": in the sync tag as always, and also in the small notice panel
   * when the library and every control panel are hidden, so it is seen ([SessionTimer.noticePanel]).
   */
  private fun timerNotice(text: String) {
    showNotice(text)
    val s = controlSnapshot()
    val controlsShown = s.screens > 0 && !s.controlsHidden
    val stormPanelShown = s.storm && !players.storm.panelHidden // the main Storm panel carries the tag
    if (SessionTimer.noticePanel(blackout.on, s.libraryVisible, controlsShown, stormPanelShown)) legend.showNotice(SystemClock.uptimeMillis(), text)
  }

  /** A Director command from the server: runs on the main thread, answers with an ack. */
  private fun directorCommand(c: DirectorCommand) {
    director.run(c) { ok, error -> sync.sendAck(c.seq, ok, error) }
  }

  /** A short tag text in the sync tag for a few seconds. */
  private fun showNotice(text: String = "Black Out lifted") {
    syncIndicator.state = syncIndicator.state.copy(notice = text)
    noticeJob?.cancel()
    noticeJob =
        scope.launch {
          delay(NOTICE_MS)
          syncIndicator.state = syncIndicator.state.copy(notice = null)
        }
  }

  /**
   * Passthrough behind the (paused) screens after STOP ALL. A 180/360 scene still covers the view:
   * it is paused, not hidden.
   */
  /** The STOP ALL room goes when the server is no longer stopped and no wearer escape is pending. */
  private fun releaseStopPassthrough() {
    if (passthroughForStop && !sync.state.value.stopped && !escape.active) setStopPassthrough(false)
  }

  private fun setStopPassthrough(on: Boolean) {
    if (passthroughForStop == on) return
    passthroughForStop = on
    // Through Room, which owns passthrough: Resume restores the wearer's own View → Behind choice.
    runCatching { players.room.stopOverride = on }
  }

  /** Launch / back from the "install unknown apps" screen: throttled update check or install. */
  override fun onResume() {
    super.onResume()
    resumed = true
    updateAway()
    updates.onResume()
  }

  /**
   * Debug builds only: `adb shell am broadcast -a app.trove.quest.DEBUG_IME --es op next`
   * drives the panel's keyboard binding without moving system focus (adb taps and keys do).
   * Ops: next, prev, clear, restart, show, hide, grab.
   */
  private fun registerImeDebugReceiver() {
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(context: Context, intent: android.content.Intent) {
            val ime = app.trove.quest.ui.PanelIme.last ?: return
            val op = intent.getStringExtra("op")
            android.util.Log.d(app.trove.quest.ui.PanelIme.TAG, "debug op=$op")
            when (op) {
              "next" -> ime.focusManager?.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
              "prev" -> ime.focusManager?.moveFocus(androidx.compose.ui.focus.FocusDirection.Previous)
              "clear" -> ime.focusManager?.clearFocus()
              "restart" -> ime.restart()
              "show" -> ime.show()
              "hide" -> ime.hide()
              "grab" -> ime.grabWindowFocus()
            }
          }
        }
    registerReceiver(
        receiver,
        android.content.IntentFilter("app.trove.quest.DEBUG_IME"),
        Context.RECEIVER_EXPORTED,
    )
  }

  override fun onSceneReady() {
    super.onSceneReady()
    // LOCAL_FLOOR: y is height above the floor, and the system recenter works.
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.enablePassthrough(false)
    // VRFeature turns on the SDK's locomotion: stick left/right snap-turns the view (on release) and
    // forward teleports. A seated video viewer needs neither, and the snap turn fired on every tilt
    // with the stick while gripping a panel: the view turned ~30° and the held panel swooped after
    // the hand (traced on the Quest, 0.2.5). The thumbstick is ours alone (HoldSystem).
    systemManager.tryFindSystem<LocomotionSystem>()?.enableLocomotion(false)
    mainEntity =
        Entity.create(
            listOf(
                Panel(R.id.main_panel),
                Transform(wallPose()),
                Scale(Vector3(wallScale)),
                Grabbable(true, GrabbableType.PIVOT_Y),
                Visible(true),
            ))
    // Never hit by the ray (the pointer stays on the wall underneath), hidden until a drag starts.
    ghostEntity =
        Entity.create(
            listOf(
                Panel(R.id.ghost_panel),
                Transform(Pose(Vector3(0f, -10f, 0f))),
                Hittable(MeshCollision.NoCollision),
                Visible(false),
            ))
    // The button legend: never hit by the ray, hidden until a stick press (or the first launch).
    legendEntity =
        Entity.create(
            listOf(
                Panel(R.id.legend_panel),
                Transform(Pose(Vector3(0f, -10f, 0f))),
                Hittable(MeshCollision.NoCollision),
                Visible(false),
            ))
  }

  /**
   * Hidden while a scene plays so it doesn't block the video; when it comes back (Library button,
   * or the last screen closed) it is put in front of the user again, since they may have turned.
   */
  private fun setLibraryVisible(visible: Boolean) {
    libraryVisible = visible
    val e = mainEntity ?: return
    if (visible) {
      e.setComponent(Transform(wallPose()))
      updates.checkIfDue()
    }
    e.setComponent(Visible(visible && !blackout.on))
  }

  /**
   * In front of the user at the wall's distance, with its tilt. Sitting keeps the tilt you gave it;
   * the other postures (posture/) put it where the screens go: overhead and tipped down, or rolled.
   */
  private fun wallPose(): Pose {
    if (players.posture.current == Posture.SIT) {
      wallHold.pitchDeg = 0f
      if (wallPostured) wallHold.rollDeg = 0f // back from a side posture: level again
      wallPostured = false
      val p = Placement.inFront(scene, wallDistance, dy = -0.2f)
      return Pose(p.t, HoldSystem.tilted(p.q, wallHold.rollDeg))
    }
    val pl = players.posture.placeWall(wallDistance)
    wallPostured = true
    wallHold.pitchDeg = pl.pitchDeg
    wallHold.rollDeg = pl.rollDeg
    return Pose(Vector3(pl.pos.x, pl.pos.y, pl.pos.z), HoldSystem.tilted(HoldSystem.levelYaw(pl.yawDeg), pl.pitchDeg, pl.rollDeg))
  }

  /** The wall's pitch / tilt came from a posture, not from the user's stick. */
  private var wallPostured = false

  /** The wall, for posture layouts and "Bring screens to me". */
  private val wallAccess =
      object : WallAccess {
        override fun replace() {
          if (libraryVisible) mainEntity?.setComponent(Transform(wallPose()))
        }

        override fun position(): Vector3? = mainEntity?.takeIf { libraryVisible }?.tryGetComponent<Transform>()?.transform?.t

        override fun turnAbout(head: Vector3, deltaDeg: Float) {
          val e = mainEntity ?: return
          val p = e.tryGetComponent<Transform>()?.transform ?: return
          val np = PostureLayout.rotateAbout(Vec(head.x, head.y, head.z), Vec(p.t.x, p.t.y, p.t.z), deltaDeg)
          val yaw = HoldSystem.yawDegOf(p.q) + deltaDeg
          e.setComponent(
              Transform(Pose(Vector3(np.x, np.y, np.z), HoldSystem.tilted(HoldSystem.levelYaw(yaw), wallHold.pitchDeg, wallHold.rollDeg))))
        }
      }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          ComposeViewPanelRegistration(
              R.id.main_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { MainPanel(model, api, images, players, updates, wallHint, cardDrag, held = { wallHold.held }) } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = MAIN_WIDTH_M, height = MAIN_HEIGHT_M),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                    input = PanelInput.triggersOnly,
                )
              },
          ),
          ComposeViewPanelRegistration(
              R.id.ghost_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { GhostPanel(cardDrag, api, images) } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = GHOST_WIDTH_M, height = GHOST_HEIGHT_M),
                    rendering = PanelRender.eyeBuffer,
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                    input = PanelInput.triggersOnly,
                )
              },
          ),
          ComposeViewPanelRegistration(
              R.id.legend_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { ButtonLegendPanel(legend.model) } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = ButtonLegend.WIDTH_M, height = ButtonLegend.HEIGHT_M),
                    rendering = PanelRender.eyeBuffer,
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                    input = PanelInput.triggersOnly,
                )
              },
          ))

  /** The Meta button menu takes focus (FOCUSED -> VISIBLE): pause, and resume when it closes ([updateAway]). */
  override fun onSessionStateChanged(state: SessionState) {
    super.onSessionStateChanged(state)
    when (state) {
      SessionState.VISIBLE -> menuOpen = true
      SessionState.FOCUSED -> menuOpen = false
      else -> Unit
    }
    updateAway()
  }

  override fun onSpatialShutdown() {
    mainEntity = null // no re-placing the wall while shutting down
    ghostEntity = null
    legendEntity = null
    sync.setActive(false)
    players.closeAll()
    scope.cancel()
    super.onSpatialShutdown()
  }

  private companion object {
    const val IMAGE_MEMORY_CACHE_BYTES = 32L * 1024 * 1024
    const val IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024
    const val MAIN_WIDTH_M = 1.8f
    const val MAIN_HEIGHT_M = 1.2f
    const val MAIN_DISTANCE_M = 1.6f
    const val GHOST_WIDTH_M = 0.36f
    const val GHOST_HEIGHT_M = 0.30f
    const val NOTICE_MS = 4000L
    const val ESCAPE_NOTICE = "Stopped — you're in control"
    const val BODY_TRACKING = "com.oculus.permission.BODY_TRACKING"
    const val REQ_BODY_TRACKING = 7301
    const val BODY_TRACKING_CHECK_MS = 2000L
    const val PREF_WALL_SCALE = "wall_scale"
    const val PREF_WALL_DISTANCE = "wall_distance"

    /** Set once the first-run button legend was shown (this version's controls). */
    const val PREF_CONTROLS_SEEN = "controls_v2_seen"

    /** The VacuGlide sub-panel as the wearer left it: chip (default), full or hidden ([VgUi.pref]). */
    const val PREF_VG_UI = "vacuglide_ui"

    /** The VacuGlide panel's "Stick: Volume / Suction": true = stick up / down holds Tighter / Looser. */
    const val PREF_VG_STICK = "vacuglide_stick_suction"
    const val FIRST_RUN_DELAY_MS = 2500L
    const val LEGEND_DISTANCE_M = 0.9f
    const val LEGEND_DY_M = -0.3f
  }
}
