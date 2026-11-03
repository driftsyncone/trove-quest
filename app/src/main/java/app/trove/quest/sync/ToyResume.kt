package app.trove.quest.sync

/** A Start / Resume control for the wearer: its [label], and whether it needs a held press ([hold]). */
data class ToyResumeOffer(val label: String, val hold: Boolean)

/**
 * When the headset itself may start or resume the toy. The server decides and says so in every status
 * (`resume`): the control shows exactly when the link is up, the server is latched and that `resume` is
 * not "no". "now" = one press ("Start toy" before the first start, "Resume toy" after a wearer escape or
 * a dead-man stop); "confirm" = a partner's STOP ALL, which needs a 2 s hold. Pure, so it is unit tested.
 */
object ToyResume {
  /** How long the confirm hold lasts. */
  const val HOLD_MS = 2000L

  fun notStarted(s: SyncState): Boolean = s.reason?.startsWith("Not started") == true

  fun of(s: SyncState): ToyResumeOffer? {
    if (s.link != SyncLink.CONNECTED || !s.stopped) return null
    return when (s.resume) {
      ResumeMode.NO -> null
      ResumeMode.NOW -> ToyResumeOffer(if (notStarted(s)) "Start toy" else "Resume toy", hold = false)
      ResumeMode.CONFIRM -> ToyResumeOffer("Hold to resume toy", hold = true)
    }
  }
}
