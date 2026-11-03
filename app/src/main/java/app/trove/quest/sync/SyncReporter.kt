package app.trove.quest.sync

/**
 * When to send a state report. Polled every [SyncClient.POLL_MS]; mirrors the web player
 * (Trove server web/src/sync.ts) so the server sees the same cadence from both:
 *
 * - at once on a change the toy has to follow: a screen opened / closed, play / pause, focus,
 *   scene, rate, or a jump in position (seek, a stall catching up) of more than [JUMP_MS];
 * - when the head turns to face another screen, or by 10 degrees ([HeadView.key]): the console's map;
 * - otherwise at least every [HEARTBEAT_MS], the heartbeat the server's dead-man rule waits for
 *   (it stops and latches after 3 s of silence) and the console's clock re-anchor.
 *
 * The server extrapolates the focused screen's time as positionMs + (now - receipt) and restarts
 * the device script when that drifts more than 1 s from its own clock, so a report within ~1 s and
 * an immediate one on every jump keeps the toy on the picture.
 */
class SyncReporter {
  private var lastKey: String? = null
  private var lastSent = Long.MIN_VALUE / 2
  private var lastPos = emptyMap<String, Pair<Long, Long>>() // id -> (position, at)

  /** Forget everything: the next [shouldSend] is true (after a reconnect). */
  fun reset() {
    lastKey = null
    lastPos = emptyMap()
  }

  /** True if [screens] at [now] (ms, monotonic) should go out; records them as sent if so. */
  fun shouldSend(screens: List<SyncScreen>, now: Long, force: Boolean = false, viewKey: String = ""): Boolean {
    val key = screens.joinToString("|") { "${it.id}:${it.sceneId}:${it.playing}:${it.focused}:${it.rate}" } + "#" + viewKey
    var jumped = false
    val next = HashMap<String, Pair<Long, Long>>()
    for (s in screens) {
      lastPos[s.id]?.let { (pos, at) ->
        val expected = pos + if (s.playing) ((now - at) * s.rate).toLong() else 0L
        if (kotlin.math.abs(expected - s.positionMs) > JUMP_MS) jumped = true
      }
      next[s.id] = s.positionMs to now
    }
    lastPos = next
    if (!force && key == lastKey && !jumped && now - lastSent < HEARTBEAT_MS) return false
    lastKey = key
    lastSent = now
    return true
  }

  companion object {
    /** Position this far off the expected one counts as a seek. */
    const val JUMP_MS = 400L
    /** The web player sends every 750-1000 ms; the server wants at least one a second. */
    const val HEARTBEAT_MS = 750L
  }
}
