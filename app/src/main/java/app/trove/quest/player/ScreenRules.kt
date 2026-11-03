package app.trove.quest.player

import app.trove.quest.hold.HoldRules
import app.trove.quest.api.Stream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

/**
 * Multi-screen rules that need no Spatial SDK or Android types, so they can be unit tested.
 * They mirror the WebXR player (Trove server web/src/xr.ts + xrscreen.ts, README "VR multi-screen").
 */
object MultiScreen {
  const val MAX_SCREENS = 6

  /** How long a screen stays focused before the streams change quality (focus flicks cost nothing). */
  const val FOCUS_DWELL_MS = 1500L

  /** Files bigger than this (pixels) play a transcode while their screen is unfocused. */
  const val LOW_RES_ABOVE = 3840L * 1080L

  /** Stash's 720p transcode; it scales the short side, so 7680x2160 SBS becomes 2560x720. */
  const val LOW_RES = "STANDARD_HD"

  /** Stash needs ~5 s before the first byte of an 8K HEVC transcode: ask for it this far ahead. */
  const val TRANSCODE_LEAD_MS = 6000L

  /** Going back to the original (seekable): start it this far ahead so the swap needs no wait. */
  const val ORIGINAL_LEAD_MS = 1500L

  /** First screen: as in milestone 1. */
  const val FIRST_WIDTH_M = 2.4f
  const val FIRST_DISTANCE_M = 2.2f

  /** A portrait screen is no taller than this: the widths above are for 16:9 (a 9:16 video at 2.4 m would be 4.3 m tall). */
  const val MAX_QUAD_HEIGHT_M = 1.7f

  /** [widthM] kept so a screen of eye aspect [aspect] (width / height) stays within [MAX_QUAD_HEIGHT_M]. */
  fun fitWidth(widthM: Float, aspect: Float): Float =
      if (aspect > 0f && widthM / aspect > MAX_QUAD_HEIGHT_M) maxOf(MAX_QUAD_HEIGHT_M * aspect, HoldRules.SCREEN_WIDTH_M.start) else widthM

  /** Extra screens are smaller and a bit further away, like the WebXR player's. */
  const val EXTRA_WIDTH_M = 1.6f
  const val EXTRA_DISTANCE_M = 2.6f

  /** Big files are worth a transcode while unfocused; small ones play the original anyway. */
  fun needsLowRes(width: Int, height: Int): Boolean = width.toLong() * height.toLong() > LOW_RES_ABOVE

  /**
   * Server-relative path of Stash's 720p transcode starting at [startMs] (through the Trove
   * /media proxy, which strips the API key). Uses the URL from the scene's stream list when there
   * is one, else builds the same path (`/media/scene/<id>/stream.mp4?resolution=STANDARD_HD`).
   */
  fun transcodePath(sceneId: String, streams: List<Stream>?, startMs: Long, resolution: String = LOW_RES): String {
    val listed =
        streams?.firstOrNull { it.mime == "video/mp4" && it.url.contains("resolution=$resolution") }?.url
            ?: "/media/scene/$sceneId/stream.mp4?resolution=$resolution"
    return withStart(listed, startMs)
  }

  /** Sets (or replaces) `start=<seconds, one decimal>` on a transcode URL; 0 drops it. */
  fun withStart(url: String, startMs: Long): String {
    val path = url.substringBefore('?')
    val params = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("start=") }
    val start = startSeconds(startMs)
    val all = if (start > 0.0) params + "start=${fmtSeconds(start)}" else params
    return if (all.isEmpty()) path else path + "?" + all.joinToString("&")
  }

  /** The offset Stash actually starts the transcode at, rounded like the URL (0.1 s). */
  fun startOffsetMs(startMs: Long): Long = Math.round(startSeconds(startMs) * 1000.0)

  private fun startSeconds(ms: Long): Double = Math.round(ms.coerceAtLeast(0) / 100.0) / 10.0

  private fun fmtSeconds(s: Double): String = if (s == Math.floor(s)) s.toLong().toString() else s.toString()

  // ---- placement: an arc around the user ----

  /** A screen seen from the head: [yawDeg] left of straight ahead (negative = right), half its angular width. */
  data class Arc(val yawDeg: Float, val halfWidthDeg: Float)

  fun halfWidthDeg(widthM: Float, distanceM: Float): Float =
      (atan((widthM / 2f) / distanceM.coerceAtLeast(0.1f)) * 180.0 / PI).toFloat()

  /**
   * Yaw for a new screen of [halfWidthDeg]: as close to straight ahead as possible without overlapping
   * [occupied] (plus [gapDeg]), trying left first then right in 5° steps up to [maxDeg]. Null when the
   * whole arc is full.
   */
  fun pickYaw(occupied: List<Arc>, halfWidthDeg: Float, gapDeg: Float = 4f, maxDeg: Float = 150f): Float? {
    var step = 0f
    while (step <= maxDeg) {
      for (yaw in if (step == 0f) listOf(0f) else listOf(step, -step)) {
        if (occupied.all { abs(angleDiff(yaw, it.yawDeg)) >= it.halfWidthDeg + halfWidthDeg + gapDeg }) return yaw
      }
      step += 5f
    }
    return null
  }

  /** Signed difference a - b, wrapped to -180..180. */
  fun angleDiff(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
  }
}
