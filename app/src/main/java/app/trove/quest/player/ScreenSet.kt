package app.trove.quest.player

/** What [ScreenSet] needs from a screen. [PlayerSession] in the app, fakes in the tests. */
interface ScreenHandle {
  /** 180/360 (and fisheye) scenes: single-scene, every other screen pauses and hides. */
  val isSphere: Boolean

  /** True when the file is big enough to drop to a transcode while unfocused (and nothing pins its stream). */
  val canDowngrade: Boolean

  /** True while it decodes a big original (a heavy HEVC decoder instance). */
  val heavyNow: Boolean

  /** [audible]: the only screen with sound. [highlighted]: focused, and there is more than one screen. */
  fun applyFocus(audible: Boolean, highlighted: Boolean)

  /** Original stream (true) or the low-res transcode. Before start / while suspended it is only remembered. */
  fun applyQuality(original: Boolean)

  /** Paused, hidden and decoder released (true) / back where it was (false). */
  fun setSuspended(suspended: Boolean)
}

/**
 * Focus, sound, stream quality and sphere exclusivity for up to [max] screens.
 *
 * - One screen is focused: it alone has sound and (after [MultiScreen.FOCUS_DWELL_MS]) plays the
 *   original file; unfocused big files play a 720p transcode. A lone screen always plays the original.
 * - A sphere (180/360) is single-scene: while one is open it has focus and every other screen is
 *   suspended (paused, hidden); they come back when it closes or turns into a flat screen again.
 * - Closing the focused screen focuses the one focused before it.
 *
 * Quality changes caused by focus moves wait for the dwell; the owner calls [applyQualityIfDue] at
 * [qualityDueAt] (a new screen gets its quality at once, so it never starts on the wrong stream).
 */
class ScreenSet<S : ScreenHandle>(val max: Int = MultiScreen.MAX_SCREENS, private val now: () -> Long) {
  private val list = mutableListOf<S>()
  private val mru = mutableListOf<S>() // most recently focused last
  private val suspended = mutableSetOf<S>()
  private val focusState = mutableMapOf<S, Pair<Boolean, Boolean>>()

  val screens: List<S>
    get() = list

  var focused: S? = null
    private set

  /** When the pending quality change is due ([now] time base), or null. */
  var qualityDueAt: Long? = null
    private set

  val isFull: Boolean
    get() = list.size >= max

  val size: Int
    get() = list.size

  fun isSuspended(s: S): Boolean = s in suspended

  /** The sphere that owns the view, if any: the focused one, else the last opened. */
  val activeSphere: S?
    get() = focused?.takeIf { it.isSphere } ?: list.lastOrNull { it.isSphere }

  private val visibleCount: Int
    get() = list.size - suspended.size

  /** The stream a screen should play now: the original when focused or alone. */
  fun wantsOriginal(s: S): Boolean = s === focused || visibleCount <= 1 || !s.canDowngrade

  fun add(s: S, focus: Boolean = true) {
    require(!isFull) { "already $max screens" }
    list += s
    if (focus || focused == null) setFocus(s)
    refresh()
    s.applyQuality(wantsOriginal(s)) // the newcomer starts on the right stream
    scheduleQuality()
  }

  fun remove(s: S) {
    if (!list.remove(s)) return
    mru.remove(s)
    suspended.remove(s)
    focusState.remove(s)
    if (focused === s) {
      focused = null
      (mru.lastOrNull() ?: list.lastOrNull())?.let { setFocus(it) }
    }
    refresh()
    scheduleQuality()
  }

  /** Trigger on a screen or its controls. A suspended screen can't be focused (it is hidden). */
  fun focus(s: S) {
    if (s !in list || s in suspended || s === focused) return
    setFocus(s)
    refresh()
    scheduleQuality()
  }

  /** A screen changed shape (View: flat <-> 180/360): re-apply the sphere rule. */
  fun changed(s: S) {
    if (s !in list) return
    // Turning a visible screen into a sphere takes over the view (a hidden one stays hidden).
    if (s.isSphere && s !in suspended) setFocus(s)
    refresh()
    scheduleQuality()
  }

  /** Applies the pending quality change if it is due; true if it did. */
  fun applyQualityIfDue(): Boolean {
    val due = qualityDueAt ?: return false
    if (now() < due) return false
    applyQuality()
    return true
  }

  /** Every visible screen to the stream it should play now. Downgrades first, to free decoders. */
  fun applyQuality() {
    qualityDueAt = null
    val visible = list.filter { it !in suspended }
    for (s in visible) if (!wantsOriginal(s)) s.applyQuality(false)
    for (s in visible) if (wantsOriginal(s)) s.applyQuality(true)
  }

  /**
   * Upgrading [s] to its original may wait until no other visible screen still decodes a big
   * original (the old focus mid-downgrade): two 8K60 HEVC streams are already the whole budget.
   */
  fun mayUpgrade(s: S): Boolean = list.none { it !== s && it !in suspended && it.heavyNow }

  private fun setFocus(s: S) {
    focused = s
    mru.remove(s)
    mru += s
  }

  private fun scheduleQuality() {
    qualityDueAt = now() + MultiScreen.FOCUS_DWELL_MS
  }

  /** Sphere exclusivity, then sound and highlight. */
  private fun refresh() {
    val sphere = activeSphere
    if (sphere != null && focused !== sphere) setFocus(sphere)
    val resumed = mutableListOf<S>()
    for (s in list) {
      val shouldSuspend = sphere != null && s !== sphere
      if (shouldSuspend && s !in suspended) {
        suspended += s
        s.setSuspended(true)
      } else if (!shouldSuspend && s in suspended) {
        suspended -= s
        resumed += s
      }
    }
    // Back from behind a sphere: reload straight on the stream they should play now.
    for (s in resumed) {
      s.applyQuality(wantsOriginal(s))
      s.setSuspended(false)
    }
    val multi = visibleCount > 1
    for (s in list) {
      val f = s === focused && s !in suspended
      val state = f to (f && multi)
      if (focusState[s] != state) {
        focusState[s] = state
        s.applyFocus(audible = state.first, highlighted = state.second)
      }
    }
  }
}
