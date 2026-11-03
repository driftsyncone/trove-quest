package app.trove.quest.input

import app.trove.quest.drag.V3
import kotlin.math.abs

/**
 * The hands-only wearer escape (README "Wearer escape", docs/control-scheme.md section 2b): the same
 * STOP ALL as both grips 2 s, for hand-tracking users, who have no grips. This file is the pure part
 * (no Spatial SDK types, unit tested with synthetic joints); `HandEscapeSystem` reads the joints.
 *
 * The gesture: **both hands up in front of the face, open, fingers up, palms flat facing forward or
 * facing you** (a two-handed "stop" sign), held 2 s. Each frame it must hold:
 *  - both hands tracked with confidence (the glue passes null for a hand otherwise);
 *  - each hand open: index, middle, ring and little finger straight and extended, thumb tip away from
 *    the index tip (so a pinch, a microgesture thumb swipe with the fingers curled, or a fist never counts);
 *  - each hand's fingers point up (within [UP_COS]);
 *  - each palm faces forward or back (the palm plane across the line of sight, within [FACING_COS]);
 *    either sign counts, so it does not depend on the joint orientation convention;
 *  - both palms between chin and forehead height and in front of the head, within arm's reach;
 *  - the two palms apart (both hands collapsed to one spot is a known tracking glitch).
 *
 * All positions are metres in one world space with +Y up (the head comes from the same joint set).
 */
data class FingerJoints(val proximal: V3, val intermediate: V3, val distal: V3, val tip: V3)

/** One hand's joints. */
data class HandJoints(
    val wrist: V3,
    val palm: V3,
    val thumbTip: V3,
    val index: FingerJoints,
    val middle: FingerJoints,
    val ring: FingerJoints,
    val little: FingerJoints,
) {
  val fingers: List<FingerJoints>
    get() = listOf(index, middle, ring, little)
}

/** One frame: each hand null unless tracked with confidence; [head] the head joint, null if not tracked. */
data class HandFrame(val left: HandJoints?, val right: HandJoints?, val head: V3?)

object HandPose {
  /** A finger's tip-to-knuckle distance over its bone lengths: 1 = dead straight. */
  const val STRAIGHT_MIN = 0.85f

  /** Tip-to-wrist over knuckle-to-wrist: a straight finger bent 90° at the knuckle is ~1.3, extended ~1.8. */
  const val EXTENDED_MIN = 1.5f

  /** Thumb tip to index tip, in hand sizes (wrist to middle knuckle, ~9 cm): a pinch is well under. */
  const val THUMB_GAP_MIN = 0.4f

  /** Fingers up: wrist -> middle tip within ~50° of straight up. */
  const val UP_COS = 0.64f

  /** Palm plane across the line of sight: palm normal within ~45° of forward (or of back). */
  const val FACING_COS = 0.7f

  /** Palm height relative to the head joint: from about the chin to just above the forehead. */
  const val MIN_DY_M = -0.35f
  const val MAX_DY_M = 0.25f

  /** Palms within this distance of the head, and their midpoint at least [MIN_AHEAD_M] in front of it (horizontally). */
  const val MAX_REACH_M = 0.8f
  const val MIN_AHEAD_M = 0.15f

  /** The two palms at least this far apart, and not further than two arms' width. */
  const val MIN_APART_M = 0.12f
  const val MAX_APART_M = 1.0f

  fun open(h: HandJoints): Boolean {
    val size = (h.middle.proximal - h.wrist).length
    if (size < 1e-3f) return false
    for (f in h.fingers) {
      val bones = (f.intermediate - f.proximal).length + (f.distal - f.intermediate).length + (f.tip - f.distal).length
      if (bones < 1e-3f) return false
      if ((f.tip - f.proximal).length / bones < STRAIGHT_MIN) return false
      val knuckle = (f.proximal - h.wrist).length
      if (knuckle < 1e-3f || (f.tip - h.wrist).length / knuckle < EXTENDED_MIN) return false
    }
    return (h.thumbTip - h.index.tip).length / size >= THUMB_GAP_MIN
  }

  /** Palm plane normal from the knuckles (sign depends on the hand and the space; callers use |n·x|). */
  fun palmNormal(h: HandJoints): V3 = cross(h.index.proximal - h.wrist, h.little.proximal - h.wrist).normalized(V3(0f, 0f, 0f))

  fun fingersUp(h: HandJoints): Boolean = (h.middle.tip - h.wrist).normalized(V3(0f, 0f, 0f)).y >= UP_COS

  /** The whole two-hand pose for one frame. */
  fun isEscapePose(f: HandFrame): Boolean = explain(f) == null

  /** Why [f] is not the escape pose (for debug logs), or null when it is. */
  fun explain(f: HandFrame): String? {
    val l = f.left ?: return "left not tracked"
    val r = f.right ?: return "right not tracked"
    val head = f.head ?: return "head not tracked"
    val apart = (l.palm - r.palm).length
    if (apart < MIN_APART_M || apart > MAX_APART_M) return "palms apart %.2f m".format(apart)
    val mid = (l.palm + r.palm) * 0.5f
    val ahead = mid - head
    val flat = V3(ahead.x, 0f, ahead.z)
    if (flat.length < MIN_AHEAD_M) return "not in front (%.2f m)".format(flat.length)
    val forward = flat.normalized()
    for ((name, h) in listOf("left" to l, "right" to r)) {
      if (!open(h)) return "$name not open"
      if (!fingersUp(h)) return "$name fingers not up"
      if (abs(palmNormal(h) dot forward) < FACING_COS) return "$name palm not facing"
      val dy = h.palm.y - head.y
      if (dy < MIN_DY_M || dy > MAX_DY_M) return "$name height %.2f m".format(dy)
      if ((h.palm - head).length > MAX_REACH_M) return "$name out of reach"
    }
    return null
  }

  private fun cross(a: V3, b: V3) = V3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
}

/**
 * The hold timer, fed one [HandFrame] (or null: no hands / controllers in use / no joint data) every
 * frame in ANY state, like `BlackoutState.gripTick`: true exactly once when the pose has held for
 * [holdMs] without a break. Any break restarts the count: a frame that is not the pose, a lost hand,
 * a gap between frames over [maxGapMs], or a palm that jumps more than [maxJumpM] in one frame (a
 * tracking glitch). After firing it fires again only after the pose was let go.
 */
class HandEscape(
    private val holdMs: Long = HOLD_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val maxJumpM: Float = MAX_JUMP_M,
) {
  private var heldSince: Long? = null
  private var fired = false
  private var lastMs: Long? = null
  private var lastPalms: Pair<V3, V3>? = null

  fun tick(nowMs: Long, frame: HandFrame?): Boolean {
    val posed = frame != null && HandPose.isEscapePose(frame)
    val gap = lastMs?.let { nowMs - it > maxGapMs || nowMs < it } ?: false
    val palms = if (posed) frame!!.left!!.palm to frame.right!!.palm else null
    val prev = lastPalms
    val jump =
        palms != null &&
            prev != null &&
            ((palms.first - prev.first).length > maxJumpM || (palms.second - prev.second).length > maxJumpM)
    lastMs = nowMs
    lastPalms = palms
    if (!posed || gap || jump) {
      fired = false
      heldSince = if (posed) nowMs else null
      return false
    }
    if (fired) return false
    val since = heldSince ?: nowMs.also { heldSince = it }
    if (nowMs - since < holdMs) return false
    fired = true
    return true
  }

  companion object {
    /** Same as the grips (`BlackoutState.WEARER_HOLD_MS`). */
    const val HOLD_MS = 2000L
    const val MAX_GAP_MS = 250L
    const val MAX_JUMP_M = 0.25f
  }
}
