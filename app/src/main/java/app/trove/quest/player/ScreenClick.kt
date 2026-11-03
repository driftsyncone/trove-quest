package app.trove.quest.player

/**
 * What a trigger click on a video screen does (README "Multi-screen"). No Spatial SDK types, so it
 * can be unit tested.
 *
 * A click on an unfocused screen only moves the focus there (sound + original stream); a second click
 * on the focused one plays / pauses. A lone screen is always focused, so its click toggles at once.
 */
object ScreenClick {
  enum class Action { FOCUS, TOGGLE, NONE }

  /**
   * Two events inside this are one click: the scene object can report a press to more than one
   * listener or frame, and a double toggle would look like nothing happened. Also stops the click that
   * just focused a screen from toggling it.
   */
  const val MIN_GAP_MS = 250L

  /**
   * [gripHeld]: the clicking hand also holds grip, i.e. the trigger is part of a grab (or a grab
   * start), never a click. [sinceLastMs]: time since this screen last acted on a click.
   */
  fun decide(focused: Boolean, gripHeld: Boolean, sinceLastMs: Long = Long.MAX_VALUE): Action =
      when {
        gripHeld -> Action.NONE
        sinceLastMs < MIN_GAP_MS -> Action.NONE
        !focused -> Action.FOCUS
        else -> Action.TOGGLE
      }
}
