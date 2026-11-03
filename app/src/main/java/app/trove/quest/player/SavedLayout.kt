package app.trove.quest.player

import app.trove.quest.api.Card
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One screen of the last layout: the scene, where the screen was (world pose, level), its width,
 * tilt and time. Distance is part of the pose (push/pull moves the screen).
 */
@Serializable
data class SavedScreen(
    val card: Card,
    /** Position (m) and level rotation (quaternion w, x, y, z, yaw only); ignored for spheres. */
    val pos: List<Float>,
    val rot: List<Float>,
    val widthM: Float,
    val timeMs: Long,
    val playing: Boolean = true,
    /** Tilt in degrees, + = clockwise as you look at it (0.2.1; older layouts have none = level). */
    val rollDeg: Float = 0f,
    /** Pitch in degrees, + = facing up and away (posture layouts, 0.3.1; older layouts have none = upright). */
    val pitchDeg: Float = 0f,
)

/** The screens open last time and which one had focus ([focus] = index into [screens]). */
@Serializable data class SavedLayout(val screens: List<SavedScreen>, val focus: Int = 0)

/** SharedPreferences JSON for [SavedLayout]; anything malformed is dropped rather than restored. */
object LayoutCodec {
  const val PREF_KEY = "screen_layout"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A NaN pose must not crash the save; decode() drops such screens.
    allowSpecialFloatingPointValues = true
  }

  fun encode(layout: SavedLayout): String = json.encodeToString(SavedLayout.serializer(), layout)

  /** Null when there is nothing (valid) to restore. */
  fun decode(text: String?): SavedLayout? {
    if (text.isNullOrBlank()) return null
    val raw = try {
      json.decodeFromString(SavedLayout.serializer(), text)
    } catch (e: Exception) {
      return null
    }
    val screens = raw.screens.filter(::valid).take(MultiScreen.MAX_SCREENS)
    if (screens.isEmpty()) return null
    // The focus index points into the unfiltered list; follow it to the same screen.
    val focusCard = raw.screens.getOrNull(raw.focus)
    val focus = screens.indexOfFirst { it === focusCard }.takeIf { it >= 0 } ?: 0
    return SavedLayout(screens, focus)
  }

  private fun valid(s: SavedScreen): Boolean =
      s.card.id.isNotBlank() &&
          s.pos.size == 3 &&
          s.rot.size == 4 &&
          (s.pos + s.rot).all { it.isFinite() } &&
          s.rot.any { it != 0f } &&
          s.widthM.isFinite() &&
          s.widthM in 0.3f..8f &&
          s.rollDeg.isFinite() &&
          s.pitchDeg.isFinite() &&
          s.timeMs >= 0
}
