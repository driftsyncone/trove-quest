package app.trove.quest.storm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The tiles of the one Storm session. Every tile is created and disposed here, so [live] (created
 * minus disposed) can be compared with the list and with the plan: a replacement is only created
 * after the old ones are disposed, and a count change only touches the last slots.
 */
class StormSlots<T : Any>(private val create: (slot: Int) -> T, private val dispose: (T) -> Unit) {
  val items = mutableListOf<T>()
  var created = 0
    private set
  var disposed = 0
    private set

  /** Tiles that exist (created, not disposed yet). */
  val live: Int
    get() = created - disposed

  fun closeAll() {
    val old = items.toList()
    items.clear()
    for (t in old) {
      dispose(t)
      disposed++
    }
  }

  /** A new layout / storm: all old tiles are disposed first, then [count] new ones exist. */
  fun rebuild(count: Int) {
    closeAll()
    for (i in 0 until count) add(i)
  }

  /** A changed count: the last slots close or new ones open; the rest stay. */
  fun resize(count: Int) {
    val change = Storm.resize(items.size, count)
    for (i in change.remove.reversed()) {
      val t = items.removeAt(i)
      dispose(t)
      disposed++
    }
    for (i in change.add) add(i)
  }

  private fun add(slot: Int) {
    val t = create(slot)
    created++
    items += t
  }
}

/** One coroutine at most: [restart] cancels the previous one before the new one starts. */
class LoopSlot(private val scope: CoroutineScope) {
  private var job: Job? = null
  var starts = 0
    private set

  val running: Boolean
    get() = job?.isActive == true

  /** Starts [block] unless one is already running ([force] replaces it). Returns whether it started. */
  fun ensure(force: Boolean = false, block: suspend () -> Unit): Boolean {
    if (running && !force) return false
    job?.cancel()
    starts++
    job = scope.launch { block() }
    return true
  }

  fun stop() {
    job?.cancel()
    job = null
  }
}

/** The control panel registration: opened once, closed once; asking again does nothing. */
class PanelSlot(private val open: () -> Unit, private val close: () -> Unit) {
  var isOpen = false
    private set
  var opens = 0
    private set

  fun ensureOpen() {
    if (isOpen) return
    open()
    isOpen = true
    opens++
  }

  fun ensureClosed() {
    if (!isOpen) return
    close()
    isOpen = false
  }
}

/** Players and video panels that exist across all tiles (counted by StormTile; read by the invariant check). */
object StormLive {
  val players = java.util.concurrent.atomic.AtomicInteger()
  val panels = java.util.concurrent.atomic.AtomicInteger()
}

/** What Storm owns right now, for the invariant check (debug builds log it after every transition). */
data class StormCounts(
    val active: Boolean,
    /** The tile count the plan allows (0 when off). */
    val planned: Int,
    val tiles: Int,
    val liveTiles: Int,
    /** Players and video panels that exist (from StormTile's counters). */
    val players: Int,
    val videoPanels: Int,
    val loops: Int,
    val controlPanels: Int,
    /** The Focus scrub bar's panel registrations, and how many a Focus session with a centre should have. */
    val scrubPanels: Int = 0,
    val scrubExpected: Int = 0,
    /** The Focus scene-info panel's registrations, and how many there should be. */
    val infoPanels: Int = 0,
    val infoExpected: Int = 0,
) {
  override fun toString() =
      "active=$active planned=$planned tiles=$tiles liveTiles=$liveTiles players=$players videoPanels=$videoPanels loops=$loops controlPanels=$controlPanels scrubPanels=$scrubPanels infoPanels=$infoPanels"

  /** Empty when the counts are what a single Storm should have. */
  fun problems(): List<String> {
    val out = mutableListOf<String>()
    if (!active) {
      if (tiles != 0 || liveTiles != 0 || players != 0 || videoPanels != 0) out += "tiles left behind while Storm is off"
      if (loops != 0) out += "loop running while Storm is off"
      if (controlPanels != 0) out += "control panel left behind while Storm is off"
      if (scrubPanels != 0) out += "scrub bar left behind while Storm is off"
      if (infoPanels != 0) out += "info panel left behind while Storm is off"
      return out
    }
    if (tiles > planned) out += "more tiles ($tiles) than planned ($planned)"
    if (liveTiles != tiles) out += "live tiles $liveTiles != listed $tiles"
    if (players != tiles) out += "players $players != tiles $tiles"
    if (videoPanels != tiles) out += "video panels $videoPanels != tiles $tiles"
    if (loops != 1) out += "loops $loops != 1"
    if (controlPanels != 1) out += "control panels $controlPanels != 1"
    if (scrubPanels != scrubExpected) out += "scrub bars $scrubPanels != $scrubExpected"
    if (infoPanels != infoExpected) out += "info panels $infoPanels != $infoExpected"
    return out
  }
}
