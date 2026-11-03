package app.trove.quest.gaze

import app.trove.quest.posture.PostureLayout
import kotlin.math.acos

/** A screen as the head sees it: where its centre is and how big it looks. [radiusDeg] = half its angular width. */
data class GazeTarget(val id: String, val yawDeg: Float, val pitchDeg: Float, val radiusDeg: Float)

/**
 * Look to focus (docs/vr-hook-ideas.md S1): the screen you face for [dwellMs] gets focus, no click.
 * Plain Kotlin so the rule is unit tested; [GazeFocus] feeds it the head pose every 100 ms.
 *
 * - A screen counts as faced when the gaze is inside [ENTER_RATIO] of its angular radius (so the
 *   gap between two screens, and their edges, face nothing). Where two screens overlap in the gaze,
 *   the one the gaze is more central in wins.
 * - Hysteresis: the focused screen keeps focus until another one is more central by [SWITCH_MARGIN]
 *   (in radii) AND has been faced for the dwell; scanning across the wall past a screen never takes
 *   focus, and a gaze resting on the border between two does not flicker.
 * - After a switch the next one waits [COOLDOWN_MS]: a focus change swaps decoders and the toy follows
 *   the focused screen, so a glance must never ping-pong it.
 * - [blocked] (gripping, dragging, pointing at a panel, wall up) clears the dwell: it starts over
 *   once the block ends.
 */
class GazeRule(
    private val dwellMs: Long = DWELL_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
  private var pending: String? = null
  private var since = 0L
  private var lastSwitch = Long.MIN_VALUE / 2

  /** Forget the dwell in progress (a block, or the screens changed). */
  fun reset() {
    pending = null
  }

  /** The screen to focus now, or null. [focusedId]: the one with focus today. */
  fun update(nowMs: Long, gazeYawDeg: Float, gazePitchDeg: Float, targets: List<GazeTarget>, focusedId: String?, blocked: Boolean): String? {
    if (blocked || targets.isEmpty()) {
      pending = null
      return null
    }
    val cand = candidate(gazeYawDeg, gazePitchDeg, targets, focusedId)
    if (cand == null || cand == focusedId) {
      pending = null
      return null
    }
    if (cand != pending) {
      pending = cand
      since = nowMs
      return null
    }
    if (nowMs - since < dwellMs || nowMs - lastSwitch < cooldownMs) return null
    pending = null
    lastSwitch = nowMs
    return cand
  }

  companion object {
    const val DWELL_MS = 800L
    const val COOLDOWN_MS = 1500L

    /** Faced when the gaze is within this share of the screen's angular radius of its centre. */
    const val ENTER_RATIO = 0.75f

    /** A screen takes over from the focused one only when this much more central, in radii. */
    const val SWITCH_MARGIN = 0.25f

    /** Angle between two looks, degrees. */
    fun angleDeg(yaw1: Float, pitch1: Float, yaw2: Float, pitch2: Float): Float {
      val a = PostureLayout.direction(yaw1, pitch1)
      val b = PostureLayout.direction(yaw2, pitch2)
      return Math.toDegrees(acos(a.dot(b).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun ratio(yaw: Float, pitch: Float, t: GazeTarget): Float =
        angleDeg(yaw, pitch, t.yawDeg, t.pitchDeg) / t.radiusDeg.coerceAtLeast(1f)

    /** The screen the gaze is on (inside [ENTER_RATIO]) with the focused one favoured by [SWITCH_MARGIN]; null for none. */
    fun candidate(yaw: Float, pitch: Float, targets: List<GazeTarget>, focusedId: String?): String? {
      var best: GazeTarget? = null
      var bestRatio = Float.MAX_VALUE
      var focusedRatio = Float.MAX_VALUE
      for (t in targets) {
        val r = ratio(yaw, pitch, t)
        if (t.id == focusedId) focusedRatio = r
        if (r <= ENTER_RATIO && r < bestRatio) {
          best = t
          bestRatio = r
        }
      }
      val b = best ?: return null
      // Keep the focused screen while it is still faced and the challenger is not clearly more central.
      if (b.id != focusedId && focusedRatio <= ENTER_RATIO && bestRatio + SWITCH_MARGIN > focusedRatio) return focusedId
      return b.id
    }

    /** The screen the head faces right now (inside its edge), for the console's "Looking at". */
    fun faced(yaw: Float, pitch: Float, targets: List<GazeTarget>): String? =
        targets.filter { ratio(yaw, pitch, it) <= 1f }.minByOrNull { ratio(yaw, pitch, it) }?.id
  }
}

/** Pointing at a panel (any of our panels' pointer events) keeps look-to-focus quiet for [windowMs]. */
class PointerGate(private val clock: () -> Long, private val windowMs: Long = WINDOW_MS) {
  @Volatile private var last = Long.MIN_VALUE / 2

  fun touch() {
    last = clock()
  }

  fun recent(): Boolean = clock() - last < windowMs

  companion object {
    const val WINDOW_MS = 1500L
  }
}
