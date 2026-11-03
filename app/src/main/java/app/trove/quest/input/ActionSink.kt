package app.trove.quest.input

/** What the router can make the app do; the activity implements it (calls players, storm, model, library). */
interface ActionSink {
  /** B / Y while holding a panel: restore where it was picked up. */
  fun putBack(hand: Hand)

  fun cancelDrag()

  fun closeKeyboard()

  fun closeRow(row: Row)

  /** B / Y on a video: closes the focused screen. */
  fun closeScreen()

  fun closeDetails()

  /** B / Y on a performer / studio / tag page: back to its section's grid. */
  fun closePage()

  /** Library on / off in front of the user. */
  fun showLibrary()

  fun hideLibrary()

  /** Menu: shows the library in front of you, or hides it. */
  fun toggleLibrary()

  /** Long Menu: hide the screens' control panels, or bring them back. */
  fun toggleControls()

  /** B / Y with the controls hidden: bring them back. */
  fun showControls()

  /** Every row and the details closed, the library grid in front of you. */
  fun home()

  fun togglePlay()

  /** Storm with no screen: Pause all / Play all. */
  fun stormTogglePlay()

  /** Pauses every screen and Storm. Never plays anything. */
  fun pauseAll()

  fun toggleLegend(hand: Hand?)

  /** Stick press of the gripping hand: distance <-> size. */
  fun toggleHoldMode(hand: Hand)

  /** Seek the focused screen by [ms] (negative = back). */
  fun seek(ms: Long)

  /** App volume one step up (+1) or down (-1). */
  fun volume(dir: Int)

  /** Storm: the next clip on the selected tile, else a shuffle of all. */
  fun stormNextClip()

  /** Storm Focus: seek the centre tile by [ms] (stick ←→ while pointing at it). */
  fun stormSeek(ms: Long) {}

  /** B / Y in Storm: close Storm (the screens stay, else the library comes up). */
  fun exitStorm() {}

  /** Storm: bring back the control panel hidden with its Hide button. */
  fun showStormPanel() {}

  /** "Stick: Suction": [hand]'s stick pushed up (Tighter) or down (Looser); held until [suctionRelease]. */
  fun suctionHold(hand: Hand, tighter: Boolean) {}

  /** [hand]'s stick let go of the valve it held (only that hand's press ends). */
  fun suctionRelease(hand: Hand) {}
}

/** One action the router decided on. [count] numbers repeats of one stick push; [row] is what CLOSE_ROW closes. */
data class Fired(val action: Action, val hand: Hand, val count: Int = 1, val row: Row? = null)

/** Maps an [Action] to the [ActionSink] call (pure, so the dispatch table is unit tested with a fake sink). */
fun ActionSink.run(f: Fired) {
  when (f.action) {
    Action.PUT_BACK -> putBack(f.hand)
    Action.CANCEL_DRAG -> cancelDrag()
    Action.CLOSE_KEYBOARD -> closeKeyboard()
    Action.CLOSE_ROW -> f.row?.let(::closeRow)
    Action.CLOSE_DETAILS -> closeDetails()
    Action.CLOSE_PAGE -> closePage()
    Action.CLOSE_SCREEN -> closeScreen()
    Action.HIDE_LIBRARY -> hideLibrary()
    Action.SHOW_LIBRARY -> showLibrary()
    Action.TOGGLE_LIBRARY -> toggleLibrary()
    Action.TOGGLE_CONTROLS -> toggleControls()
    Action.SHOW_CONTROLS -> showControls()
    Action.HOME -> home()
    Action.TOGGLE_PLAY -> togglePlay()
    Action.STORM_TOGGLE_PLAY -> stormTogglePlay()
    Action.PAUSE_ALL -> pauseAll()
    Action.SHOW_LEGEND -> toggleLegend(f.hand)
    Action.TOGGLE_HOLD_MODE -> toggleHoldMode(f.hand)
    Action.SEEK_BACK -> seek(-ControlScheme.seekMs(f.count))
    Action.SEEK_FORWARD -> seek(ControlScheme.seekMs(f.count))
    Action.VOLUME_UP -> volume(1)
    Action.VOLUME_DOWN -> volume(-1)
    Action.STORM_NEXT_CLIP -> stormNextClip()
    Action.STORM_SEEK_BACK -> stormSeek(-ControlScheme.seekMs(f.count))
    Action.STORM_SEEK_FORWARD -> stormSeek(ControlScheme.seekMs(f.count))
    Action.SHOW_STORM_PANEL -> showStormPanel()
    Action.EXIT_STORM -> exitStorm()
    Action.SUCTION_TIGHTER -> suctionHold(f.hand, tighter = true)
    Action.SUCTION_LOOSER -> suctionHold(f.hand, tighter = false)
    Action.SUCTION_RELEASE -> suctionRelease(f.hand)
    // Continuous, HoldSystem's own; nothing to dispatch.
    Action.HOLD_STICK, Action.HOLD_SIZE, Action.HOLD_PITCH, Action.NONE -> Unit
  }
}
