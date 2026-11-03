package app.trove.quest.storm

/**
 * The Focus centre's scrub bar (README "Storm"): pure rules, unit tested in `ScrubRulesTest`.
 * The bar shows scene time (elapsed / total) whatever the centre plays, a preview or the original.
 */
object ScrubRules {
  /** The bar stays this long after the last ray left the centre tile. */
  const val HIDE_DELAY_MS = 1_500L

  /** The bar panel is this high (m); it sits inside the bottom of the centre tile. */
  const val BAR_H_M = 0.16f

  /** The bar is this fraction of the centre's width, centred ("about 2/3 the width of the player"). */
  const val BAR_WIDTH_FRACTION = 2f / 3f

  /** Gap between the bar and the centre's bottom edge, as a fraction of the centre's height. */
  const val BAR_BOTTOM_MARGIN_FRACTION = 0.04f

  /** Width (m) of the bar for a centre tile [centreW] wide. */
  fun barWidthM(centreW: Float): Float = centreW * BAR_WIDTH_FRACTION

  /** Vertical offset (m, negative = down) of the bar's centre from the centre tile's centre, at scale 1. */
  fun barOffsetY(centreH: Float, barH: Float = BAR_H_M): Float = -(centreH / 2f - centreH * BAR_BOTTOM_MARGIN_FRACTION - barH / 2f)

  /** The bar panel is registered once at this width (m), for the layout's centre width; its scale makes it fit the centre actually there. */
  val REGISTERED_W_M: Float = barWidthM(StormLayout.FOCUS_CENTRE_W_M)

  /**
   * Where the bar goes for a centre that renders [centreW] x [centreH] m: [scale] is the entity scale (relative to the
   * bar's own registered size, NOT to the centre tile's: a tile swapped in from the ring was registered much
   * smaller than the centre and carries a big layout scale, which the bar must not inherit - that was
   * the "twice the window" bar), [offsetY] the bar centre's height below the centre's centre (m, world).
   */
  data class Placement(val scale: Float, val offsetY: Float, val worldW: Float, val worldH: Float)

  fun placement(centreW: Float, centreH: Float): Placement {
    val scale = if (centreW > 0f) centreW / StormLayout.FOCUS_CENTRE_W_M else 1f
    val worldH = BAR_H_M * scale
    return Placement(scale, barOffsetY(centreH, worldH), REGISTERED_W_M * scale, worldH)
  }

  /** How far the bar floats in front of the tile (m), so it never z-fights with the video. */
  const val BAR_LIFT_M = 0.02f

  /** What a seek on the bar has to do for the centre in its current state. */
  enum class SeekStep {
    /** The centre plays the original: just seek. */
    SEEK_ORIGINAL,

    /** Still a preview: play the original from the target time (that is the seek). */
    UPGRADE_AT,

    /** A preview that may not become the original (decoder budget): nothing to seek. */
    NONE,
  }

  fun seekStep(kind: ClipKind, canUpgrade: Boolean): SeekStep =
      when {
        kind == ClipKind.SEEK -> SeekStep.SEEK_ORIGINAL
        canUpgrade -> SeekStep.UPGRADE_AT
        else -> SeekStep.NONE
      }

  /** Scene time (ms) of [fraction] (0..1) along a bar for a scene [durationSec] long. */
  fun fractionToMs(fraction: Float, durationSec: Double): Long {
    if (durationSec <= 0.0 || !fraction.isFinite()) return 0L
    return (fraction.coerceIn(0f, 1f) * durationSec * 1000).toLong()
  }

  /** Where [sceneMs] lies along the bar, 0..1. */
  fun fraction(sceneMs: Long, durationSec: Double): Float =
      if (durationSec <= 0.0) 0f else (sceneMs / (durationSec * 1000)).toFloat().coerceIn(0f, 1f)

  /** A stick step of [deltaMs] from the shown time, kept inside the scene. */
  fun stepTarget(elapsedMs: Long, deltaMs: Long, durationSec: Double): Long =
      (elapsedMs + deltaMs).coerceIn(0L, (durationSec * 1000).toLong().coerceAtLeast(0L))

  /**
   * Scene time (ms) the centre shows now. An original's position is the scene's; a preview's position
   * is in the montage, mapped through [montage] (Stash's segment layout) to the spot in the scene.
   */
  fun elapsedMs(kind: ClipKind, playerPosMs: Long, previewDurationMs: Long, sceneDurationSec: Double, montage: PreviewMontage): Long {
    val pos = playerPosMs.coerceAtLeast(0L)
    if (kind == ClipKind.SEEK) return pos
    return montage.sceneTimeMs(pos, sceneDurationSec, previewDurationMs.coerceAtLeast(0L))
  }
}

/** Hover state of the scrub bar: shown while a ray is on the centre tile and [ScrubRules.HIDE_DELAY_MS] after. */
class ScrubHover {
  private var lastHoverMs = Long.MIN_VALUE

  /** Feed every frame; returns whether the bar shows. */
  fun update(nowMs: Long, hovering: Boolean): Boolean {
    if (hovering) lastHoverMs = nowMs
    return lastHoverMs != Long.MIN_VALUE && (hovering || nowMs - lastHoverMs < ScrubRules.HIDE_DELAY_MS)
  }

  fun reset() {
    lastHoverMs = Long.MIN_VALUE
  }
}
