package app.trove.quest.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** [SyncClient.state] for Compose: the wall header and every screen's controls read it. */
class SyncIndicator {
  var state by mutableStateOf(SyncState())

  /** Start / Resume from the headset, set by the activity ([SyncClient.sendResume]); `confirm` after a held press. */
  var onResume: (confirm: Boolean) -> Unit = {}

  /** Session timer set / +10 / cancel from the Timer row ([SyncClient.sendTimer]); false = not sent (link down). */
  var onTimer: (TimerOp) -> Boolean = { false }
}

/** What the small sync tag says, or null for nothing (link off). */
data class SyncBadge(val text: String, val level: Level) {
  enum class Level {
    NEUTRAL,
    ACCENT,
    OK,
    WARN,
    ALERT,
  }

  companion object {
    /** After the partner's STOP ALL: what happened and what to do (nothing; they resume). */
    const val PARTNER_STOP = "Stopped by your partner — wait for them to resume"

    /** After the wearer's own escape: Resume is a console action. */
    const val WEARER_STOP = "Stopped — resume from the console"

    /** Stopped and the headset itself may start / resume the toy ([ToyResume]). */
    const val TOY_NOT_STARTED = "Toy not started · press Start toy"
    const val TOY_STOPPED = "Toy stopped · press Resume toy"
    const val PARTNER_STOP_HOLD = "Stopped by your partner · hold Resume toy"

    /**
     * Partner STOP ALL wins over everything (the wearer must see why the video stopped and what to do);
     * then a short notice ("Stopped — you're in control"), the wearer's own latch, the link. With the
     * link up it says only what is true about the toy: unarmed (the server's fake driver), armed, on, or
     * stopped ([SyncState.armed] from the server's status; an older server that doesn't say: "Link ok").
     * [quiet] (the screens' controls) hides the all-fine state, which the wall shows.
     */
    fun of(s: SyncState, quiet: Boolean = false): SyncBadge? =
        when {
          s.link == SyncLink.OFF -> null
          s.byPartner && s.resume == ResumeMode.CONFIRM -> SyncBadge(PARTNER_STOP_HOLD, Level.ALERT)
          s.byPartner -> SyncBadge(PARTNER_STOP, Level.ALERT)
          s.notice != null -> SyncBadge(s.notice, Level.OK)
          s.byWearer && s.resume != ResumeMode.NO -> SyncBadge(TOY_STOPPED, Level.ALERT)
          s.byWearer -> SyncBadge(WEARER_STOP, Level.ALERT)
          s.link == SyncLink.CONNECTING -> if (quiet) null else SyncBadge("Sync: connecting", Level.WARN)
          s.link == SyncLink.RECONNECTING -> SyncBadge("Sync: reconnecting", Level.WARN)
          s.stopped && s.resume != ResumeMode.NO -> SyncBadge(if (ToyResume.notStarted(s)) TOY_NOT_STARTED else TOY_STOPPED, Level.WARN)
          quiet -> null
          s.armed == false -> SyncBadge("Link ok · toy unarmed", Level.NEUTRAL)
          s.armed == true && s.stopped -> SyncBadge("Link ok · toy stopped", Level.NEUTRAL)
          s.armed == true && s.device == "playing" -> SyncBadge("Link ok · toy on", Level.ACCENT)
          s.armed == true -> SyncBadge("Link ok · toy armed", Level.ACCENT)
          else -> SyncBadge("Link ok", Level.OK)
        }
  }
}
