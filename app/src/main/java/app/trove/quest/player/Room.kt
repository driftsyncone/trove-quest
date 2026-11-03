package app.trove.quest.player

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Lut
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene

/**
 * What is behind flat/3D screens: the black void (passthrough off) or the room, clear or darkened.
 * 100 % darkening looks the same as [OFF], so it has no entry of its own.
 */
enum class RoomView(val id: String, val label: String, val passthrough: Boolean, val dim: Float) {
  OFF("off", "Off", false, 1f),
  CLEAR("clear", "Room", true, 0f),
  DIM50("dim50", "Room, 50 % darker", true, 0.5f),
  DIM80("dim80", "Room, 80 % darker", true, 0.8f);

  companion object {
    fun fromId(id: String?): RoomView = entries.firstOrNull { it.id == id } ?: OFF

    /**
     * Spheres (180/360/fisheye) fill the view and keep passthrough off. With screens open the View →
     * Behind choice decides; with only the wall, the wall's own Room toggle ([onWall], off by default).
     */
    fun passthroughOn(view: RoomView, screensOpen: Boolean, sphereActive: Boolean, onWall: Boolean = false): Boolean =
        when {
          sphereActive -> false
          screensOpen -> view.passthrough
          else -> onWall
        }
  }
}

/**
 * The room choice (control panel, View) for every flat/3D screen, kept in SharedPreferences like the
 * layout. Passthrough is scene-wide, so [PlayerManager] calls [apply] whenever screens open, close or
 * change shape; darkening is a passthrough colour LUT that scales every colour by 1 - dim.
 */
class Room(private val scene: () -> Scene, private val prefs: SharedPreferences) {
  var view by mutableStateOf(RoomView.fromId(prefs.getString(PREF_KEY, null)))
    private set

  private var screensOpen = false
  private var sphereActive = false

  /**
   * The partner's STOP ALL: show the room, undimmed, whatever [view] says, so the wearer sees where
   * they are. Off again on Resume, which brings back the wearer's own choice.
   */
  var stopOverride = false
    set(v) {
      if (field == v) return
      field = v
      push()
    }

  /** Black Out: passthrough off (pure black) whatever else asks, STOP ALL included until it is lifted. */
  var blackout = false
    set(v) {
      if (field == v) return
      field = v
      push()
    }
  private var applied: Pair<Boolean, Float>? = null
  private var lutSet = false

  /** Wall header toggle: the room behind the library when no screen is open (darkened like [view]). */
  var onWall by mutableStateOf(prefs.getBoolean(WALL_PREF_KEY, false))
    private set

  fun toggleWall() {
    onWall = !onWall
    prefs.edit().putBoolean(WALL_PREF_KEY, onWall).apply()
    push()
  }

  fun select(v: RoomView) {
    view = v
    prefs.edit().putString(PREF_KEY, v.id).apply()
    push()
  }

  fun apply(screensOpen: Boolean, sphereActive: Boolean) {
    this.screensOpen = screensOpen
    this.sphereActive = sphereActive
    push()
  }

  private fun push() {
    val on = !blackout && (stopOverride || RoomView.passthroughOn(view, screensOpen, sphereActive, onWall))
    val dim = if (on && !stopOverride) view.dim else 0f
    if (applied == on to dim) return
    applied = on to dim
    val s = scene()
    // Only touch the LUT when darkening or to clear one we set (never while passthrough was never on).
    if (dim > 0f) {
      s.setPassthroughLUT(Lut.fromScale(Vector3(1f - dim)))
      lutSet = true
    } else if (lutSet) {
      s.setPassthroughLUT(null)
      lutSet = false
    }
    s.enablePassthrough(on)
  }

  companion object {
    const val PREF_KEY = "room_view"
    const val WALL_PREF_KEY = "room_on_wall"
  }
}
