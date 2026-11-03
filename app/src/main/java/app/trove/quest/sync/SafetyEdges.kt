package app.trove.quest.sync

/**
 * When the app pauses everything on its own (README "Headset off and the Meta menu"): the activity is
 * paused (headset taken off, another app or a system screen in front) or the Meta menu has the input.
 * Two causes, one pause: [update] returns the edge, true = just went away (pause every screen and
 * Storm, remembering what played), false = just came back (resume only that), null = no change. So
 * the menu closing while the headset is still off resumes nothing. Pure, unit tested.
 */
class AwayGate {
  var away = false
    private set

  fun update(activityResumed: Boolean, menuOpen: Boolean): Boolean? {
    val now = !activityResumed || menuOpen
    if (now == away) return null
    away = now
    return now
  }
}

/**
 * The rising edge of a person's STOP ALL (the partner's, or the wearer's escape) in the server's
 * status. The app treats it like the `pause-all` command (every screen paused, the room shown), so a
 * STOP ALL that came while the link was down still stops the video once the status arrives after the
 * reconnect, even if the command itself never does. Idempotent with the command: pausing twice is fine.
 */
class PersonStopEdge {
  private var was = false

  fun rose(s: SyncState): Boolean {
    val now = s.byPartner || s.byWearer
    val rising = now && !was
    was = now
    return rising
  }
}
