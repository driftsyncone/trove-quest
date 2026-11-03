package app.trove.quest.storm

import app.trove.quest.input.Row
import app.trove.quest.sync.SyncIndicator
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import app.trove.quest.BuildConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import app.trove.quest.api.Facet
import app.trove.quest.api.Marker
import app.trove.quest.api.Scene
import com.meta.spatial.toolkit.Scale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.PanelIds
import app.trove.quest.Placement
import app.trove.quest.R
import app.trove.quest.hold.HoldHint
import app.trove.quest.input.PanelInput
import app.trove.quest.input.PanelRender
import app.trove.quest.input.PointerRegion
import app.trove.quest.input.Target
import app.trove.quest.input.rectAt
import app.trove.quest.input.rectFacing
import app.trove.quest.UI_DP_PER_METER
import app.trove.quest.api.Card
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.TroveApi
import app.trove.quest.player.Room
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import app.trove.quest.posture.turnedPose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelCreationSystem
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.core.SystemBase
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What [StormManager] needs from the screens (implemented by PlayerManager). */
interface StormHost {
  /** Passthrough / darkening, offered on the storm panel as Behind. */
  val room: Room

  /** Open screens, and what they cost the video decoder (blocks per second); see [DecoderBudget.screenBlocks]. */
  fun screenLoad(): Pair<Int, Long>

  /** Compositor layers in use apart from Storm's tiles: the open screens and the library wall while shown ([LayerBudget]). */
  fun otherLayers(): Int

  /** Solo: the scene as a regular screen, from [startMs]. */
  fun soloScreen(card: Card, startMs: Long)

  /** The panel's Library button: the wall (to pick another view for Re-storm) shows / hides. */
  fun toggleLibrary()

  /** Storm opened or closed: the library panel and the room behind follow. */
  fun stormChanged(active: Boolean)

  /** App volume 0..1 (control scheme: stick up / down); the selected tile plays at this level. */
  val volumeLevel: Float
    get() = 1f

  /** The panel's Volume slider: the app volume to [level] (0..1). */
  fun setVolume(level: Float) {}

  /** The sync state (session timer tag and row, notices); null = none (tests). */
  val sync: SyncIndicator?
    get() = null

  /** The VacuGlide's state, for the Storm panel's Toy row; null = none (tests). */
  val vacuGlide: app.trove.quest.sync.VacuGlideControl?
    get() = null
}

/**
 * Storm mode (README "Storm"): a wall of small videos, each cycling random clips from a pool built
 * from the library query. Owns the tiles and the control panel; the rules are in StormRules.kt.
 *
 * - One ExoPlayer per tile and no spare ones: a cut reuses the tile's decoder. The tile count is
 *   capped by [DecoderBudget] against the open screens and re-checked every second.
 * - Black Out hides and mutes it; STOP ALL / the Meta menu / Pause all stop the clips (and the cut
 *   timers); the partner console is told "Storm on, N tiles" and gets no scene clock from it.
 */
class StormManager(
    private val activity: AppSystemActivity,
    private val api: TroveApi,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val host: StormHost,
) {
  // ---- state for the panel ----

  var active by mutableStateOf(false)
    private set

  /** Reading the library for the pool. */
  var loading by mutableStateOf(false)
    private set

  /** Why Storm could not open, shown next to its button. */
  var error by mutableStateOf<String?>(null)
    private set

  var requestedTiles by mutableIntStateOf(prefs.getInt(PREF_TILES, Storm.DEFAULT_TILES))
    private set
  var layout by mutableStateOf(StormLayoutMode.entries.firstOrNull { it.name == prefs.getString(PREF_LAYOUT, null) } ?: StormLayoutMode.WALL)
    private set
  var clipSeconds by mutableIntStateOf(prefs.getInt(PREF_CLIP, Storm.DEFAULT_CLIP_S).coerceIn(Storm.MIN_CLIP_S, Storm.MAX_CLIP_S))
    private set
  var focusRing by mutableIntStateOf(prefs.getInt(PREF_RING, Storm.DEFAULT_FOCUS_RING).takeIf { it in Storm.FOCUS_RING_COUNTS } ?: Storm.DEFAULT_FOCUS_RING)
    private set

  /** The session timer's chip row is open on the control panel (B closes it, [Row.Kind.TIMER] owned by [Row.STORM]). */
  var timerOpen by mutableStateOf(false)

  /** The sync state for the panel's timer tag and Timer row (the host's). */
  val sync: SyncIndicator?
    get() = host.sync

  /** The VacuGlide (the host's), for the panel's Toy row. */
  val vacuGlide: app.trove.quest.sync.VacuGlideControl?
    get() = host.vacuGlide

  /** The rows B can close on the Storm panel (only while it shows). */
  fun openRows(): List<Row> = if (active && !panelHidden && !blackedOut && timerOpen) listOf(Row(Row.Kind.TIMER, Row.STORM)) else emptyList()

  /** The control panel was hidden by its Hide button (Menu / B bring it back). */
  var panelHidden by mutableStateOf(false)
    private set

  /** The last Storm start found too few usable scenes: the wall offers to storm the whole library instead. */
  var canStormAll by mutableStateOf(false)
    private set
  var sourceMode by mutableStateOf(SourceMode.entries.firstOrNull { it.name == prefs.getString(PREF_SOURCE, null) } ?: SourceMode.PREVIEWS)
    private set

  /** Tiles actually shown, and why fewer than asked for. */
  var shownTiles by mutableIntStateOf(0)
    private set
  var budgetNote by mutableStateOf<String?>(null)
    private set
  var poolSize by mutableIntStateOf(0)
    private set

  var paused by mutableStateOf(false)
    private set

  /** Sound on the selected tile (false = every tile muted). */
  var soundOn by mutableStateOf(false)
    private set

  /** The tile that has the sound / that Solo takes, and its scene title. */
  var selected by mutableStateOf<Int?>(null)
    private set
  var selectedTitle by mutableStateOf<String?>(null)
    private set

  // ---- the centre's scrub bar (Focus) ----

  /** The bar shows (a ray is on the centre, or left it less than [ScrubRules.HIDE_DELAY_MS] ago). */
  var scrubVisible by mutableStateOf(false)
    private set

  /** Scene time shown on the bar, the scene's length and its markers (refreshed while the bar shows). */
  var scrubPositionMs by mutableLongStateOf(0L)
    private set
  var scrubDurationMs by mutableLongStateOf(0L)
    private set
  var scrubMarkers by mutableStateOf<List<Marker>>(emptyList())
    private set

  /** The centre is playing (the bar's Play / Pause button). */
  var scrubPlaying by mutableStateOf(true)
    private set

  // ---- the scene-info panel beside the centre (Focus) ----

  /** Collapsed to a slim tab (remembered across launches). */
  var infoCollapsed by mutableStateOf(prefs.getBoolean(PREF_INFO_COLLAPSED, false))
    private set

  /** Removed by its Hide button until Menu / B bring it back (with the Storm controls). */
  var infoHidden by mutableStateOf(false)
    private set

  /** The centre's scene, its details once fetched (cached per scene id) and why they could not be. */
  var infoCard by mutableStateOf<Card?>(null)
    private set
  var infoScene by mutableStateOf<Scene?>(null)
    private set
  var infoError by mutableStateOf<String?>(null)
    private set

  /** A panel of Storm is hidden (Menu / B show them all again). */
  val anyPanelHidden: Boolean
    get() = panelHidden || infoHidden

  /** "Show in library" on a facet chip: the library opens filtered by it (wired by the activity). */
  var onShowInLibrary: ((Facet) -> Unit)? = null

  /** "Volume 70 %" for a moment, shown on the panel's title row when the stick changes the volume. */
  val volumeHint = HoldHint()

  // ---- internals ----

  private val rnd = Random.Default
  private var pool: StormPool? = null
  /** Poses of the layout being built; the slot factory reads them (set just before a rebuild / resize). */
  private var buildPoses: List<TilePose> = emptyList()
  private val slots = StormSlots<StormTile>(create = { slot -> newTile(slot, buildPoses[slot]) }, dispose = { it.close() })
  private val tiles: MutableList<StormTile>
    get() = slots.items

  /** Exactly one cut loop (restart cancels the previous one), one control panel registration, one pool load. */
  private val loopSlot = LoopSlot(scope)
  private val panelSlot = PanelSlot(open = ::openPanel, close = ::closePanel)
  /** The scrub bar's panel: one registration while Focus has a centre, moved with it on swaps. */
  private val scrubSlot = PanelSlot(open = ::openScrub, close = ::closeScrub)
  private val scrubHover = ScrubHover()
  /** The info panel: one registration while Focus has a centre. */
  private val infoSlot = PanelSlot(open = ::openInfo, close = ::closeInfo)
  private var infoEntity: Entity? = null
  private val infoPanelId = PanelIds.next()
  private val infoCache = SceneCache<Scene>()
  private var infoJob: Job? = null
  /** The query the running storm was built from (a facet chip adds to it). */
  private var stormQuery = SceneQuery()
  private var swapAt = 0L
  private var scrubEntity: Entity? = null
  private val scrubPanelId = PanelIds.next()
  private var markersFor: String? = null
  private var planned = 0
  private var lastTriggerAt = 0L

  /** Stash's preview layout, to map a position in a preview to scene time (defaults until the server answers). */
  private var montage = PreviewMontage.DEFAULT
  private val scheduler = StormScheduler(rnd)
  private var loadJob: Job? = null
  private var blackedOut = false
  private var menuPaused = false
  private var pausedAt = 0L
  private var controlEntity: Entity? = null
  private val controlPanelId = PanelIds.next()
  private var lastFailAt = 0L
  private var layoutHead = Vector3(0f)
  private var layoutYaw = 0.0
  private val moves = mutableMapOf<StormTile, SlotMove>()
  private var tweenSystem: StormTweenSystem? = null

  /** The centre is held with grip right now (the whole layout follows it). */
  private var grabbing = false

  /** Keep the layout's place across the next rebuild (a facet re-Storm must not snap back to the head). */
  private var keepOrigin = false

  // Wall fade (a new storm over a running one: the old tiles fade out, the new ones in).
  private var fadeFrom = 1f
  private var fadeTo = 1f
  private var fadeStart = 0L
  private var fadeMs = 1L
  private var fadeNow = 1f
  private var fadeInPending = false
  private var fadeInDeadline = 0L

  /** Compact status for the partner console ("on, 9 tiles"), null when off. */
  fun syncLabel(): String? = Storm.syncLabel(active, tiles.size)

  // ---- settings (kept across launches) ----

  fun setTiles(n: Int) {
    requestedTiles = n
    prefs.edit().putInt(PREF_TILES, n).apply()
    if (active) {
      applyPlan(rebuild = false)
      audit("tiles")
    }
  }

  fun pickFocusRing(n: Int) {
    focusRing = n
    prefs.edit().putInt(PREF_RING, n).apply()
    if (active) {
      applyPlan(rebuild = false)
      audit("ring")
    }
  }

  fun pickLayout(l: StormLayoutMode) {
    if (l == layout) return
    layout = l
    prefs.edit().putString(PREF_LAYOUT, l.name).apply()
    if (active) {
      applyPlan(rebuild = true) // tile sizes and the centre differ
      audit("layout")
    }
  }

  /** While the slider moves: only the shown number changes. */
  fun previewClip(seconds: Int) {
    clipSeconds = seconds.coerceIn(Storm.MIN_CLIP_S, Storm.MAX_CLIP_S)
  }

  /** The slider was let go (or a value was picked): remembered, and the cut timing follows. */
  fun pickClip(seconds: Int) {
    clipSeconds = seconds.coerceIn(Storm.MIN_CLIP_S, Storm.MAX_CLIP_S)
    prefs.edit().putInt(PREF_CLIP, clipSeconds).apply()
    if (active) scheduler.reset(tiles.size, now(), clipMs()) // tiles keep playing; new timing
  }

  fun setSource(m: SourceMode) {
    sourceMode = m
    prefs.edit().putString(PREF_SOURCE, m.name).apply()
    if (active) {
      applyPlan(rebuild = false) // the per-tile cost changes with the source
      shuffleNow() // so the new source shows
      audit("source")
    }
  }

  // ---- hiding the panel ----

  fun hidePanel() {
    panelHidden = true
    updatePanelVisible()
  }

  fun showPanel() {
    panelHidden = false
    infoHidden = false
    updatePanelVisible()
    applyInfoVisible()
  }

  private fun updatePanelVisible() {
    controlEntity?.setComponent(Visible(!blackedOut && !panelHidden))
  }

  // ---- open / close ----

  /** Builds the pool from [query] (its tab, search, facets and sort; not its page) and opens the wall. */
  fun start(query: SceneQuery) = startWith(query, keepPanels = false)

  /** A facet chip in the info panel: Storm again with [f] added, staying in Storm (panels stay as they are). */
  fun restormWith(f: Facet) {
    if (!active || loading) return
    keepOrigin = layout == StormLayoutMode.FOCUS
    startWith(StormInfo.restormQuery(stormQuery, f), keepPanels = true)
  }

  /** The info panel's small "Library" action. */
  fun showInLibrary(f: Facet) {
    onShowInLibrary?.invoke(f)
  }

  private fun startWith(query: SceneQuery, keepPanels: Boolean) {
    if (loading) return
    loading = true
    error = null
    canStormAll = false
    loadJob?.cancel()
    loadJob =
        scope.launch {
          try {
            refreshMontage()
            val cards = loadPool(query)
            val p = StormPool(cards, rnd)
            Storm.poolProblem(p.size, cards.distinctBy { it.id }.size)?.let { problem ->
              error = problem
              canStormAll = true
              if (p.size == 0) return@launch
            }
            val crossfade = active && tiles.isNotEmpty() && !blackedOut
            if (!crossfade) resetFade()
            if (crossfade) {
              fadeTo(0f, FADE_OUT_MS)
              delay(FADE_OUT_MS + 40)
            }
            if (active) closeTiles()
            panelSlot.ensureOpen()
            pool = p
            stormQuery = query
            poolSize = p.size
            active = true
            paused = false
            selected = null
            selectedTitle = null
            if (!keepPanels) {
              panelHidden = false
              infoHidden = false
            }
            host.stormChanged(true)
            if (crossfade) {
              // New tiles start clear and come in on the centre's first frame (or after a moment).
              fadeNow = 0f
              fadeFrom = 0f
              fadeTo = 0f
              fadeInPending = true
              fadeInDeadline = now() + FADE_IN_WAIT_MS
            }
            applyPlan(rebuild = true)
            if (crossfade) tiles.forEach { it.setFade(0f) }
            loopSlot.ensure { loop() }
            audit("start")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            error = "Storm: ${e.message ?: e}"
          } finally {
            keepOrigin = false
            loading = false
          }
        }
  }

  /** The wall's alpha heads for [to] over [ms]; [tickFade] steps it. */
  private fun fadeTo(to: Float, ms: Long) {
    fadeFrom = fadeNow
    fadeTo = to
    fadeStart = now()
    fadeMs = ms.coerceAtLeast(1L)
    ensureTween()
  }

  private fun resetFade() {
    fadeNow = 1f
    fadeFrom = 1f
    fadeTo = 1f
    fadeInPending = false
  }

  private fun startFadeIn() {
    if (!fadeInPending) return
    fadeInPending = false
    fadeTo(1f, FADE_IN_MS)
  }

  private fun tickFade() {
    if (fadeInPending && now() > fadeInDeadline) startFadeIn()
    if (fadeNow == fadeTo) return
    val f = ((now() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
    fadeNow = fadeFrom + (fadeTo - fadeFrom) * f * f * (3f - 2f * f) // smoothstep
    if (f >= 1f) fadeNow = fadeTo
    tiles.forEach { it.setFade(fadeNow) }
  }

  private fun ensureTween() {
    if (tweenSystem == null) {
      tweenSystem = StormTweenSystem { onFrame() }.also { activity.systemManager.registerSystem(it) }
    }
  }

  /** The preview montage settings from the server; Stash's defaults when it can't say. */
  private suspend fun refreshMontage() {
    montage =
        try {
          PreviewMontage.from(api.previewConfig())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "preview-config unavailable, using Stash's defaults: ${e.message}")
          PreviewMontage.DEFAULT
        }
  }

  private suspend fun loadPool(query: SceneQuery): List<Card> {
    val base = query.copy(perPage = Storm.POOL_PAGE_SIZE, page = 1)
    val first = api.scenes(base)
    val pages = Storm.poolPages(first.count, rnd)
    val cards = mutableListOf<Card>()
    for (p in pages) cards += if (p == 1) first.scenes else api.scenes(base.copy(page = p)).scenes
    return cards
  }

  /** The wall's "Storm the whole library" action (too few usable scenes in the view). */
  fun startAll() = start(SceneQuery())

  fun close() {
    timerOpen = false
    loadJob?.cancel()
    loopSlot.stop()
    resetFade()
    grabbing = false
    // Idempotent and unconditional: whatever is left of a half-started or failed storm goes too.
    closeTiles()
    panelSlot.ensureClosed()
    scrubSlot.ensureClosed()
    infoSlot.ensureClosed()
    infoCard = null
    infoScene = null
    infoError = null
    infoJob?.cancel()
    scrubHover.reset()
    scrubVisible = false
    planned = 0
    if (!active) {
      audit("close(off)")
      return
    }
    active = false
    paused = false
    selected = null
    selectedTitle = null
    shownTiles = 0
    pool = null
    panelHidden = false
    infoHidden = false
    host.stormChanged(false)
    audit("close")
  }

  // ---- safety ----

  /** Black Out: tiles hidden and muted (they keep playing, like screens); the panel hides too. */
  fun setBlackout(on: Boolean) {
    blackedOut = on
    tiles.forEach { it.setVisible(!on) }
    updatePanelVisible()
    applyScrubVisible()
    applyInfoVisible()
    applyVolumes()
  }

  /** STOP ALL (partner) and Pause all: tiles stop and nothing cuts until Play. */
  fun pauseAll() {
    menuPaused = false // a STOP ALL during the menu must outlive closing it
    holdAll(true)
  }

  /** The Meta menu opened: pause, unless already paused by hand. */
  fun menuOpened() {
    if (active && !paused) {
      menuPaused = true
      holdAll(true)
    }
  }

  fun menuClosed() {
    if (menuPaused) {
      menuPaused = false
      holdAll(false)
    }
  }

  fun holdAll(p: Boolean) {
    if (!active || paused == p) return
    paused = p
    if (!p) menuPaused = false
    if (p) pausedAt = now() else scheduler.postpone(now() - pausedAt)
    tiles.forEach { it.setPlaying(!p) }
  }

  // ---- controls ----

  fun toggleLibrary() = host.toggleLibrary()

  /** Every tile but the selected one cuts to a new clip, one after another (the cut gap rule paces them). */
  fun shuffleNow() {
    if (!active) return
    if (paused) holdAll(false)
    scheduler.shuffleNow(now())
  }

  fun setSound(on: Boolean) {
    soundOn = on
    if (on && selected == null && tiles.isNotEmpty()) select(0)
    applyVolumes()
  }

  /**
   * Trigger on a tile only selects it (sound, a bit bigger), or in Focus swaps it into the centre.
   * It never opens the video: a second trigger on the selected tile / the centre does nothing, Solo
   * is the panel's button. The SDK can deliver one click twice, so triggers closer than
   * [TRIGGER_GAP_MS] are dropped.
   */
  private fun tileClicked(t: StormTile) {
    if (blackedOut) return
    val n = now()
    if (!Storm.triggerAccepted(lastTriggerAt, n)) return
    lastTriggerAt = n
    if (t.index !in tiles.indices || tiles[t.index] !== t) return // a tile from before a rebuild
    if (layout == StormLayoutMode.FOCUS) {
      if (t.index != 0) swapWithCentre(t)
      return
    }
    if (selected != t.index) {
      soundOn = true
      select(t.index)
    }
  }

  /** The tile that does not shuffle: the Focus centre, else the selected one. */
  private fun stillTile(): Int? = if (layout == StormLayoutMode.FOCUS) 0 else selected

  private fun select(i: Int?) {
    val before = selected
    if (before != null && before != i && before in tiles.indices) scheduler.rearm(before, now(), clipMs()) // let go: a fresh clip away from its next cut
    selected = i
    selectedTitle = i?.let { tiles.getOrNull(it)?.card?.title }
    tiles.forEach { it.setSelected(it.index == i) }
    applyVolumes()
  }

  /** The selected tile's scene opens as a regular screen from where the clip is; the wall keeps going. */
  fun solo() {
    val t = selected?.let { tiles.getOrNull(it) } ?: return
    val card = t.card ?: return
    val start = t.sceneTimeMs(montage)
    host.soloScreen(card, start)
    applyPlan(rebuild = false) // the new screen takes a decoder: fewer tiles if it must
    audit("solo")
  }

  private fun applyVolumes() {
    for (t in tiles) t.setVolume(if (soundOn && !blackedOut && t.index == selected) host.volumeLevel else 0f)
  }

  /** The app volume changed (stick up / down, a Volume slider). */
  fun refreshVolume() = applyVolumes()

  /** App volume 0..1, for the panel's Volume slider. */
  val volumeLevel: Float
    get() = host.volumeLevel

  /** The panel's Volume slider. */
  fun setVolume(level: Float) = host.setVolume(level)

  /**
   * Stick right (control scheme): the selected tile cuts to its next clip, else every tile shuffles.
   * Does nothing while paused: only a pause can be set from a button, never a resume.
   */
  fun nextClip() {
    if (!active || paused || blackedOut) return
    val i = selected
    // The Focus centre never cuts (stick right off the centre shuffles the ring instead).
    if (i != null && i < tiles.size && layout != StormLayoutMode.FOCUS) cut(i) else shuffleNow()
  }

  /** What the stick can hit: the tiles (cut to the next clip) and the control panel. */
  fun pointerRegions(): List<PointerRegion> {
    if (!active || blackedOut) return emptyList()
    val out = tiles.mapNotNull { t -> t.pointerRegion(if (layout == StormLayoutMode.FOCUS && t.index == 0) Target.CENTRE else Target.TILE, layoutHead) }.toMutableList()
    val c = if (panelHidden) null else controlEntity?.tryGetComponent<Transform>()?.transform
    if (c != null) out += PointerRegion(Target.CONTROLS, rectAt(c, Vector3(0f, 0f, 0f), PANEL_W_M / 2f, PANEL_H_M / 2f))
    if (infoShown()) {
      // The panel scrolls with the stick (the SDK's), so it is a PANEL target: the stick must not seek the centre from here.
      val ip = StormLayout.infoPose(infoCollapsed)
      out += PointerRegion(Target.PANEL, rectFacing(slotPose(ip).t, layoutHead, ip.widthM / 2f, ip.heightM / 2f))
    }
    return out
  }

  // ---- tiles ----

  private fun now() = SystemClock.elapsedRealtime()

  private fun clipMs() = Storm.clipMs(clipSeconds)

  /**
   * Re-plans the tile count against the decoder budget. A changed count only closes the last
   * slots or opens new ones and re-lays the rest ([resize]); only a new storm / layout [rebuild]s.
   * The budget is the hard cap: never more than 16 decoder instances with the screens (README "Storm").
   */
  private fun applyPlan(rebuild: Boolean) {
    val (screens, screenBlocks0) = host.screenLoad()
    val perTile = DecoderBudget.tileBlocks(sourceMode)
    // The Focus centre plays its original: keep its extra cost free, so a re-plan never opens a ring tile it needs the room of.
    val screenBlocks = screenBlocks0 + (centre()?.card?.let { DecoderBudget.centreReserve(it.width, it.height, perTile) } ?: 0L)
    val requested = Storm.requestedTotal(layout, requestedTiles, focusRing)
    val plan = DecoderBudget.plan(requested, screens, screenBlocks, perTile, LayerBudget.tileRoom(host.otherLayers()))
    planned = plan.tiles
    budgetNote = plan.note()
    if (rebuild) rebuildTiles(plan.tiles) else if (plan.tiles != tiles.size) resize(plan.tiles)
  }

  /** The library wall or a screen appeared / went (a layer more or less): re-plan the tiles now, not at the next tick. */
  fun layersChanged() {
    if (active) applyPlan(rebuild = false)
  }

  private fun slotPose(tp: TilePose): Pose {
    val a = layoutYaw + Math.toRadians(tp.yawDeg.toDouble())
    val pos = layoutHead + Vector3(sin(a).toFloat(), 0f, cos(a).toFloat()) * tp.horizM + Vector3(0f, tp.dyM, 0f)
    return Pose(pos, Quaternion.lookRotationAroundY(pos - layoutHead))
  }

  private fun newTile(slot: Int, tp: TilePose): StormTile {
    val tile =
        StormTile(
            activity, api, scope, slot, slotPose(tp), tp.widthM, tp.heightM,
            bumpSelected = layout != StormLayoutMode.FOCUS,
            onClick = ::tileClicked,
            onFailed = ::tileFailed,
            onOriginalReady = { t, ms -> if (t.index == 0 && layout == StormLayoutMode.FOCUS) audit("centre original ready after $ms ms") },
            previewLengthMs = { c -> montage.previewLengthMs(c.duration) },
            onFirstFrame = { t -> if (t.index == 0) startFadeIn() },
        )
    tile.setVisible(!blackedOut)
    return tile
  }

  private fun rebuildTiles(count: Int) {
    closeTiles() // the old tiles are gone before any replacement exists
    val p = pool ?: return
    if (!keepOrigin || layout != StormLayoutMode.FOCUS) {
      val (head, fwd) = Placement.head(activity.scene)
      layoutHead = head
      layoutYaw = Math.atan2(fwd.x.toDouble(), fwd.z.toDouble())
    }
    buildPoses = StormLayout.poses(count, layout)
    slots.rebuild(buildPoses.size)
    for (tile in tiles.toList()) {
      val card = p.next(tiles.mapNotNull { it.card?.id }.toSet())
      if (card == null) { // the pool ran dry: no blank tiles, the rest of the slots close
        slots.resize(tile.index)
        break
      }
      startClip(tile, card)
    }
    shownTiles = tiles.size
    scheduler.reset(tiles.size, now(), clipMs())
    if (layout == StormLayoutMode.FOCUS && tiles.isNotEmpty()) {
      soundOn = true
      select(0)
      upgradeCentre()
    } else if (selected != null && selected!! >= tiles.size) select(null) else select(selected)
    applyVolumes()
    syncGrabbable()
    syncScrubPanel()
    if (!keepOrigin) placePanelBelow()
  }

  /** Only the Focus centre can be gripped to move the layout. */
  private fun syncGrabbable() {
    for ((i, t) in tiles.withIndex()) t.setGrabbable(layout == StormLayoutMode.FOCUS && i == 0)
    ensureTween()
  }

  /** B7: closes the last slots or opens new ones and re-lays the survivors in place. No other tile restarts. */
  private fun resize(count: Int) {
    val p = pool ?: return
    val old = tiles.size
    val change = Storm.resize(old, count)
    if (change.unchanged) return
    val poses = StormLayout.poses(count, layout)
    buildPoses = poses
    for (i in change.remove) moves.remove(tiles[i])
    slots.resize(count)
    for ((i, t) in tiles.withIndex()) {
      if (i >= old) break // new slots are placed by their factory
      moves.remove(t)
      t.place(slotPose(poses[i]), poses[i].widthM / t.baseWidthM)
    }
    for (i in change.add) {
      val tile = tiles[i]
      p.next(tiles.mapNotNull { it.card?.id }.toSet(), null)?.let { startClip(tile, it) }
    }
    shownTiles = tiles.size
    scheduler.resize(tiles.size, now(), clipMs())
    if (selected != null && selected!! >= tiles.size) select(null) else select(selected)
    applyVolumes()
    syncGrabbable()
    syncScrubPanel()
  }

  private fun startClip(tile: StormTile, card: Card) {
    val kind = Storm.kindFor(card, sourceMode)
    val start = if (kind == ClipKind.SEEK) Storm.clipStartMs(card.duration, clipMs(), rnd) else 0L
    tile.play(card, kind, start, playing = !paused)
    if (selected == tile.index) selectedTitle = card.title
  }

  private fun cut(i: Int) {
    val tile = tiles.getOrNull(i) ?: return
    val inUse = tiles.filter { it !== tile }.mapNotNull { it.card?.id }.toSet()
    val card = pool?.next(inUse, tile.card?.let { Storm.stereoFor(it) }) ?: return
    startClip(tile, card)
    scheduler.switched(i, now(), clipMs())
  }

  // ---- Focus: the centre plays its original, a trigger on a ring tile swaps ----

  /**
   * A tile that becomes the centre plays its scene's original (sound; previews are silent) at once, from
   * the preview's spot in the scene (Stash's montage jumps between segments, so the centre never stays on
   * it). Without decoder room the last ring tiles close to make it ([CentreUpgrade]); with none to be had
   * the preview is paused on its frame instead of left jumping.
   */
  private fun upgradeCentre() {
    if (layout != StormLayoutMode.FOCUS) return
    val t = tiles.getOrNull(0) ?: return
    val card = t.card ?: return
    val (_, screenBlocks) = host.screenLoad()
    val d = CentreUpgrade.decide(t.clipKind, card.width, card.height, tiles.size, screenBlocks, DecoderBudget.tileBlocks(sourceMode))
    when (d.step) {
      CentreUpgrade.Step.KEEP -> return
      CentreUpgrade.Step.FREEZE_PREVIEW -> {
        t.setPlaying(false)
        Log.w(TAG, "centre ${card.id}: no decoder room even without the ring, preview frozen")
        return
      }
      CentreUpgrade.Step.DROP_RING_THEN_UPGRADE -> {
        val keep = tiles.size - d.dropRing
        Log.d(TAG, "centre ${card.id}: closing ${d.dropRing} ring tile(s) for its original")
        resize(keep)
        planned = tiles.size
      }
      CentreUpgrade.Step.UPGRADE -> {}
    }
    val start = t.sceneTimeMs(montage) // the preview's spot in the scene, exactly (Stash's segment layout)
    t.play(card, ClipKind.SEEK, start, playing = !paused)
    if (BuildConfig.DEBUG) Log.d(TAG, "centre ${card.id}: original requested at ${start}ms, ${if (swapAt > 0) now() - swapAt else 0}ms after the swap")
  }

  private fun centreFits(card: Card): Boolean {
    val (_, screenBlocks) = host.screenLoad()
    return DecoderBudget.centreFits(card.width, card.height, tiles.size, screenBlocks, DecoderBudget.tileBlocks(sourceMode))
  }

  /** A centre pushed out to the ring goes back to its tiny preview and rejoins the shuffle. */
  private fun downgrade(t: StormTile) {
    val card = t.card ?: return
    if (t.clipKind == ClipKind.SEEK) t.play(card, ClipKind.PREVIEW, 0L, playing = !paused)
    scheduler.rearm(t.index, now(), clipMs())
  }

  private fun swapWithCentre(t: StormTile) {
    val k = t.index
    if (layout != StormLayoutMode.FOCUS || k <= 0 || k >= tiles.size || moves.isNotEmpty()) return
    val old = tiles[0]
    swapAt = now()
    val poses = StormLayout.poses(tiles.size, layout)
    tiles[0] = t
    tiles[k] = old
    t.moveToSlot(0)
    old.moveToSlot(k)
    syncGrabbable()
    val t0 = now()
    startMove(t, poses[0], t0, +SlotMove.SWAP_BULGE_M)
    startMove(old, poses[k], t0, -SlotMove.SWAP_BULGE_M)
    applyScrubVisible() // hidden while the tiles glide
    soundOn = true
    select(0)
    downgrade(old)
    upgradeCentre()
    refreshInfo() // the panel follows the new centre
    audit("swap")
  }

  private fun startMove(t: StormTile, tp: TilePose, t0: Long, bulge: Float) {
    val from = t.currentPose().t
    val to = slotPose(tp).t
    moves[t] =
        SlotMove(
            from.x, from.y, from.z, to.x, to.y, to.z,
            t.layoutScale(), tp.widthM / t.baseWidthM,
            t0, SlotMove.SWAP_MS, bulge)
    ensureTween()
  }

  private fun onFrame() {
    tickMoves()
    tickGrab()
    tickFade()
  }

  /**
   * Grip on the Focus centre moves the whole layout: the SDK's grab (pivot about the head) moves the centre; here
   * the layout's origin and yaw are re-derived from where it is, and the ring, info and scrub panels re-laid on it.
   * Letting go snaps the centre onto its slot (it faces the layout, not the head).
   */
  private fun tickGrab() {
    val c = tiles.getOrNull(0)
    val held = active && layout == StormLayoutMode.FOCUS && moves.isEmpty() && c != null && c.isGrabbed()
    if (!held) {
      if (grabbing) {
        grabbing = false
        val poses = StormLayout.poses(tiles.size, layout)
        if (c != null && poses.isNotEmpty()) c.place(slotPose(poses[0]), poses[0].widthM / c.baseWidthM)
        placeScrub()
        applyScrubVisible()
      }
      return
    }
    val cp = c!!.adoptHeldPose() ?: return
    grabbing = true
    val poses = StormLayout.poses(tiles.size, layout)
    val p0 = poses.firstOrNull() ?: return
    val (head, _) = Placement.head(activity.scene)
    val yaw = atan2((cp.t.x - head.x).toDouble(), (cp.t.z - head.z).toDouble())
    layoutYaw = yaw - Math.toRadians(p0.yawDeg.toDouble())
    val a = layoutYaw + Math.toRadians(p0.yawDeg.toDouble())
    layoutHead = Vector3(cp.t.x - sin(a).toFloat() * p0.horizM, cp.t.y - p0.dyM, cp.t.z - cos(a).toFloat() * p0.horizM)
    for ((i, t) in tiles.withIndex()) {
      if (i == 0) continue
      t.place(slotPose(poses[i]), poses[i].widthM / t.baseWidthM)
    }
    scrubEntity?.setComponent(Visible(false))
    placeScrub()
    placeInfo()
  }

  /** Per rendered frame: advances the swap glides. */
  private fun tickMoves() {
    if (moves.isEmpty()) return
    val n = now()
    val it = moves.entries.iterator()
    while (it.hasNext()) {
      val (t, m) = it.next()
      val f = m.at(n)
      var pos = Vector3(f.x, f.y, f.z)
      if (f.depthM != 0f) {
        val toHead = layoutHead - pos
        val len = hypot(toHead.x, toHead.z).coerceAtLeast(0.001f)
        pos = pos + Vector3(toHead.x / len, 0f, toHead.z / len) * f.depthM
      }
      t.place(Pose(pos, Quaternion.lookRotationAroundY(pos - layoutHead)), f.scale)
      if (f.done) it.remove()
    }
    if (moves.isEmpty()) {
      placeScrub() // the new centre has landed
      applyScrubVisible()
    }
  }

  private fun tileFailed(tile: StormTile, card: Card) {
    if (tile.card?.id != card.id) return
    // A centre whose original won't play falls back to its preview: the scene is not bad.
    if (layout == StormLayoutMode.FOCUS && tile.index == 0 && tile.clipKind == ClipKind.SEEK) {
      tile.play(card, ClipKind.PREVIEW, 0L, playing = !paused)
      return
    }
    pool?.markBad(card.id)
    poolSize = pool?.size ?: 0
    if (now() - lastFailAt < Storm.MIN_SWITCH_GAP_MS) return // a broken pool must not spin
    lastFailAt = now()
    cut(tile.index)
  }

  private fun closeTiles() {
    moves.clear()
    markersFor = null
    slots.closeAll()
    shownTiles = 0
  }

  private suspend fun loop() {
    var ticks = 0
    while (active) {
      delay(TICK_MS)
      try {
        if (++ticks % BUDGET_TICKS == 0) {
          applyPlan(rebuild = false)
          audit("budget")
        }
        refreshScrub()
        refreshInfo()
        if (paused) continue
        // The Focus centre / the selected tile (the one you picked, with the sound) is not in the
        // shuffle: it never cuts and never re-seeks, its clip just plays on (a preview loops, an
        // original runs on from where it was).
        scheduler.due(now(), stillTile())?.let { i -> cut(i) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "storm tick failed", e) // one bad tick must not end the loop and leave a frozen wall
      }
    }
  }

  // ---- invariant: one storm ----

  private fun counts() =
      StormCounts(
          active = active,
          planned = planned,
          tiles = tiles.size,
          liveTiles = slots.live,
          players = StormLive.players.get(),
          videoPanels = StormLive.panels.get(),
          loops = if (loopSlot.running) 1 else 0,
          controlPanels = if (panelSlot.isOpen) 1 else 0,
          scrubPanels = if (scrubSlot.isOpen) 1 else 0,
          scrubExpected = if (active && layout == StormLayoutMode.FOCUS && tiles.isNotEmpty()) 1 else 0,
          infoPanels = if (infoSlot.isOpen) 1 else 0,
          infoExpected = if (active && layout == StormLayoutMode.FOCUS && tiles.isNotEmpty()) 1 else 0,
      )

  /**
   * After every transition: logs the live tile / player / panel / loop counts (tag TroveStorm) and
   * an error when they differ from the plan (a second storm would show here). Debug builds only (off in
   * the published `publish` variant); log only: throwing here would crash the user's session.
   */
  private fun audit(what: String) {
    if (!BuildConfig.DEBUG) return
    val c = counts()
    val problems = c.problems().toMutableList()
    val other = host.otherLayers()
    val layers = LayerBudget.total(slots.live, other)
    if (active && LayerBudget.overBudget(layers)) problems += "layers $layers > budget ${LayerBudget.USABLE}"
    Log.d(TAG, "after $what: $c layers=$layers (tiles ${slots.live} + other $other, max ${LayerBudget.MAX_LAYERS})")
    if (problems.isNotEmpty()) {
      Log.e(TAG, "INVARIANT BROKEN after $what: ${problems.joinToString("; ")} ($c)")
    }
  }

  // ---- bring screens to me ----

  /** Where the tiles are (empty while Storm is off). */
  fun positions(): List<Vector3> = tiles.mapNotNull { it.position() }

  /** Turns the tiles and the control panel about the vertical axis through [head] by [deltaDeg]. */
  fun turnAbout(head: Vector3, deltaDeg: Float) {
    for (t in tiles) t.turnAbout(head, deltaDeg)
    layoutHead = turnedPose(Pose(layoutHead, Quaternion()), head, deltaDeg).t
    // Later re-lays (resize, swap) follow the turned layout: read its yaw back from a resting tile.
    val poses = StormLayout.poses(tiles.size, layout)
    tiles.withIndex().firstOrNull { it.value !in moves }?.let { (i, t) ->
      val d = t.currentPose().t - layoutHead
      layoutYaw = Math.atan2(d.x.toDouble(), d.z.toDouble()) - Math.toRadians(poses[i].yawDeg.toDouble())
    }
    placeScrub()
    placeInfo()
    val e = controlEntity ?: return
    val p = e.tryGetComponent<Transform>()?.transform ?: return
    e.setComponent(Transform(turnedPose(p, head, deltaDeg)))
  }

  // ---- Focus: the centre's scrub bar ----

  /** Called every frame by the input router: [hovering] = a ray is on the centre tile (or its bar). */
  fun pointerFrame(nowMs: Long, hovering: Boolean) {
    val show = active && layout == StormLayoutMode.FOCUS && !blackedOut && scrubHover.update(nowMs, hovering)
    if (show == scrubVisible) return
    scrubVisible = show
    applyScrubVisible()
    if (show) refreshScrub()
  }

  private fun applyScrubVisible() {
    scrubEntity?.setComponent(Visible(scrubVisible && !blackedOut && moves.isEmpty() && layout == StormLayoutMode.FOCUS))
  }

  /** Focus with a centre: the bar panel exists (once) and sits on it; otherwise it is gone. */
  private fun syncScrubPanel() {
    syncInfoPanel()
    if (active && layout == StormLayoutMode.FOCUS && tiles.isNotEmpty()) {
      scrubSlot.ensureOpen()
      placeScrub()
      applyScrubVisible()
    } else {
      scrubSlot.ensureClosed()
      scrubVisible = false
      scrubHover.reset()
    }
  }

  /**
   * Puts the bar inside the bottom of the centre (2/3 of its rendered width, centred), a hair in front of it (toward
   * the head). The scale comes from the centre's RENDERED size ([ScrubRules.placement]), not its layout scale.
   */
  private fun placeScrub() {
    val e = scrubEntity ?: return
    val c = tiles.getOrNull(0) ?: return
    val pose = c.currentPose()
    val pl = ScrubRules.placement(c.baseWidthM * c.layoutScale(), c.baseHeightM * c.layoutScale())
    val toHead = layoutHead - pose.t
    val len = hypot(toHead.x, toHead.z).coerceAtLeast(0.001f)
    val lift = Vector3(toHead.x / len, 0f, toHead.z / len) * ScrubRules.BAR_LIFT_M
    val down = Vector3(0f, pl.offsetY, 0f)
    e.setComponent(Transform(Pose(pose.t + lift + down, pose.q)))
    e.setComponent(Scale(Vector3(pl.scale)))
  }

  // ---- Focus: the scene-info panel beside the centre ----

  private fun infoShown() = active && layout == StormLayoutMode.FOCUS && infoSlot.isOpen && !infoHidden && !blackedOut && tiles.isNotEmpty()

  private fun syncInfoPanel() {
    if (active && layout == StormLayoutMode.FOCUS && tiles.isNotEmpty()) {
      infoSlot.ensureOpen()
      placeInfo()
      applyInfoVisible()
      refreshInfo()
    } else {
      infoSlot.ensureClosed()
    }
  }

  private fun applyInfoVisible() {
    infoEntity?.setComponent(Visible(!blackedOut && !infoHidden && layout == StormLayoutMode.FOCUS))
  }

  /** The panel's quad is registered expanded; collapsed, the tab stays at its left edge (the rest is transparent). */
  private fun placeInfo() {
    val e = infoEntity ?: return
    e.setComponent(Transform(slotPose(StormLayout.infoPose(false))))
  }

  fun collapseInfo(c: Boolean) {
    infoCollapsed = c
    prefs.edit().putBoolean(PREF_INFO_COLLAPSED, c).apply()
  }

  fun hideInfo() {
    infoHidden = true
    applyInfoVisible()
  }

  fun retryInfo() {
    infoCard = null // the next tick fetches again
    infoError = null
  }

  /** The panel shows the centre's scene: details fetched once per scene id (cached), the card's own fields meanwhile. */
  private fun refreshInfo() {
    val card = centre()?.card
    if (card?.id == infoCard?.id && (card == null || infoScene != null || infoError != null)) return
    if (card?.id == infoCard?.id && infoJob?.isActive == true) return
    infoCard = card
    infoError = null
    infoJob?.cancel()
    if (card == null) {
      infoScene = null
      return
    }
    val cached = infoCache[card.id]
    infoScene = cached
    if (cached != null) return
    infoJob =
        scope.launch {
          try {
            val sc = api.scene(card.id)
            infoCache.put(card.id, sc)
            if (infoCard?.id == card.id) infoScene = sc
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "info for ${card.id} unavailable: ${e.message}")
            if (infoCard?.id == card.id) infoError = e.message ?: e.toString()
          }
        }
  }

  private fun openInfo() {
    activity.registerPanel(
        ComposeViewPanelRegistration(
            infoPanelId,
            composeViewCreator = { _, ctx ->
              androidx.compose.ui.platform.ComposeView(ctx).apply { setContent { StormInfoPanel(this@StormManager) } }
            },
            settingsCreator = {
              val ip = StormLayout.infoPose(false)
              UIPanelSettings(
                  shape = QuadShapeOptions(width = ip.widthM, height = ip.heightM),
                  rendering = PanelRender.eyeBuffer,
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                  input = PanelInput.triggersOnly,
              )
            },
        ))
    infoEntity = Entity.create(listOf(Panel(infoPanelId), Transform(Pose(Vector3(0f), Quaternion())), Visible(false)))
  }

  private fun closeInfo() {
    infoEntity?.destroy()
    infoEntity = null
    activity.panelRegistrations.remove(infoPanelId)
    activity.systemManager.findSystem<PanelCreationSystem>().panelCreator.remove(infoPanelId)
  }

  /** The centre tile when it is a Focus centre with a scene. */
  private fun centre(): StormTile? = if (layout == StormLayoutMode.FOCUS) tiles.getOrNull(0)?.takeIf { it.card != null } else null

  /** Scene time (ms) the centre shows now. */
  private fun centreElapsedMs(t: StormTile): Long {
    val c = t.card ?: return 0L
    return ScrubRules.elapsedMs(t.clipKind, t.playerPositionMs(), t.playerDurationMs(), c.duration, montage)
  }

  /** Per tick while the bar shows: elapsed / total, and the markers of the centre's scene (read once per scene). */
  private fun refreshScrub() {
    if (!scrubVisible) return
    val t = centre() ?: return
    val c = t.card ?: return
    scrubDurationMs = (c.duration * 1000).toLong()
    scrubPositionMs = centreElapsedMs(t).coerceIn(0L, scrubDurationMs.coerceAtLeast(0L))
    scrubPlaying = t.isPlaying()
    if (markersFor != c.id) {
      markersFor = c.id
      scrubMarkers = emptyList()
      scope.launch {
        try {
          val m = api.scene(c.id).markers
          if (markersFor == c.id) scrubMarkers = m
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "markers for ${c.id} unavailable: ${e.message}")
        }
      }
    }
  }

  /**
   * Seek the centre to scene time [ms] (a trigger on the bar). The original just seeks; a centre that
   * is still a preview plays the original from [ms] (its position in the preview is a montage, so the
   * scene time is what counts), when the decoder budget has room; otherwise nothing happens.
   */
  fun seekCentre(ms: Long) {
    val t = centre() ?: return
    val card = t.card ?: return
    val target = ScrubRules.stepTarget(0L, ms, card.duration)
    when (ScrubRules.seekStep(t.clipKind, centreFits(card))) {
      ScrubRules.SeekStep.SEEK_ORIGINAL -> t.seekTo(target)
      ScrubRules.SeekStep.UPGRADE_AT -> t.play(card, ClipKind.SEEK, target, playing = !paused)
      ScrubRules.SeekStep.NONE -> return
    }
    scrubPositionMs = target
  }

  /** The scrub bar's Play / Pause: the centre alone (Pause all is the panel's). */
  fun toggleCentrePlay() {
    val t = centre() ?: return
    val play = !t.isPlaying()
    t.setPlaying(play)
    scrubPlaying = play
  }

  /** Stick left / right on the centre: [deltaMs] from where it is now (the control scheme's 10 s / 30 s steps). */
  fun seekCentreBy(deltaMs: Long) {
    if (!active || blackedOut) return
    val t = centre() ?: return
    val card = t.card ?: return
    // The player reports a new position at once after a seek, so repeated steps add up.
    seekCentre(ScrubRules.stepTarget(centreElapsedMs(t), deltaMs, card.duration))
  }

  private fun openScrub() {
    activity.registerPanel(
        ComposeViewPanelRegistration(
            scrubPanelId,
            composeViewCreator = { _, ctx ->
              androidx.compose.ui.platform.ComposeView(ctx).apply { setContent { StormScrubBar(this@StormManager) } }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = ScrubRules.REGISTERED_W_M, height = ScrubRules.BAR_H_M),
                  rendering = PanelRender.eyeBuffer,
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                  input = PanelInput.triggersOnly,
              )
            },
        ))
    scrubEntity = Entity.create(listOf(Panel(scrubPanelId), Transform(Pose(Vector3(0f), Quaternion())), Visible(false)))
  }

  private fun closeScrub() {
    scrubEntity?.destroy()
    scrubEntity = null
    activity.panelRegistrations.remove(scrubPanelId)
    activity.systemManager.findSystem<PanelCreationSystem>().panelCreator.remove(scrubPanelId)
  }

  // ---- control panel ----

  private fun openPanel() {
    activity.registerPanel(
        ComposeViewPanelRegistration(
            controlPanelId,
            composeViewCreator = { _, ctx ->
              androidx.compose.ui.platform.ComposeView(ctx).apply { setContent { StormControls(this@StormManager, host.room) } }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = PANEL_W_M, height = PANEL_H_M),
                  rendering = PanelRender.eyeBuffer,
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(UI_DP_PER_METER),
                  input = PanelInput.triggersOnly,
              )
            },
        ))
    val pose = Placement.inFront(activity.scene, PANEL_DISTANCE_M, dy = PANEL_DY_M)
    controlEntity =
        Entity.create(
            listOf(Panel(controlPanelId), Transform(pose), Grabbable(true, GrabbableType.PIVOT_Y), Visible(!blackedOut && !panelHidden)))
  }

  /**
   * The control panel sits below the layout, not over it: its top edge is under the lowest tile's bottom edge as
   * seen from the head (it is nearer than the tiles, so it would otherwise hide the bottom row).
   */
  private fun placePanelBelow() {
    val e = controlEntity ?: return
    if (buildPoses.isEmpty()) return
    val lowest = buildPoses.minOf { (it.dyM - it.heightM / 2f) / it.horizM } // tan of the lowest edge's angle
    val top = lowest * PANEL_DISTANCE_M - PANEL_GAP_M
    e.setComponent(Transform(Placement.inFront(activity.scene, PANEL_DISTANCE_M, dy = top - PANEL_H_M / 2f)))
  }

  private fun closePanel() {
    controlEntity?.destroy()
    controlEntity = null
    activity.panelRegistrations.remove(controlPanelId)
    activity.systemManager.findSystem<PanelCreationSystem>().panelCreator.remove(controlPanelId)
  }

  private companion object {
    const val TAG = "TroveStorm"
    const val TRIGGER_GAP_MS = Storm.TRIGGER_GAP_MS
    const val PREF_TILES = "storm_tiles"
    const val PREF_LAYOUT = "storm_layout"
    const val PREF_CLIP = "storm_clip_s"
    const val PREF_RING = "storm_focus_ring"
    const val PREF_SOURCE = "storm_source"
    const val PREF_INFO_COLLAPSED = "storm_info_collapsed"
    const val TICK_MS = 100L
    const val BUDGET_TICKS = 10
    const val PANEL_W_M = 1.4f
    const val PANEL_H_M = 0.70f
    const val PANEL_DISTANCE_M = 1.3f
    const val PANEL_DY_M = -0.75f
    const val PANEL_GAP_M = 0.04f
    const val FADE_OUT_MS = 260L
    const val FADE_IN_MS = 380L
    const val FADE_IN_WAIT_MS = 1500L
  }
}

/** Calls [onFrame] once per rendered frame (the swap glide); registered with the SDK on the first swap. */
class StormTweenSystem(private val onFrame: () -> Unit) : SystemBase() {
  override fun execute() = onFrame()
}
