package app.trove.quest.play

import android.os.SystemClock
import android.util.Log
import app.trove.quest.api.ApiException
import app.trove.quest.api.Scene
import app.trove.quest.player.PlayerSession
import app.trove.quest.player.ResumeBook
import app.trove.quest.player.ResumeStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports play activity of the open screens to the Trove server (play counts, resume point shared
 * with the Stash UI and the web player). The rules are in [ActivityTracker]; this class samples the
 * sessions, sends in order on one coroutine and never blocks or throws into the UI. Storm tiles are
 * not [PlayerSession]s and are never reported.
 *
 * Only the focused (audible) screen counts as watching. A server without the endpoint (404) switches
 * reporting off for the rest of the run.
 */
class ActivityReporter(
    private val scope: CoroutineScope,
    private val send: suspend (sceneId: String, report: ActivityReport) -> ActivityAck,
    private val fetchScene: suspend (sceneId: String) -> Scene,
    private val sessions: () -> List<PlayerSession>,
    private val isFocused: (PlayerSession) -> Boolean,
    private val resume: ResumeStore,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
  private class Job1(val tracker: ActivityTracker, val sceneId: String, val report: ActivityReport, val durationMs: Long)

  private val trackers = HashMap<PlayerSession, ActivityTracker>()
  private val queue = Channel<Job1>(Channel.UNLIMITED)
  private var sender: Job? = null
  private var ticker: Job? = null
  private var outstanding = 0

  /** Server has no play tracking (404): off for this run. */
  @Volatile var disabled = false
    private set

  /** Starts the 1 s sampling while screens are open (called when a screen is created). */
  fun ensureRunning() {
    if (sender?.isActive != true) sender = scope.launch { for (j in queue) deliver(j) }
    if (ticker?.isActive == true) return
    ticker =
        scope.launch {
          while (sessions().isNotEmpty()) {
            delay(TICK_MS)
            tick()
          }
        }
  }

  private fun tick() {
    if (disabled) return
    val now = clock()
    val open = sessions()
    trackers.keys.retainAll(open.toSet())
    for (s in open) {
      val t = trackers.getOrPut(s) { ActivityTracker() }
      val counting = isFocused(s) && s.advancing
      val r = runCatching { t.sample(now, s.positionMs, s.durationMs, counting) }.getOrNull() ?: continue
      // Offline / slow server: don't pile up periodic reports, keep their seconds for the next one.
      if (!r.final && outstanding > 0) t.restore(r.played) else enqueue(t, s, r)
    }
  }

  /** App backgrounded, Meta menu, stop all: a final report for every screen that has something to say. */
  fun flush() {
    if (disabled) return
    val now = clock()
    for (s in sessions()) {
      val t = trackers[s] ?: continue
      enqueue(t, s, runCatching { t.flush(now, s.positionMs, s.durationMs) }.getOrNull() ?: continue)
    }
  }

  /** Before [s] is released (its position is still good). */
  fun closing(s: PlayerSession) {
    val t = trackers.remove(s) ?: return
    if (disabled) return
    enqueue(t, s, runCatching { t.closing(clock(), s.positionMs, s.durationMs) }.getOrNull() ?: return)
  }

  private fun enqueue(t: ActivityTracker, s: PlayerSession, r: ActivityReport) {
    outstanding++
    queue.trySend(Job1(t, s.card.id, r, s.durationMs))
  }

  private suspend fun deliver(j: Job1) {
    try {
      if (disabled) return
      val ack = send(j.sceneId, j.report)
      // The server's stored point (0 after the end) is the shared truth; remember it as the newest.
      resume.noteRemote(j.sceneId, RemoteResume(finishedAware(ack.resumeTime, j), System.currentTimeMillis()))
    } catch (e: CancellationException) {
      throw e
    } catch (e: ApiException) {
      when {
        e.status == 404 -> {
          disabled = true
          Log.i(TAG, "server has no play tracking, reporting off")
        }
        e.status >= 500 -> j.tracker.restore(j.report.played) // 502: Stash refused, resend with the next write
        else -> Log.w(TAG, "report dropped: ${e.status} ${e.message}") // 400 / 401 / 415: resending won't help
      }
    } catch (e: IOException) {
      j.tracker.restore(j.report.played)
    } catch (e: Exception) {
      Log.w(TAG, "report failed", e)
    } finally {
      outstanding--
    }
  }

  private fun finishedAware(seconds: Double, j: Job1): Long {
    val ms = (seconds * 1000).toLong().coerceAtLeast(0)
    return if (ResumeBook.finished(ms, j.durationMs)) 0 else ms
  }

  /**
   * A scene was just opened from the start: ask the server where it was left (it may have been
   * watched elsewhere) and update the "Resume at" offer unless the wearer already used or dismissed it.
   */
  fun refreshOffer(s: PlayerSession) {
    val before = s.resumeOfferMs
    scope.launch {
      try {
        val scene = fetchScene(s.card.id)
        resume.noteScene(scene)
        if (s in sessions() && s.resumeOfferMs == before && s.positionMs < ResumeBook.MIN_MS) {
          s.resumeOfferMs = resume.get(s.card.id)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // offline or older server: the local offer stands
      }
    }
  }

  private companion object {
    const val TAG = "TroveActivity"
    const val TICK_MS = 1000L
  }
}
