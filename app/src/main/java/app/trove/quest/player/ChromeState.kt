package app.trove.quest.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which screens show their chrome (control panel; the focus bar is separate, see [PlayerSession]). Pure, so it is unit tested. */
object ChromeRules {
  /**
   * A screen's control panel is up when it is the primary one (the focused screen, or the only one;
   * `ScreenSet` gives sound to exactly that screen) and the wearer has not hidden the controls.
   */
  fun showsControls(isPrimary: Boolean, hidden: Boolean): Boolean = isPrimary && !hidden

  /** Choices of the account panel's "Auto-hide controls" (seconds, 0 = off), in cycling order. */
  val AUTO_HIDE_CHOICES = listOf(0, 15, 30, 60)

  fun nextAutoHide(current: Int): Int {
    val i = AUTO_HIDE_CHOICES.indexOf(current)
    return AUTO_HIDE_CHOICES[(i + 1) % AUTO_HIDE_CHOICES.size]
  }

  fun autoHideLabel(secs: Int): String = if (secs <= 0) "Off" else "$secs s"

  /** True when the controls are up, auto-hide is on and nothing touched them for [secs] seconds. */
  fun autoHideDue(hidden: Boolean, lastTouchMs: Long, nowMs: Long, secs: Int): Boolean =
      !hidden && secs > 0 && nowMs - lastTouchMs >= secs * 1000L
}

/**
 * The wearer's "controls hidden" flag and the optional auto-hide. One flag for the whole layout:
 * hiding hides the focused screen's controls, and focus moving (look-to-focus, trigger, Director)
 * keeps the next screen's controls hidden too until the wearer brings them back (trigger on a
 * screen, Menu, B or the panel's own button). [onChange] runs when [hidden] flips.
 */
class ChromeState(
    autoHideSecs: Int,
    private val saveAutoHide: (Int) -> Unit,
    private val onChange: () -> Unit,
) {
  var hidden by mutableStateOf(false)
    private set

  /** Auto-hide after this many idle seconds; 0 = off (default). */
  var autoHideSecs by mutableIntStateOf(if (autoHideSecs in ChromeRules.AUTO_HIDE_CHOICES) autoHideSecs else 0)
    private set

  private var lastTouchMs = 0L

  fun hide() = set(true)

  fun show(nowMs: Long) {
    lastTouchMs = nowMs
    set(false)
  }

  fun toggle(nowMs: Long) = if (hidden) show(nowMs) else hide()

  /** Any interaction with the controls or the screens (restarts the auto-hide clock). */
  fun touch(nowMs: Long) {
    lastTouchMs = nowMs
  }

  /** Called about once a second while auto-hide is on; true if it hid the controls. */
  fun tick(nowMs: Long): Boolean {
    if (!ChromeRules.autoHideDue(hidden, lastTouchMs, nowMs, autoHideSecs)) return false
    hide()
    return true
  }

  fun cycleAutoHide(nowMs: Long) {
    autoHideSecs = ChromeRules.nextAutoHide(autoHideSecs)
    lastTouchMs = nowMs
    saveAutoHide(autoHideSecs)
  }

  private fun set(v: Boolean) {
    if (hidden == v) return
    hidden = v
    onChange()
  }
}
