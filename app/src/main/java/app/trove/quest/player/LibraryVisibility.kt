package app.trove.quest.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the main panel (login / wall, "the library") is shown. Opening a scene hides it so it
 * doesn't block the video; the control panel's Library button toggles it; closing the last
 * screen brings it back. [onChange] applies it to the panel entity (true = shown, re-placed in
 * front of the user).
 */
class LibraryVisibility(private val onChange: (Boolean) -> Unit) {
  var visible by mutableStateOf(true)
    private set

  fun sceneOpened() = set(false)

  fun allClosed() = set(true)

  fun toggle() = set(!visible)

  private fun set(v: Boolean) {
    if (v == visible) return
    visible = v
    onChange(v)
  }
}
