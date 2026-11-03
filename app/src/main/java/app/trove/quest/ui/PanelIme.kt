package app.trove.quest.ui

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the Horizon OS system keyboard bound to the focused text field of a Spatial SDK panel.
 *
 * The problem (Quest 3, Horizon OS / Android 14): each panel is a window on its own virtual
 * display. The system only accepts a (re)start of text input from the window that has system
 * focus, and while the keyboard is up that is vrshell's FocusPlaceholderActivity, not our panel.
 * When it closes, focus goes to our activity on display 0, still not the panel; only a click on
 * the panel (the SDK injects it into the panel's display) moves focus there. So when focus moves to
 * another field (ImeAction.Next, or a click while the keyboard was open), Compose's
 * `restartInput` is silently refused, the keyboard stays bound to the previous field's dead
 * InputConnection, and every key logs "setComposingText on inactive InputConnection". Only the
 * first field of a session worked, because the click that focused it had just focused the panel.
 *
 * The fix: when a field gains focus while the panel window lacks system focus, inject a
 * DOWN + CANCEL into a corner of the panel's own display (the same reflection call the SDK uses
 * for controller clicks; CANCEL so nothing is clicked). The window manager then focuses the panel,
 * and on that window focus gain we restart input for the focused field and show the keyboard.
 * Any later window focus gain while a field is focused also restarts input, which repairs the
 * binding after a click on the panel.
 */
class PanelIme(val view: View) {
  private val imm = view.context.getSystemService(InputMethodManager::class.java)

  /** For the debug hooks in TroveActivity. */
  var focusManager: FocusManager? = null

  /** Token of the focused text field, or null. */
  private var focusedField: Any? = null

  /** We asked for system focus for a field; on gaining it, restart input and show the keyboard. */
  private var rebindPending = false

  val editing: Boolean
    get() = focusedField != null

  fun onFieldFocus(field: Any, focused: Boolean) {
    if (focused) {
      focusedField = field
      val hasWindowFocus = view.hasWindowFocus()
      Log.d(TAG, "field focused, windowFocus=$hasWindowFocus")
      if (!hasWindowFocus) {
        // Compose's own restartInput for this field will be refused; get system focus first.
        rebindPending = true
        if (!grabWindowFocus()) {
          // No way to fix the binding now: hide the keyboard rather than let the user type into
          // a dead connection. The next click on the panel focuses it and onWindowFocus rebinds.
          rebindPending = false
          view.post { hide() }
        }
      }
    } else if (focusedField === field) {
      focusedField = null
      rebindPending = false
    }
  }

  fun onWindowFocus(hasFocus: Boolean) {
    Log.d(TAG, "window focus=$hasFocus editing=$editing")
    if (!hasFocus || !editing) return
    restart()
    // Show only when we asked for focus ourselves. After a user click Compose shows the keyboard
    // on the tap; showing it here, between DOWN and UP, would make the SDK drop the UP.
    if (rebindPending) {
      rebindPending = false
      show()
    }
  }

  fun restart() {
    Log.d(TAG, "restartInput windowFocus=${view.hasWindowFocus()}")
    imm.restartInput(view)
  }

  fun show() {
    imm.showSoftInput(view, 0)
  }

  fun hide() {
    imm.hideSoftInputFromWindow(view.windowToken, 0)
  }

  /**
   * Inject DOWN + CANCEL at (2, 2) of this panel's display, like the SDK's PanelDisplay does for
   * controller input, so the window manager gives the panel system focus. Returns false if the
   * hidden APIs aren't reachable.
   */
  fun grabWindowFocus(): Boolean {
    val displayId = view.display?.displayId ?: return false
    return try {
      val im = Class.forName("android.hardware.input.InputManager").getDeclaredMethod("getInstance").invoke(null)!!
      val inject = im.javaClass.getDeclaredMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
      val setDisplayId = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
      val now = SystemClock.uptimeMillis()
      for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
        val ev = MotionEvent.obtain(now, now, action, 2f, 2f, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        setDisplayId.invoke(ev, displayId)
        inject.invoke(im, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
        ev.recycle()
      }
      Log.d(TAG, "asked for system focus on display $displayId")
      true
    } catch (e: Exception) {
      Log.w(TAG, "grabWindowFocus failed", e)
      false
    }
  }

  companion object {
    const val TAG = "TroveIme"

    /** The most recent panel helper; the debug hooks use it. */
    @Volatile var last: PanelIme? = null
  }
}

val LocalPanelIme = staticCompositionLocalOf<PanelIme?> { null }

/** Provides a [PanelIme] for the panel this content is in. Text fields opt in with [panelIme]. */
@Composable
fun ProvidePanelIme(content: @Composable () -> Unit) {
  val view = LocalView.current
  val ime = remember(view) { PanelIme(view) }
  ime.focusManager = LocalFocusManager.current
  DisposableEffect(ime) {
    PanelIme.last = ime
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { ime.onWindowFocus(it) }
    view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    onDispose {
      view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
      if (PanelIme.last === ime) PanelIme.last = null
    }
  }
  CompositionLocalProvider(LocalPanelIme provides ime, content = content)
}

/** Put on every text field in a panel (see [PanelIme]). */
@Composable
fun Modifier.panelIme(): Modifier {
  val ime = LocalPanelIme.current ?: return this
  val token = remember { Any() }
  DisposableEffect(ime, token) { onDispose { ime.onFieldFocus(token, false) } }
  return onFocusChanged { ime.onFieldFocus(token, it.isFocused) }
}
