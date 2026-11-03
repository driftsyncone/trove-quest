package app.trove.quest.sync

/**
 * Black Out as the headset sees it (Trove server README "Funscript sync and partner console"): the
 * partner blanks everything (panels hidden, audio muted, passthrough off; the videos keep playing).
 * This is the pure part: on / off, the volume rule, the two-grip hold timer of the wearer's escape
 * (which works in every state, Black Out included) and the lift on link loss. The scene work (hiding entities, passthrough) is done by whoever flips [set].
 *
 * Volumes are not saved and restored: a screen's volume is always derived from "is it the audible
 * one" ([volume]), so lifting restores exactly what was there, however focus moved meanwhile.
 */
class BlackoutState(private val holdMs: Long = WEARER_HOLD_MS) {
  var on = false
    private set

  /** Last [seq] the server sent with an "on" command; echoed in the headset's reply. */
  var seq = 0L
    private set

  private var heldSince: Long? = null
  private var fired = false

  /** Switch to [value]; true if that changed anything. [commandSeq]: the server command's number. */
  fun set(value: Boolean, commandSeq: Long = seq): Boolean {
    seq = commandSeq
    if (on == value) return false
    on = value
    return true
  }

  /** Player volume for a screen: silent while black, else 1 for the audible screen and 0 for the rest. */
  fun volume(audible: Boolean): Float = if (!on && audible) 1f else 0f

  /**
   * The wearer's escape rule, fed the grip state once per frame, in ANY state (playing, Storm, Black
   * Out, menus, a panel held): true exactly once when both grips have been held for [holdMs] without a
   * break. It fires again only after a release, so one long squeeze is one escape. Carrying a panel
   * (one grip) never counts; a grip already held when something else starts counts from that moment
   * on, not from before. The caller does the escape (it is not done here).
   */
  fun gripTick(nowMs: Long, bothGrips: Boolean): Boolean {
    if (!bothGrips) {
      heldSince = null
      fired = false
      return false
    }
    if (fired) return false
    val since = heldSince ?: nowMs.also { heldSince = it }
    if (nowMs - since < holdMs) return false
    fired = true
    return true
  }

  /** The sync socket dropped: the wearer must never sit in black without a partner link. */
  fun linkLost(): Boolean = set(false)

  companion object {
    const val WEARER_HOLD_MS = 2000L
  }
}
