package app.trove.quest.storm

import app.trove.quest.api.Card
import app.trove.quest.api.Projection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Storm mode rules (README "Storm"): a wall of small videos that each cycle random clips. Pure
 * Kotlin, no Spatial SDK or Android types, so they are unit tested in `StormRulesTest`.
 */

/** Where a tile's clip comes from. */
enum class ClipKind {
  /** Stash's generated preview mp4 (`/media/scene/<id>/preview`, ~15 s H.264, tiny): the default. */
  PREVIEW,

  /** A random offset inside the original file (`/stream`, seekable): only for small H.264 files. */
  SEEK,
}

/** What the Source chip asks for; [SEEK] falls back to a preview for scenes the headset can't seek cheaply. */
enum class SourceMode(val label: String) {
  PREVIEWS("Previews"),
  SEEKS("Random seeks"),
}

enum class StormLayoutMode(val label: String) {
  WALL("Curved wall"),
  SURROUND("Surround"),

  /** One big centre tile (sound, never cuts) with preview tiles round it. */
  FOCUS("Focus"),
}

/** How a tile's panel splits the video into eyes (registered with the panel, so fixed per registration). */
enum class StormStereo {
  NONE,
  LEFT_RIGHT,
  UP_DOWN,
}

object Storm {
  /**
   * Where a preview starts (ms), so tiles differ: a random point in the first [PREVIEW_START_SPAN] of a
   * preview [lengthMs] long; 0 for a short (or unknown) one. Picked before loading, so the tile opens
   * there instead of showing the start and then jumping (a second buffering and range request).
   */
  fun previewStartMs(lengthMs: Long, random: Double): Long =
      if (lengthMs > PREVIEW_RANDOM_MIN_MS) (lengthMs * PREVIEW_START_SPAN * random.coerceIn(0.0, 1.0)).toLong() else 0L

  /** Previews shorter than this start at 0. */
  const val PREVIEW_RANDOM_MIN_MS = 4_000L

  /** The part of a preview a random start falls in (the rest is left to play before it loops). */
  const val PREVIEW_START_SPAN = 0.7

  val TILE_COUNTS = listOf(4, 9, 16, 25)
  /** Fewest scenes worth a storm. */
  const val MIN_POOL = 3

  /** Why a pool of [eligible] usable scenes out of [total] read is too small (null = fine). */
  fun poolProblem(eligible: Int, total: Int): String? =
      if (eligible >= MIN_POOL) null
      else if (total == 0) "This view has no scenes to storm."
      else "Only $eligible of $total scenes here can be tiles (180/360 and fisheye files and odd shapes are skipped)."

  const val MIN_CLIP_S = 5
  const val MAX_CLIP_S = 60
  const val DEFAULT_TILES = 9
  const val DEFAULT_CLIP_S = 20

  /** Focus layout: preview tiles round the centre (the centre is one more tile on top of this). */
  val FOCUS_RING_COUNTS = listOf(6, 8, 12)
  const val DEFAULT_FOCUS_RING = 8

  /** The clip-length slider (5 - 60 s, whole seconds): a raw slider position becomes a valid length. */
  fun clipSecondsFromSlider(v: Float): Int = Math.round(v).coerceIn(MIN_CLIP_S, MAX_CLIP_S)

  fun clipMs(seconds: Int): Long = seconds.coerceIn(MIN_CLIP_S, MAX_CLIP_S) * 1000L

  /** Total tiles a layout asks for: [tiles] for the wall / surround, the centre plus [ring] for Focus. */
  fun requestedTotal(layout: StormLayoutMode, tiles: Int, ring: Int): Int = if (layout == StormLayoutMode.FOCUS) ring + 1 else tiles

  /** What changes when the tile count goes from [old] to [new]: slots to close (the last ones) and slots to open. */
  data class Resize(val remove: IntRange, val add: IntRange) {
    val unchanged: Boolean
      get() = remove.isEmpty() && add.isEmpty()
  }

  fun resize(old: Int, new: Int): Resize =
      Resize(remove = if (new < old) new until old else IntRange.EMPTY, add = if (new > old) old until new else IntRange.EMPTY)

  /** The SDK can deliver one trigger twice: a trigger this soon after the previous one is the same click. */
  const val TRIGGER_GAP_MS = 300L

  fun triggerAccepted(lastAt: Long, now: Long): Boolean = lastAt == 0L || now - lastAt >= TRIGGER_GAP_MS

  /** How far a clip length may vary either way, so tiles never switch together. */
  const val JITTER = 0.4

  /** No clip shorter than this, whatever the jitter says. */
  const val MIN_CLIP_MS = 2_000L

  /** At most one tile cuts per this many ms: a decoder reconfigure each, so no storm of them at once. */
  const val MIN_SWITCH_GAP_MS = 300L

  /** Pool: the query's first page tells the total; this many 60-scene pages are then read (random ones when there are more). */
  const val POOL_PAGE_SIZE = 60
  const val POOL_MAX_PAGES = 4

  /** Tiles are 16:9; a file much off that would stretch, so it stays out of the pool. */
  const val MIN_ASPECT = 1.55f
  const val MAX_ASPECT = 2.05f

  /** Files with at most 1920 x 1088 pixels of H.264 are cheap enough to seek in. */
  const val SEEK_MAX_PIXELS = 1920L * 1088L

  /** The first and last bit of a scene (titles, credits) is not worth a clip. */
  const val SKIP_EDGE = 0.04

  /** How [card] must be registered: SBS split left / right, over / under split top / bottom, else plain. */
  fun stereoFor(card: Card): StormStereo =
      when (card.projectionEnum) {
        Projection.SBS -> StormStereo.LEFT_RIGHT
        Projection.OU -> StormStereo.UP_DOWN
        else -> StormStereo.NONE
      }

  /**
   * Scenes a 16:9 tile can show: flat, or a 3D screen (side by side / over under), whose eye is 16:9
   * either full (twice as wide / tall) or squeezed (half-SBS / half-OU stretch back). 180 / 360 /
   * fisheye spheres can't be a tile. Other shapes would stretch and stay out.
   */
  fun eligible(card: Card): Boolean {
    val proj = card.projectionEnum
    if (proj != Projection.FLAT && proj != Projection.SBS && proj != Projection.OU) return false
    if (card.width <= 0 || card.height <= 0) return true // size unknown: let it through
    val aspect = card.width.toFloat() / card.height
    val eyeAspects =
        when (proj) {
          Projection.SBS -> listOf(aspect, aspect / 2f)
          Projection.OU -> listOf(aspect, aspect * 2f)
          else -> listOf(aspect)
        }
    return eyeAspects.any { it in MIN_ASPECT..MAX_ASPECT }
  }

  /** The scenes a query result gives the pool: eligible, each id once. */
  fun filterPool(cards: List<Card>): List<Card> = cards.filter(::eligible).distinctBy { it.id }

  fun seekEligible(card: Card): Boolean =
      card.codec.lowercase().let { it == "h264" || it == "avc" || it == "avc1" } &&
          card.width > 0 && card.height > 0 &&
          card.width.toLong() * card.height <= SEEK_MAX_PIXELS &&
          card.duration >= 30.0

  fun kindFor(card: Card, mode: SourceMode): ClipKind =
      if (mode == SourceMode.SEEKS && seekEligible(card)) ClipKind.SEEK else ClipKind.PREVIEW

  /** Random start (ms) of a [clipMs] clip inside a scene [durationSec] long, away from its first and last 4 %. */
  fun clipStartMs(durationSec: Double, clipMs: Long, rnd: Random): Long {
    val total = (durationSec * 1000).toLong()
    val lo = (total * SKIP_EDGE).toLong()
    val hi = total - (total * SKIP_EDGE).toLong() - clipMs
    if (hi <= lo) return (total - clipMs).coerceAtLeast(0)
    return lo + (rnd.nextDouble() * (hi - lo)).toLong()
  }

  /** Which 60-scene pages (1-based) of a query with [total] scenes feed the pool: all of them up to [maxPages], else random ones. */
  fun poolPages(total: Int, rnd: Random, perPage: Int = POOL_PAGE_SIZE, maxPages: Int = POOL_MAX_PAGES): List<Int> {
    val pages = ((total + perPage - 1) / perPage).coerceAtLeast(1)
    if (pages <= maxPages) return (1..pages).toList()
    return (1..pages).shuffled(rnd).take(maxPages).sorted()
  }

  /** "on, 9 tiles" for the partner console's headset line; null while storm is off. */
  fun syncLabel(active: Boolean, tiles: Int): String? =
      if (!active) null else "on, $tiles ${if (tiles == 1) "tile" else "tiles"}"

  /** One clip's length with jitter: [clipMs] x (1 +/- [jitter]). */
  fun nextDelayMs(clipMs: Long, rnd: Random, jitter: Double = JITTER): Long =
      (clipMs * (1.0 + jitter * (2 * rnd.nextDouble() - 1))).toLong().coerceAtLeast(MIN_CLIP_MS)
}

/**
 * The scenes tiles draw from: a shuffled deck, so nothing repeats until every scene was shown.
 * Scenes on screen right now are skipped when anything else is left; broken ones ([markBad]) never come back.
 */
class StormPool(cards: List<Card>, private val rnd: Random) {
  private val all = Storm.filterPool(cards)
  private val bad = mutableSetOf<String>()
  private val deck = ArrayDeque<Card>()

  val size: Int
    get() = all.count { it.id !in bad }

  fun markBad(id: String) {
    bad += id
  }

  /**
   * The next scene, not one of [inUse] unless that is all there is; null when the pool is empty.
   * With [stereo], a scene of that kind comes first when the deck has one (a tile whose panel
   * already is that kind then needs no new registration).
   */
  fun next(inUse: Set<String>, stereo: StormStereo? = null): Card? {
    if (size == 0) return null
    repeat(2) {
      deck.removeAll { it.id in bad }
      val i =
          (if (stereo != null) deck.indexOfFirst { it.id !in inUse && Storm.stereoFor(it) == stereo } else -1).takeIf { it >= 0 }
              ?: deck.indexOfFirst { it.id !in inUse }
      if (i >= 0) return deck.removeAt(i)
      deck.clear() // nothing fresh left in this deck: shuffle a new one
      deck.addAll(all.filter { it.id !in bad }.shuffled(rnd))
    }
    return deck.removeFirstOrNull() // every scene is on screen already
  }
}

/**
 * When each tile cuts to its next clip. Deadlines are spread (the first ones are staggered) and
 * jittered, and [due] hands out at most one tile per [Storm.MIN_SWITCH_GAP_MS].
 */
class StormScheduler(private val rnd: Random) {
  private var deadlines = LongArray(0)
  private var lastSwitch = Long.MIN_VALUE / 2

  /** New tiles at [now]: the first cuts are spread over 30 % - 100 % of a clip in a random order. */
  fun reset(count: Int, now: Long, clipMs: Long) {
    val order = (0 until count).shuffled(rnd)
    deadlines = LongArray(count)
    for (tile in 0 until count) {
      val rank = order[tile] + 1
      deadlines[tile] = now + (Storm.nextDelayMs(clipMs, rnd) * (0.3 + 0.7 * rank / count)).toLong()
    }
  }

  fun deadline(tile: Int): Long = deadlines[tile]

  /** The tile count changed: existing deadlines stay, new slots get staggered first cuts. */
  fun resize(count: Int, now: Long, clipMs: Long) {
    val old = deadlines.size
    if (count == old) return
    val next = LongArray(count)
    for (i in 0 until minOf(old, count)) next[i] = deadlines[i]
    for (i in old until count) next[i] = now + (Storm.nextDelayMs(clipMs, rnd) * (0.3 + 0.7 * rnd.nextDouble())).toLong()
    deadlines = next
  }

  /** A tile's next cut a fresh clip away without counting as a cut (a swapped-out centre rejoining the shuffle). */
  fun rearm(tile: Int, now: Long, clipMs: Long) {
    if (tile in deadlines.indices) deadlines[tile] = now + Storm.nextDelayMs(clipMs, rnd)
  }

  /**
   * The tile to cut now, or null. The most overdue one first. [skip] is never returned: the Focus
   * centre / the selected tile does not take part in the shuffle at all (it plays on as it is, and
   * does not use up the cut gap either).
   */
  fun due(now: Long, skip: Int? = null): Int? {
    if (now - lastSwitch < Storm.MIN_SWITCH_GAP_MS) return null
    var best = -1
    for (i in deadlines.indices) if (i != skip && deadlines[i] <= now && (best < 0 || deadlines[i] < deadlines[best])) best = i
    return best.takeIf { it >= 0 }
  }

  fun switched(tile: Int, now: Long, clipMs: Long) {
    lastSwitch = now
    deadlines[tile] = now + Storm.nextDelayMs(clipMs, rnd)
  }

  /** Every tile cuts as soon as the gap rule allows ("Shuffle now"); in index order (the one [due] skips stays out). */
  fun shuffleNow(now: Long) {
    for (i in deadlines.indices) deadlines[i] = now
  }

  /** Paused for [ms]: nothing becomes due while it was stopped. */
  fun postpone(ms: Long) {
    for (i in deadlines.indices) deadlines[i] += ms
  }
}

/**
 * Decoder and block budget (README "Streams and the decoder budget"): the Quest 3 hardware decoder
 * handles at most 16 instances and 7,776,000 16x16 blocks per second, shared with the open screens.
 * Two instances stay free (a Solo screen, a screen's stream swap), and tiles may use 80 % of the blocks.
 */
object DecoderBudget {
  const val MAX_INSTANCES = 16
  const val SPARE_INSTANCES = 2
  const val MAX_BLOCKS_PER_S = 7_776_000L
  const val BLOCK_SHARE = 0.8

  /** A Stash preview: 640 x 360 at 30 fps. */
  const val PREVIEW_BLOCKS = 40L * 23 * 30

  /** The biggest seekable original (1920 x 1088) at a pessimistic 60 fps. */
  const val SEEK_BLOCKS = 120L * 68 * 60

  fun blocksPerSecond(width: Int, height: Int, fps: Int): Long =
      ceil(width / 16.0).toLong() * ceil(height / 16.0).toLong() * fps

  fun tileBlocks(mode: SourceMode): Long = if (mode == SourceMode.SEEKS) SEEK_BLOCKS else PREVIEW_BLOCKS

  /** What open screens cost: the focused one its file at 60 fps (capped at 8K), the others a 720p transcode at 30 fps. */
  fun screenBlocks(files: List<Pair<Int, Int>>, focused: Int): Long =
      files.withIndex().sumOf { (i, wh) ->
        val (w, h) = wh
        if (i == focused) blocksPerSecond(w.coerceIn(1, 7680), h.coerceIn(1, 4320), 60)
        else blocksPerSecond(min(w, 2560).coerceAtLeast(1), min(h, 720).coerceAtLeast(1), 30)
      }

  /**
   * Whether the Focus centre may play its original: the screens, the other [tiles] - 1 tiles at
   * [perTile] blocks and the original ([width] x [height] at 60 fps, capped at 8K) fit the block budget.
   */
  fun centreFits(width: Int, height: Int, tiles: Int, screenBlocks: Long, perTile: Long): Boolean {
    val own = originalBlocks(width, height)
    val used = screenBlocks + (tiles - 1).coerceAtLeast(0) * perTile + own
    return used <= MAX_BLOCKS_PER_S * BLOCK_SHARE
  }

  /** Blocks per second the centre's original costs ([width] x [height] at 60 fps, capped at 8K; unknown = the biggest seekable). */
  fun originalBlocks(width: Int, height: Int): Long =
      if (width <= 0 || height <= 0) SEEK_BLOCKS else blocksPerSecond(width.coerceIn(1, 7680), height.coerceIn(1, 4320), 60)

  /**
   * What the Focus centre's original costs on top of a preview tile: the plan keeps this free, so a
   * re-plan never opens a ring tile the centre's original needs the room of.
   */
  fun centreReserve(width: Int, height: Int, perTile: Long): Long = (originalBlocks(width, height) - perTile).coerceAtLeast(0L)

  /**
   * How many ring tiles must close for the centre's original to fit (0 = it fits as is), or null when
   * it doesn't fit even with no ring at all (the open screens alone use the room).
   */
  fun ringDropsNeeded(width: Int, height: Int, tiles: Int, screenBlocks: Long, perTile: Long): Int? {
    for (k in 0 until tiles.coerceAtLeast(1)) {
      if (centreFits(width, height, tiles - k, screenBlocks, perTile)) return k
    }
    return null
  }

  enum class Limit { INSTANCES, BLOCKS, LAYERS }

  data class Plan(val tiles: Int, val requested: Int, val limit: Limit?) {
    /** What the panel says when fewer tiles than asked for are shown. */
    fun note(): String? =
        when {
          tiles >= requested -> null
          tiles == 0 && limit == Limit.LAYERS -> "No layer room for tiles: close some screens."
          tiles == 0 -> "No decoder room for tiles: close some screens."
          limit == Limit.LAYERS -> "$tiles of $requested tiles: display layer limit (${LayerBudget.USABLE} layers with the screens and library)."
          limit == Limit.BLOCKS -> "$tiles of $requested tiles: video decoder speed limit."
          else -> "$tiles of $requested tiles: decoder limit ($MAX_INSTANCES instances, $SPARE_INSTANCES kept free)."
        }
  }

  /** [layerRoom]: tiles the compositor layer budget allows next to the other layers ([LayerBudget.tileRoom]). */
  fun plan(requested: Int, openScreens: Int, screenBlocks: Long, perTile: Long, layerRoom: Int = Int.MAX_VALUE): Plan {
    val byInstances = (MAX_INSTANCES - SPARE_INSTANCES - openScreens).coerceAtLeast(0)
    val room = (MAX_BLOCKS_PER_S * BLOCK_SHARE - screenBlocks).coerceAtLeast(0.0)
    val byBlocks = floor(room / perTile).toInt()
    val tiles = minOf(requested, byInstances, byBlocks, layerRoom).coerceAtLeast(0)
    val limit =
        when {
          tiles >= requested -> null
          layerRoom < minOf(byBlocks, byInstances) -> Limit.LAYERS
          byBlocks < byInstances -> Limit.BLOCKS
          else -> Limit.INSTANCES
        }
    return Plan(tiles, requested, limit)
  }
}

/**
 * What the Focus centre does the moment a tile becomes the centre (a swap, Storm starting): a preview
 * is a montage of segments (Stash jumps between them), so the centre must not stay on it. Pure rule,
 * unit tested in `CentreUpgradeTest`; StormManager.upgradeCentre does what it says.
 */
object CentreUpgrade {
  enum class Step {
    /** Already the original: nothing to do. */
    KEEP,

    /** Play the original from the preview's spot in the scene, now. */
    UPGRADE,

    /** No room: close [Decision.dropRing] ring tiles (the last slots), then play the original. */
    DROP_RING_THEN_UPGRADE,

    /** No room even with no ring (the open screens use it all): the preview is paused on its frame, not left jumping. */
    FREEZE_PREVIEW,
  }

  data class Decision(val step: Step, val dropRing: Int = 0)

  fun decide(kind: ClipKind, width: Int, height: Int, tiles: Int, screenBlocks: Long, perTile: Long): Decision {
    if (kind == ClipKind.SEEK) return Decision(Step.KEEP)
    val drops = DecoderBudget.ringDropsNeeded(width, height, tiles, screenBlocks, perTile)
    return when {
      drops == null -> Decision(Step.FREEZE_PREVIEW)
      drops == 0 -> Decision(Step.UPGRADE)
      else -> Decision(Step.DROP_RING_THEN_UPGRADE, drops)
    }
  }
}

/**
 * Where tiles go, relative to the head and the way the user faces when the storm opens. [yawDeg]:
 * right of straight ahead (negative = left); [horizM]: distance along the floor; [dyM]: above eye level.
 * Every tile faces the head.
 */
data class TilePose(val yawDeg: Float, val horizM: Float, val dyM: Float, val widthM: Float, val heightM: Float)

object StormLayout {
  const val WALL_RADIUS_M = 2.2f
  const val WALL_MAX_ARC_DEG = 130f
  const val WALL_MAX_TILE_DEG = 34f
  const val WALL_GAP_DEG = 1.5f
  const val WALL_CENTER_DY_M = -0.1f
  const val SURROUND_RADIUS_M = 2.0f
  const val SURROUND_MAX_TILE_DEG = 36f
  const val SURROUND_RING_GAP_DEG = 3f

  /** Columns x rows for [n] tiles: few empty slots, about 3:2 wide. */
  fun grid(n: Int): Pair<Int, Int> {
    var best = 1 to n
    var bestCost = Double.MAX_VALUE
    for (rows in 1..6) {
      val cols = ceil(n.toDouble() / rows).toInt()
      if (cols < 1) continue
      val cost = (cols * rows - n) * 2.0 + abs(cols.toDouble() / rows - 1.5) * 3.0
      if (cost < bestCost) {
        bestCost = cost
        best = cols to rows
      }
    }
    return best
  }

  /** [n] tiles in total; for FOCUS the first pose is the centre and the rest the ring round it. */
  fun poses(n: Int, mode: StormLayoutMode): List<TilePose> =
      when (mode) {
        StormLayoutMode.WALL -> wall(n)
        StormLayoutMode.SURROUND -> surround(n)
        StormLayoutMode.FOCUS -> focus(n - 1)
      }

  const val FOCUS_RADIUS_M = 2.2f

  /** Width of the Focus centre tile (was 1.8 m): smaller, so the ring can sit closer and bigger. */
  const val FOCUS_CENTRE_W_M = 1.4f

  /**
   * Ring tile size, the knob to tune: the width the ring tiles had before the Focus rework ([FOCUS_RING_BASE_W_M]
   * for up to [FOCUS_DENSE_FROM] - 1 tiles, [FOCUS_RING_BASE_DENSE_W_M] for more) times this. 1.4 = 40 % larger.
   */
  const val FOCUS_RING_SCALE = 1.4f
  const val FOCUS_RING_BASE_W_M = 0.62f
  const val FOCUS_RING_BASE_DENSE_W_M = 0.5f
  const val FOCUS_DENSE_FROM = 9

  /** Width of one ring tile when [ring] tiles surround the centre. */
  fun focusRingWidth(ring: Int): Float =
      (if (ring < FOCUS_DENSE_FROM) FOCUS_RING_BASE_W_M else FOCUS_RING_BASE_DENSE_W_M) * FOCUS_RING_SCALE

  const val FOCUS_GAP_M = 0.05f
  const val FOCUS_DY_M = -0.1f

  /** Half-angles the whole Focus layout must fit in (0.4.5's ellipse pushed 12 tiles ~2 m to each side). */
  const val FOCUS_MAX_HALF_YAW_DEG = 34f
  const val FOCUS_MAX_HALF_PITCH_DEG = 24f

  /**
   * One big tile straight ahead and [ring] small ones in a row above it and a row below (index 0 =
   * centre; the top row gets the extra tile when [ring] is odd; no side columns), so everything stays
   * inside [FOCUS_MAX_HALF_YAW_DEG] x [FOCUS_MAX_HALF_PITCH_DEG]. Ring tiles get [focusRingWidth] when that
   * fits and shrink until the frame fits and no two tiles are closer than the gap. Laid out on a flat
   * plane 2.2 m ahead, then read back as yaw / distance / height.
   */
  fun focus(ring: Int): List<TilePose> {
    val cw = FOCUS_CENTRE_W_M
    val ch = cw * 9f / 16f
    val out = mutableListOf(planePose(0f, 0f, cw, ch))
    if (ring <= 0) return out
    val g = FOCUS_GAP_M
    val top = (ring + 1) / 2
    val bottom = ring / 2
    val maxW = 2 * FOCUS_RADIUS_M * tan(FOCUS_MAX_HALF_YAW_DEG * PI / 180).toFloat()
    val maxH = 2 * FOCUS_RADIUS_M * tan(FOCUS_MAX_HALF_PITCH_DEG * PI / 180).toFloat()
    var sw = focusRingWidth(ring)
    while (true) {
      val sh = sw * 9f / 16f
      val pts = mutableListOf<Pair<Float, Float>>()
      fun row(k: Int, y: Float) = repeat(k) { i -> pts += ((i - (k - 1) / 2f) * (sw + g)) to y }
      row(top, ch / 2 + g + sh / 2)
      row(bottom, -(ch / 2 + g + sh / 2))
      val halfW = pts.maxOf { abs(it.first) } + sw / 2
      val halfH = maxOf(pts.maxOf { abs(it.second) } + sh / 2, ch / 2)
      val fits = 2 * halfW <= maxW && 2 * halfH <= maxH
      if (sw <= 0.1f || (fits && focusClear(pts, sw, sh, cw, ch))) return out + pts.map { (x, y) -> planePose(x, y, sw, sh) }
      sw *= 0.98f
    }
  }

  /** The scene-info panel beside the centre (expanded / collapsed to a slim tab); its left edge is [FOCUS_GAP_M] right of the centre. */
  const val INFO_W_M = 0.7f
  const val INFO_TAB_W_M = 0.12f

  /** Pose of the info panel (same plane as the focus layout, as tall as the centre so it stays clear of the ring rows). */
  fun infoPose(collapsed: Boolean): TilePose {
    val cw = FOCUS_CENTRE_W_M
    val w = if (collapsed) INFO_TAB_W_M else INFO_W_M
    return planePose(cw / 2 + FOCUS_GAP_M + w / 2, 0f, w, cw * 9f / 16f)
  }

  private fun focusClear(pts: List<Pair<Float, Float>>, sw: Float, sh: Float, cw: Float, ch: Float): Boolean {
    val g = FOCUS_GAP_M
    for ((i, p) in pts.withIndex()) {
      if (abs(p.first) < (cw + sw) / 2 + g && abs(p.second) < (ch + sh) / 2 + g) return false
      for (j in i + 1 until pts.size) {
        val q = pts[j]
        if (abs(p.first - q.first) < sw + g && abs(p.second - q.second) < sh + g) return false
      }
    }
    return true
  }

  private fun planePose(x: Float, y: Float, w: Float, h: Float) =
      TilePose(
          yawDeg = Math.toDegrees(kotlin.math.atan2(x, FOCUS_RADIUS_M).toDouble()).toFloat(),
          horizM = kotlin.math.hypot(x, FOCUS_RADIUS_M),
          dyM = FOCUS_DY_M + y,
          widthM = w,
          heightM = h,
      )

  /** The chord a tile of [tileDeg] spans: flat tiles touch the cylinder / sphere at their edges, so neighbours can't overlap. */
  private fun widthAt(radius: Float, tileDeg: Float) = (2 * radius * sin(tileDeg / 2 * PI / 180)).toFloat()

  /** A grid on a cylinder in front of the head, row by row from top left; the last row is centred. */
  private fun wall(n: Int): List<TilePose> {
    if (n <= 0) return emptyList()
    val (cols, rows) = grid(n)
    val tileDeg = min(WALL_MAX_TILE_DEG, WALL_MAX_ARC_DEG / cols - WALL_GAP_DEG)
    val w = widthAt(WALL_RADIUS_M, tileDeg)
    val h = w * 9f / 16f
    val pitch = h + (WALL_RADIUS_M * tan(WALL_GAP_DEG * PI / 180)).toFloat()
    val step = tileDeg + WALL_GAP_DEG
    return List(n) { i ->
      val row = i / cols
      val inRow = min(cols, n - row * cols)
      val col = i % cols
      TilePose(
          yawDeg = (col - (inRow - 1) / 2f) * step,
          horizM = WALL_RADIUS_M,
          dyM = WALL_CENTER_DY_M + ((rows - 1) / 2f - row) * pitch,
          widthM = w,
          heightM = h,
      )
    }
  }

  /** Rings of tiles around the head (1 ring up to 8 tiles, 2 up to 16, else 3); the gap is behind you. */
  private fun surround(n: Int): List<TilePose> {
    if (n <= 0) return emptyList()
    val rings = if (n <= 8) 1 else if (n <= 16) 2 else 3
    val out = mutableListOf<TilePose>()
    for (r in 0 until rings) {
      val inRing = n / rings + if (r < n % rings) 1 else 0
      val step = 360f / inRing
      val tileDeg = min(SURROUND_MAX_TILE_DEG, step * 0.8f)
      val w = widthAt(SURROUND_RADIUS_M, tileDeg)
      val h = w * 9f / 16f
      val pitchDeg = ((rings - 1) / 2f - r) * (tileDeg * 9f / 16f + SURROUND_RING_GAP_DEG)
      val rad = pitchDeg * PI / 180
      for (k in 0 until inRing) {
        out +=
            TilePose(
                yawDeg = (k - (inRing - 1) / 2f) * step,
                horizM = (SURROUND_RADIUS_M * cos(rad)).toFloat(),
                dyM = (SURROUND_RADIUS_M * sin(rad)).toFloat(),
                widthM = w,
                heightM = h,
            )
      }
    }
    return out
  }
}

/**
 * A tile gliding to another place (Focus swap): position, size and a depth bulge, eased. Pure
 * numbers so the tween is unit tested; the manager applies [at] every frame. [bulgeM] pushes the
 * tile toward the head at mid-flight (positive) or away from it (negative), so the two tiles of a
 * swap pass in front of / behind each other instead of through each other.
 */
class SlotMove(
    val fromX: Float, val fromY: Float, val fromZ: Float,
    val toX: Float, val toY: Float, val toZ: Float,
    val fromScale: Float, val toScale: Float,
    val startMs: Long,
    val durationMs: Long,
    val bulgeM: Float = 0f,
) {
  data class Frame(val x: Float, val y: Float, val z: Float, val scale: Float, val depthM: Float, val done: Boolean)

  /** 0..1 progress at [now], eased in and out (smootherstep). */
  fun progress(now: Long): Float =
      if (durationMs <= 0) 1f else ease(((now - startMs).toFloat() / durationMs).coerceIn(0f, 1f))

  fun at(now: Long): Frame {
    val t = progress(now)
    val done = durationMs <= 0 || now - startMs >= durationMs
    return Frame(
        lerp(fromX, toX, t), lerp(fromY, toY, t), lerp(fromZ, toZ, t),
        lerp(fromScale, toScale, t),
        if (done) 0f else bulgeM * sin(PI * t).toFloat(),
        done,
    )
  }

  companion object {
    const val SWAP_MS = 350L
    const val SWAP_BULGE_M = 0.25f

    fun ease(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
  }
}
