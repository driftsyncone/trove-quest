package app.trove.quest.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * The app-wide volume (control scheme: stick up / down while watching; the Volume sliders on the
 * screen controls and the Storm panel), 0..1 in steps of [STEP], applied to the screen with sound
 * and to Storm; [save] remembers it. Compose state, so the sliders follow the stick. The headset's
 * own volume keys are separate and still work.
 */
class AppVolume(initial: Float, private val save: (Float) -> Unit = {}) {
  var level: Float by mutableFloatStateOf(snap(if (initial.isFinite()) initial else 1f))
    private set

  /** One step louder ([dir] > 0) or quieter; returns the new level. */
  fun step(dir: Int): Float {
    level = snap(level + dir * STEP)
    save(level)
    return level
  }

  /** A slider: straight to [v] (snapped to a step); returns the new level. Saved only when it changed. */
  fun set(v: Float): Float {
    if (!v.isFinite()) return level
    val next = snap(v)
    if (next != level) {
      level = next
      save(level)
    }
    return level
  }

  val percent: Int
    get() = (level * 100).roundToInt()

  companion object {
    const val STEP = 0.1f

    /** Stops between 0 and 1 on a Volume slider (Compose `steps`): every [STEP]. */
    const val SLIDER_STEPS = 9

    fun snap(v: Float): Float = ((v.coerceIn(0f, 1f)) * 10).roundToInt() / 10f
  }
}
