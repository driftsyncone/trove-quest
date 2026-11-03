package app.trove.quest.play

import app.trove.quest.player.ResumeBook
import app.trove.quest.player.ResumePoint
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One body for `POST /api/scenes/<id>/activity` (Trove server README "Play tracking and resume").
 * Seconds everywhere; [position] is scene time (a transcode's start offset already included).
 */
data class ActivityReport(
    val position: Double,
    val played: Double,
    val duration: Double = 0.0,
    val final: Boolean = false,
    val ended: Boolean = false,
) {
  fun toJson(): String =
      buildJsonObject {
            put("position", JsonPrimitive(position))
            put("played", JsonPrimitive(played))
            if (duration > 0) put("duration", JsonPrimitive(duration))
            if (final) put("final", JsonPrimitive(true))
            if (ended) put("ended", JsonPrimitive(true))
          }
          .toString()
}

/** Answer of the activity endpoint; [resumeTime] is what the server stored (0 = from the start). */
@Serializable data class ActivityAck(val ok: Boolean = true, val resumeTime: Double = 0.0, val playCounted: Boolean = false)

/**
 * The timing / state rules of play reporting for ONE open screen (pure, unit tested in
 * ActivityTrackerTest; times are a monotonic clock in ms).
 *
 * [sample] is called about once a second with whether this screen is *counting*: it is the focused
 * screen and its picture advances right now. Only counting time becomes `played`. A screen that never
 * counted reports nothing at all (unfocused screens, a scene opened paused and closed again).
 *
 * Reports: every [intervalMs] while counting; `final` when counting stops (paused, lost focus) and on
 * [flush] / [closing] (backgrounded, closed); `ended` once when the position reaches the last
 * [END_SLACK_MS] (re-armed by seeking away). A report that failed is given back with [restore] so
 * its seconds go out with the next one.
 */
class ActivityTracker(private val intervalMs: Long = INTERVAL_MS) {
  private var lastAt: Long? = null
  private var lastCounting = false
  private var pendingMs = 0L
  private var lastSentAt: Long? = null
  private var lastReportedPosMs = -1L
  private var engaged = false
  private var endedSent = false

  fun sample(now: Long, positionMs: Long, durationMs: Long, counting: Boolean): ActivityReport? {
    val prevAt = lastAt
    if (lastCounting && prevAt != null) pendingMs += (now - prevAt).coerceIn(0, MAX_GAP_MS)
    val wasCounting = lastCounting
    lastAt = now
    lastCounting = counting
    if (counting) {
      engaged = true
      if (lastSentAt == null || !wasCounting) lastSentAt = now // the 10 s count from when playing (re)starts
    }
    if (!engaged) return null
    if (atEnd(positionMs, durationMs)) {
      if (!endedSent) {
        endedSent = true
        return take(now, positionMs, durationMs, final = true, ended = true)
      }
      return null
    }
    if (durationMs <= 0 || positionMs < durationMs - REARM_MS) endedSent = false
    if (wasCounting && !counting) return flushIfChanged(now, positionMs, durationMs)
    if (counting && now - (lastSentAt ?: now) >= intervalMs) return take(now, positionMs, durationMs)
    return null
  }

  /** App backgrounded / stop all / Meta menu: a final report if something is unreported. */
  fun flush(now: Long, positionMs: Long, durationMs: Long): ActivityReport? {
    if (!engaged) return null
    lastAt?.let { if (lastCounting) pendingMs += (now - it).coerceIn(0, MAX_GAP_MS) }
    lastAt = now
    if (atEnd(positionMs, durationMs) && !endedSent) {
      endedSent = true
      return take(now, positionMs, durationMs, final = true, ended = true)
    }
    return flushIfChanged(now, positionMs, durationMs)
  }

  /** The screen closes: always a final report once it has counted (the position may have been seeked). */
  fun closing(now: Long, positionMs: Long, durationMs: Long): ActivityReport? {
    if (!engaged) return null
    if (lastCounting) lastAt?.let { pendingMs += (now - it).coerceIn(0, MAX_GAP_MS) }
    lastCounting = false
    lastAt = now
    val ended = atEnd(positionMs, durationMs)
    if (ended && endedSent) {
      // Already told the server; just make sure leftover seconds are not lost.
      if (pendingMs <= 0) return null
    }
    return take(now, positionMs, durationMs, final = true, ended = ended)
  }

  /** A report did not reach the server (502, offline): its seconds go out with the next one. */
  fun restore(playedSeconds: Double) {
    pendingMs += (playedSeconds * 1000).toLong()
  }

  private fun flushIfChanged(now: Long, positionMs: Long, durationMs: Long): ActivityReport? {
    val moved = lastReportedPosMs < 0 || kotlin.math.abs(positionMs - lastReportedPosMs) >= 1000
    return if (pendingMs > 0 || moved) take(now, positionMs, durationMs, final = true) else null
  }

  private fun take(now: Long, positionMs: Long, durationMs: Long, final: Boolean = false, ended: Boolean = false): ActivityReport {
    val ms = pendingMs.coerceAtMost(MAX_PLAYED_MS)
    pendingMs -= ms
    lastSentAt = now
    lastReportedPosMs = positionMs
    return ActivityReport(
        position = round1(positionMs / 1000.0),
        played = round1(ms / 1000.0),
        duration = if (durationMs > 0) round1(durationMs / 1000.0) else 0.0,
        final = final,
        ended = ended,
    )
  }

  private fun atEnd(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs >= durationMs - END_SLACK_MS

  private fun round1(v: Double) = Math.round(v * 10) / 10.0

  companion object {
    const val INTERVAL_MS = 10_000L
    /** A stall between two samples counts at most this much played time. */
    const val MAX_GAP_MS = 5_000L
    /** The server caps `played` at 600 s per report. */
    const val MAX_PLAYED_MS = 600_000L
    const val END_SLACK_MS = 2_000L
    const val REARM_MS = 10_000L
  }
}

/** What the server says about a scene: [ms] resume point (0 = none / finished), [at] when it was last played (wall clock ms). */
data class RemoteResume(val ms: Long, val at: Long?)

/** Which resume point wins, server or this headset (pure, unit tested in ResumeMergeTest). */
object ResumeMerge {
  /** The server only offers a resume past the first 5 s. */
  const val REMOTE_MIN_MS = 5_000L
  /** Clock skew between headset and Stash host. */
  const val SKEW_MS = 60_000L

  /**
   * The server's point wins (shared with the Stash UI and the web player), except: unknown server
   * data = the local point; a local point clearly newer than the server's last play (watched offline,
   * report lost) wins; a server "0" with a play newer than the local point means finished elsewhere.
   */
  fun merge(remote: RemoteResume?, local: ResumePoint?): Long? {
    if (remote == null) return local?.ms
    if (local != null && remote.at != null && local.at > remote.at + SKEW_MS) return local.ms
    if (remote.ms >= REMOTE_MIN_MS) return remote.ms
    // Server has no point: finished or never played there.
    if (local == null) return null
    return if (remote.at == null || local.at > remote.at) local.ms else null
  }

  /** [seconds] as sent by the server; a point in the finished zone counts as none. */
  fun remoteOf(seconds: Double, lastPlayedAt: String?, durationMs: Long): RemoteResume {
    var ms = (seconds * 1000).toLong().coerceAtLeast(0)
    if (ResumeBook.finished(ms, durationMs)) ms = 0
    return RemoteResume(ms, parseInstant(lastPlayedAt))
  }

  fun parseInstant(text: String?): Long? =
      if (text.isNullOrBlank()) null else runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}
