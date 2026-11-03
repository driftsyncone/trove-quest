package app.trove.quest.sync

/**
 * The session timer (Trove server README "Session timer"): the server owns it (one per user), so the
 * headset only asks for a change ([TimerOp]) and shows what the status says ([TimerState]). At zero the
 * server latches STOPPED (reason "Session timer ended") and sends `pause-all` with reason "timer"; the
 * timer never starts or resumes anything, here or on the server.
 */
data class TimerState(
    /** Server wall clock (epoch ms) when it ends. */
    val endsAt: Long,
    /** "wearer" or "partner": who set or last changed it. */
    val setBy: String,
)

/** What the Timer row can ask for; [label] is the chip text. */
sealed class TimerOp(val label: String) {
  data class Set(val minutes: Int) : TimerOp("$minutes min")

  data object Extend : TimerOp("+${SessionTimer.EXTEND_MIN} min")

  data object Cancel : TimerOp("Cancel")
}

object SessionTimer {
  /** The presets both sides offer (the server refuses anything else). */
  val PRESETS = listOf(15, 30, 45, 60, 90)
  const val EXTEND_MIN = 10

  /** `reason` of the server's pause-all at zero. */
  const val REASON = "timer"

  /** Status `reason` while the timer's latch holds. */
  const val ENDED_REASON = "Session timer ended"

  const val ENDED_NOTICE = "Timer ended"

  /** At or under this the tag turns amber (the 5 min warning). */
  const val WARN_MS = 5 * 60_000L

  /** The chips of the Timer row: the presets always, +10 and Cancel only while one runs. */
  fun choices(running: Boolean): List<TimerOp> =
      PRESETS.map { TimerOp.Set(it) } + if (running) listOf(TimerOp.Extend, TimerOp.Cancel) else emptyList()

  /**
   * Server clock minus local clock from the hello's `serverNow` and the local wall clock when it came
   * (the trip's latency is in it, well under the tag's second); 0 without one (older server).
   */
  fun clockOffsetMs(serverNow: Long?, localNowMs: Long): Long = serverNow?.let { it - localNowMs } ?: 0L

  /** The server's clock now: the local wall clock plus [SyncState.clockOffsetMs]. */
  fun serverNow(localNowMs: Long, offsetMs: Long): Long = localNowMs + offsetMs

  /** Time left at [nowMs] (epoch ms on the SERVER's clock, see [serverNow]), never negative; null without a timer. */
  fun leftMs(t: TimerState?, nowMs: Long): Long? = t?.let { (it.endsAt - nowMs).coerceAtLeast(0L) }

  /** "28:41", "1:02:00", "0:42": seconds rounded up, so a fresh 30 min timer reads 30:00. */
  fun format(leftMs: Long): String {
    val s = (leftMs.coerceAtLeast(0L) + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
  }

  /** The tag next to the volume tag ("Timer 28:41"); null without a timer. */
  fun tagText(t: TimerState?, nowMs: Long): String? = leftMs(t, nowMs)?.let { "Timer ${format(it)}" }

  /** The tag is amber in the last five minutes. */
  fun warning(t: TimerState?, nowMs: Long): Boolean = leftMs(t, nowMs)?.let { it <= WARN_MS } ?: false

  /** The server's `{"type":"timer","event":"warn","minutesLeft":N}` as a notice. */
  fun warnText(minutesLeft: Int): String = "$minutesLeft min left"

  /** Notices (warnings, "Timer ended") are never shown during Black Out: nothing is, there. */
  fun showNotice(blackout: Boolean): Boolean = !blackout

  /**
   * The 5 / 1 min warnings and "Timer ended" also go to the small notice panel (the button legend's,
   * about 5 s) when nothing that shows the sync tag is in view: the library hidden, no screen's
   * controls shown (none open, or hidden) and no Storm panel shown. Never during Black Out.
   */
  fun noticePanel(blackout: Boolean, libraryVisible: Boolean, controlsShown: Boolean, stormPanelShown: Boolean): Boolean =
      showNotice(blackout) && !libraryVisible && !controlsShown && !stormPanelShown
}
