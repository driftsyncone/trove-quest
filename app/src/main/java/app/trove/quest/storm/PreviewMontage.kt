package app.trove.quest.storm

import app.trove.quest.api.PreviewConfig

/**
 * Where a position in a scene's preview mp4 lies in the scene itself.
 *
 * Stash builds the preview (pkg/scene/generate/preview.go, v0.31) as `segments` clips of
 * `segmentDuration` seconds, clip i starting at `offset + i * stepSize` in the scene, where
 * `excludeStart` / `excludeEnd` (seconds, or a percentage of the scene like "2%") are cut off the
 * scene's ends: `stepSize = (duration - excludeStart - excludeEnd) / segments`, `offset = excludeStart`.
 * When the excludes eat the whole scene, offset is 0 and the whole duration is used. A scene shorter
 * than `segments * segmentDuration` gets a single clip of the whole scene (preview time = scene time).
 * A segment duration under 0.75 s is generated as 0.75 s.
 */
data class PreviewMontage(
    val segments: Int = 12,
    val segmentDuration: Double = 0.75,
    val excludeStart: String = "0",
    val excludeEnd: String = "0",
) {
  /**
   * Scene time (seconds) of [previewPosSec] in the preview of a scene [sceneDurationSec] long.
   * [previewDurationSec] (the loaded preview's length, optional) corrects the segment length when
   * the encoder made the clips a hair off nominal; ignored when it is not near segments x length.
   */
  fun sceneTime(previewPosSec: Double, sceneDurationSec: Double, previewDurationSec: Double = 0.0): Double {
    if (sceneDurationSec <= 0.0) return 0.0
    val pos = previewPosSec.coerceAtLeast(0.0)
    val n = segments.coerceAtLeast(1)
    // Go compares with the configured (unclamped) length.
    if (sceneDurationSec < segmentDuration * n) return pos.coerceAtMost(sceneDurationSec)
    val nominal = segmentDuration.coerceAtLeast(MIN_SEGMENT_S)
    val segDur =
        if (previewDurationSec > 0.0 && kotlin.math.abs(previewDurationSec / n - nominal) <= nominal * 0.15) previewDurationSec / n else nominal
    val es = exclude(excludeStart, sceneDurationSec)
    val ee = exclude(excludeEnd, sceneDurationSec)
    var span = sceneDurationSec
    var offset = 0.0
    if (sceneDurationSec > es + ee) {
      span = sceneDurationSec - es - ee
      offset = es
    }
    val step = span / n
    val index = Math.floor(pos / segDur).toInt().coerceIn(0, n - 1)
    val within = (pos - index * segDur).coerceIn(0.0, segDur)
    return (offset + index * step + within).coerceIn(0.0, sceneDurationSec)
  }

  /**
   * How long the preview of a scene [sceneDurationSec] long should be (ms), before it is loaded:
   * `segments` clips of the (clamped) segment length, or the whole scene when it is shorter than that.
   * 0 when the scene's length is unknown.
   */
  fun previewLengthMs(sceneDurationSec: Double): Long {
    if (sceneDurationSec <= 0.0) return 0L
    val n = segments.coerceAtLeast(1)
    if (sceneDurationSec < segmentDuration * n) return (sceneDurationSec * 1000).toLong()
    return (n * segmentDuration.coerceAtLeast(MIN_SEGMENT_S) * 1000).toLong()
  }

  /** Same in ms; the player's positions are ms. */
  fun sceneTimeMs(previewPosMs: Long, sceneDurationSec: Double, previewDurationMs: Long = 0L): Long =
      (sceneTime(previewPosMs / 1000.0, sceneDurationSec, previewDurationMs / 1000.0) * 1000).toLong()

  companion object {
    const val MIN_SEGMENT_S = 0.75

    /** Stash's defaults, used until (or unless) the server tells the real settings. */
    val DEFAULT = PreviewMontage()

    fun from(c: PreviewConfig) = PreviewMontage(c.segments, c.segmentDuration, c.excludeStart, c.excludeEnd)

    /** Stash's getExcludeValue: "2%" of the scene, else seconds; unparsable = 0. */
    fun exclude(v: String, sceneDurationSec: Double): Double {
      val s = v.trim()
      if (s.endsWith("%") && s.length > 1) {
        val p = s.dropLast(1).toDoubleOrNull() ?: 0.0
        return p / 100.0 * sceneDurationSec
      }
      return s.toDoubleOrNull() ?: 0.0
    }
  }
}
