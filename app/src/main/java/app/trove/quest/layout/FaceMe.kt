package app.trove.quest.layout

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "Face me" (wall Screens row, default on, remembered in [PREF_KEY]): every flat screen is kept
 * square to the eyes, its forward axis pointing from the head to its centre, yaw and pitch ([Facing]).
 * Off, screens keep the pitch they have and the other stick tips them by hand (README "Face me").
 */
class FaceMe(private val prefs: SharedPreferences, private val onChange: (Boolean) -> Unit = {}) {
  var enabled by mutableStateOf(prefs.getBoolean(PREF_KEY, true))
    private set

  fun set(on: Boolean) {
    if (on == enabled) return
    enabled = on
    prefs.edit().putBoolean(PREF_KEY, on).apply()
    onChange(on)
  }

  companion object {
    const val PREF_KEY = "face_me"
  }
}
