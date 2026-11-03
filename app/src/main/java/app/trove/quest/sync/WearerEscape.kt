package app.trove.quest.sync

/**
 * The wearer's universal escape (both grips, 2 s, any state): the headset does everything local at
 * once (pause, room, lift Black Out) and tells the server, which latches STOPPED "by the wearer".
 * This is the pure part: whether a queued stop is still worth sending after the link returns, and
 * how long the headset itself counts as stopped (new scenes open paused) until the server says the
 * partner resumed. Nothing here ever resumes anything.
 */
object EscapeRules {
  /** A stop the link could not carry is dropped after this long (the toy's dead-man stopped it anyway). */
  const val RELEVANT_MS = 5 * 60_000L

  /**
   * After a reconnect: send the queued stop only if the server is not already stopped (it then
   * shows its own latch, and a second one would only rewrite the reason) and it is not stale.
   */
  fun stillRelevant(queuedAtMs: Long, nowMs: Long, serverStopped: Boolean): Boolean =
      !serverStopped && nowMs - queuedAtMs in 0..RELEVANT_MS
}

/**
 * The headset's own "I pulled the escape" state. [active] means: treat as STOPPED (new scenes open
 * paused, the room stays shown) until the server has been seen latched and then released (the
 * partner's Resume), or the queued stop was dropped as stale. Without this, a status that was
 * already on its way when the escape fired could end it early.
 */
class WearerEscapeState {
  var active = false
    private set

  private var seenStopped = false

  fun trigger() {
    active = true
    seenStopped = false
  }

  /** A server status arrived while [connected] (link up). True when this ended the escape. */
  fun onStatus(stopped: Boolean, connected: Boolean, stopQueued: Boolean): Boolean {
    if (!active) return false
    if (stopped) seenStopped = true
    else if (seenStopped && connected && !stopQueued) {
      active = false
      return true
    }
    return false
  }

  /** The queued stop was dropped without the server ever latching (stale): back to normal. */
  fun dropped() {
    active = false
  }
}
