package app.trove.quest

import app.trove.quest.input.Action
import app.trove.quest.input.Button
import app.trove.quest.input.ControlScheme
import app.trove.quest.input.ControlScheme.Microgesture
import app.trove.quest.input.Context
import app.trove.quest.input.Dir
import app.trove.quest.input.Hand
import app.trove.quest.input.Press
import app.trove.quest.input.PressTimer
import app.trove.quest.input.Row
import app.trove.quest.input.Snapshot
import app.trove.quest.input.StickStepper
import app.trove.quest.input.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSchemeTest {
  private val wall = Snapshot()
  private val video = Snapshot(screens = 1, libraryVisible = false)
  private val storm = Snapshot(storm = true, libraryVisible = false)

  private fun b(s: Snapshot) = ControlScheme.onButton(s, Button.B, Press.SHORT)

  // --- B: the back stack -------------------------------------------------------------------------

  @Test
  fun backWalksTheStackTopFirst() {
    val full =
        Snapshot(
            screens = 2,
            libraryVisible = true,
            detailsOpen = true,
            rows = listOf(Row(Row.Kind.MARKERS, "screen-1"), Row(Row.Kind.SORT, Row.WALL)),
        )
    assertEquals(Action.PUT_BACK, b(full.copy(holding = Hand.LEFT, dragging = true, keyboardOpen = true)))
    assertEquals(Action.CANCEL_DRAG, b(full.copy(dragging = true, keyboardOpen = true)))
    assertEquals(Action.CLOSE_KEYBOARD, b(full.copy(keyboardOpen = true)))
    assertEquals(Action.CLOSE_ROW, b(full))
    assertEquals(Row(Row.Kind.SORT, Row.WALL), ControlScheme.rowToClose(full))
    assertEquals(Action.CLOSE_DETAILS, b(full.copy(rows = emptyList())))
    assertEquals(Action.HIDE_LIBRARY, b(full.copy(rows = emptyList(), detailsOpen = false)))
    assertEquals(Action.CLOSE_SCREEN, b(video))
    assertEquals(Action.NONE, b(wall))
  }

  @Test
  fun backSkipsRowsAndDetailsOfAHiddenLibrary() {
    val s = video.copy(detailsOpen = true, rows = listOf(Row(Row.Kind.MARKERS, "screen-1"), Row(Row.Kind.SORT, Row.WALL)))
    assertEquals(Row(Row.Kind.MARKERS, "screen-1"), ControlScheme.rowToClose(s))
    assertEquals(Action.CLOSE_SCREEN, b(s.copy(rows = listOf(Row(Row.Kind.ACCOUNT, Row.WALL)))))
  }

  @Test
  fun menuBringsAHiddenStormPanelBack() {
    val hidden = storm.copy(stormPanelHidden = true)
    assertEquals(Action.EXIT_STORM, b(hidden)) // B leaves Storm even with its panel hidden
    assertEquals(Action.SHOW_STORM_PANEL, ControlScheme.onButton(hidden, Button.MENU, Press.SHORT))
    assertEquals(Action.HIDE_LIBRARY, b(hidden.copy(libraryVisible = true)))
    assertEquals(Action.TOGGLE_LIBRARY, ControlScheme.onButton(hidden.copy(libraryVisible = true), Button.MENU, Press.SHORT))
  }

  @Test
  fun bExitsStormButHidesTheLibraryOverItFirst() {
    assertEquals(Action.EXIT_STORM, b(storm))
    // The library over Storm: the first B goes back to Storm, the second leaves it.
    assertEquals(Action.HIDE_LIBRARY, b(storm.copy(libraryVisible = true)))
    // Anything above Storm in the stack goes first.
    assertEquals(Action.PUT_BACK, b(storm.copy(holding = Hand.LEFT)))
    assertEquals(Action.CANCEL_DRAG, b(storm.copy(dragging = true)))
    assertEquals(Action.CLOSE_KEYBOARD, b(storm.copy(keyboardOpen = true)))
    assertEquals(Action.CLOSE_ROW, b(storm.copy(libraryVisible = true, rows = listOf(Row(Row.Kind.SORT, Row.WALL)))))
    // Storm next to screens: B still closes Storm, the screens stay.
    assertEquals(Action.EXIT_STORM, b(storm.copy(screens = 2)))
    assertEquals(Action.EXIT_STORM, b(storm.copy(screens = 1, controlsHidden = true)))
    // Black Out: nothing.
    assertEquals(Action.NONE, b(storm.copy(blackout = true)))
    assertEquals("Exit Storm", Action.EXIT_STORM.label)
  }

  @Test
  fun backClosesAVideoOrStormButNeverStopsAnything() {
    assertEquals(Action.CLOSE_SCREEN, b(video))
    assertEquals(Action.EXIT_STORM, b(storm))
    // Browsing over a video: B hides the library, the video stays.
    assertEquals(Action.HIDE_LIBRARY, b(video.copy(libraryVisible = true)))
    for (s in listOf(wall, video, storm, storm.copy(libraryVisible = true))) {
      assertFalse(b(s).name, b(s).name.contains("STOP"))
    }
  }

  @Test
  fun yIsTheSameAsBAndXTheSameAsA() {
    val states = listOf(wall, video, storm, video.copy(holding = Hand.RIGHT), Snapshot(dragging = true), video.copy(detailsOpen = true, libraryVisible = true))
    for (s in states) for (p in Press.entries) {
      assertEquals(ControlScheme.onButton(s, Button.B, p), ControlScheme.onButton(s, Button.Y, p))
      assertEquals(ControlScheme.onButton(s, Button.A, p), ControlScheme.onButton(s, Button.X, p))
    }
  }

  @Test
  fun longBGoesHomeButKeepsTransientMeanings() {
    val deep = video.copy(libraryVisible = true, detailsOpen = true, rows = listOf(Row(Row.Kind.SORT, Row.WALL)))
    assertEquals(Action.HOME, ControlScheme.onButton(deep, Button.B, Press.LONG))
    // Plain video: held B shows / hides the library, nothing closes.
    assertEquals(Action.SHOW_LIBRARY, ControlScheme.onButton(video, Button.B, Press.LONG))
    assertEquals(Action.NONE, ControlScheme.onButton(wall, Button.B, Press.LONG))
    assertEquals(Action.HIDE_LIBRARY, ControlScheme.onButton(video.copy(libraryVisible = true), Button.B, Press.LONG))
    assertEquals(Action.PUT_BACK, ControlScheme.onButton(video.copy(holding = Hand.LEFT), Button.B, Press.LONG))
    assertEquals(Action.CANCEL_DRAG, ControlScheme.onButton(Snapshot(dragging = true), Button.Y, Press.LONG))
  }

  // --- Safety ------------------------------------------------------------------------------------

  @Test
  fun blackOutIgnoresEveryButtonAndStick() {
    val black = video.copy(blackout = true, holding = Hand.RIGHT, rows = listOf(Row(Row.Kind.VIEW, "screen-1")))
    for (btn in Button.entries) for (p in Press.entries) assertEquals(Action.NONE, ControlScheme.onButton(black, btn, p))
    for (h in Hand.entries) for (d in Dir.entries) assertEquals(Action.NONE, ControlScheme.onStick(black, h, d))
    assertEquals(listOf("Both grips 2 s"), ControlScheme.legend(black, Hand.RIGHT).map { it.input })
  }

  @Test
  fun longAOnlyEverPauses() {
    assertEquals(Action.PAUSE_ALL, ControlScheme.onButton(video, Button.A, Press.LONG))
    assertEquals(Action.PAUSE_ALL, ControlScheme.onButton(storm, Button.X, Press.LONG))
    assertEquals(Action.NONE, ControlScheme.onButton(wall, Button.A, Press.LONG))
  }

  @Test
  fun stoppedChangesNoMapping() {
    for (s in listOf(wall, video, storm)) for (btn in Button.entries) for (p in Press.entries) {
      assertEquals(ControlScheme.onButton(s, btn, p), ControlScheme.onButton(s.copy(stopped = true), btn, p))
    }
  }

  @Test
  fun everyLegendEndsWithTheEscape() {
    for (s in listOf(wall, video, storm, video.copy(holding = Hand.RIGHT), Snapshot(dragging = true), Snapshot(loggedIn = false))) {
      for (h in Hand.entries) assertEquals("Both grips 2 s", ControlScheme.legend(s, h).last().input)
    }
  }

  // --- A, Menu, stick press ----------------------------------------------------------------------

  @Test
  fun aPlaysThePrimaryMedia() {
    assertEquals(Action.TOGGLE_PLAY, ControlScheme.onButton(video, Button.A, Press.SHORT))
    assertEquals(Action.TOGGLE_PLAY, ControlScheme.onButton(video.copy(storm = true), Button.A, Press.SHORT))
    assertEquals(Action.STORM_TOGGLE_PLAY, ControlScheme.onButton(storm, Button.A, Press.SHORT))
    assertEquals(Action.NONE, ControlScheme.onButton(wall, Button.A, Press.SHORT))
    assertEquals(Action.NONE, ControlScheme.onButton(video.copy(keyboardOpen = true), Button.A, Press.SHORT))
  }

  @Test
  fun menuTogglesTheLibraryOnlyWhenThereIsSomethingBehindIt() {
    assertEquals(Action.TOGGLE_LIBRARY, ControlScheme.onButton(video, Button.MENU, Press.SHORT))
    assertEquals(Action.TOGGLE_LIBRARY, ControlScheme.onButton(storm.copy(libraryVisible = true), Button.MENU, Press.SHORT))
    assertEquals(Action.NONE, ControlScheme.onButton(wall, Button.MENU, Press.SHORT))
    assertEquals(Action.NONE, ControlScheme.onButton(video.copy(holding = Hand.RIGHT, heldIsWall = true), Button.MENU, Press.SHORT))
  }

  @Test
  fun stickPressIsHelpExceptOnTheGrippingHand() {
    assertEquals(Action.SHOW_LEGEND, ControlScheme.onButton(video, Button.STICK_CLICK, Press.SHORT, Hand.LEFT))
    val held = video.copy(holding = Hand.RIGHT)
    assertEquals(Action.TOGGLE_HOLD_MODE, ControlScheme.onButton(held, Button.STICK_CLICK, Press.SHORT, Hand.RIGHT))
    assertEquals(Action.NONE, ControlScheme.onButton(held, Button.STICK_CLICK, Press.SHORT, Hand.LEFT))
    assertFalse(ControlScheme.stickClickCounts(deflected = true, msSinceDeflected = 1000))
    assertFalse(ControlScheme.stickClickCounts(deflected = false, msSinceDeflected = 50))
    assertTrue(ControlScheme.stickClickCounts(deflected = false, msSinceDeflected = 500))
  }

  // --- Stick -------------------------------------------------------------------------------------

  @Test
  fun stickSeeksAndSetsVolumeUnlessAPanelScrolls() {
    assertEquals(Action.SEEK_BACK, ControlScheme.onStick(video, Hand.RIGHT, Dir.LEFT))
    assertEquals(Action.SEEK_FORWARD, ControlScheme.onStick(video, Hand.LEFT, Dir.RIGHT))
    assertEquals(Action.VOLUME_UP, ControlScheme.onStick(video, Hand.RIGHT, Dir.UP))
    assertEquals(Action.VOLUME_DOWN, ControlScheme.onStick(video.copy(sphere = true), Hand.RIGHT, Dir.DOWN))
    val onWall = video.copy(libraryVisible = true, pointer = mapOf(Hand.RIGHT to Target.PANEL))
    assertEquals(Action.NONE, ControlScheme.onStick(onWall, Hand.RIGHT, Dir.LEFT))
    assertEquals(Action.SEEK_BACK, ControlScheme.onStick(onWall, Hand.LEFT, Dir.LEFT))
    assertEquals(Action.SEEK_FORWARD, ControlScheme.onStick(video.copy(pointer = mapOf(Hand.RIGHT to Target.CONTROLS)), Hand.RIGHT, Dir.RIGHT))
    assertEquals(Action.NONE, ControlScheme.onStick(wall, Hand.RIGHT, Dir.LEFT))
  }

  @Test
  fun stickInStormCutsToTheNextClip() {
    assertEquals(Action.STORM_NEXT_CLIP, ControlScheme.onStick(storm, Hand.RIGHT, Dir.RIGHT))
    assertEquals(Action.NONE, ControlScheme.onStick(storm, Hand.RIGHT, Dir.LEFT))
    val both = storm.copy(screens = 1)
    assertEquals(Action.SEEK_FORWARD, ControlScheme.onStick(both, Hand.RIGHT, Dir.RIGHT))
    assertEquals(Action.STORM_NEXT_CLIP, ControlScheme.onStick(both.copy(pointer = mapOf(Hand.RIGHT to Target.TILE)), Hand.RIGHT, Dir.RIGHT))
  }

  @Test
  fun stickOnTheFocusCentreSeeksIt() {
    val c = storm.copy(pointer = mapOf(Hand.RIGHT to Target.CENTRE))
    assertEquals(Action.STORM_SEEK_BACK, ControlScheme.onStick(c, Hand.RIGHT, Dir.LEFT))
    assertEquals(Action.STORM_SEEK_FORWARD, ControlScheme.onStick(c, Hand.RIGHT, Dir.RIGHT))
    assertEquals(Action.VOLUME_UP, ControlScheme.onStick(c, Hand.RIGHT, Dir.UP))
    // With a screen open the centre still wins over the screen's seek.
    assertEquals(Action.STORM_SEEK_FORWARD, ControlScheme.onStick(c.copy(screens = 1), Hand.RIGHT, Dir.RIGHT))
    // Other tiles keep the next-clip rule.
    assertEquals(Action.STORM_NEXT_CLIP, ControlScheme.onStick(storm.copy(pointer = mapOf(Hand.RIGHT to Target.TILE)), Hand.RIGHT, Dir.RIGHT))
  }

  @Test
  fun holdingGivesTheGrippingStickToTheHoldAndTheOtherStickResizesAndPitches() {
    val held = video.copy(holding = Hand.LEFT)
    for (d in Dir.entries) assertEquals(Action.HOLD_STICK, ControlScheme.onStick(held, Hand.LEFT, d))
    assertEquals(Action.HOLD_SIZE, ControlScheme.onStick(held, Hand.RIGHT, Dir.UP))
    assertEquals(Action.HOLD_SIZE, ControlScheme.onStick(held, Hand.RIGHT, Dir.DOWN))
    assertEquals(Action.HOLD_PITCH, ControlScheme.onStick(held, Hand.RIGHT, Dir.LEFT))
    assertEquals(Action.HOLD_PITCH, ControlScheme.onStick(held, Hand.RIGHT, Dir.RIGHT))
  }

  @Test
  fun seekGrowsWhenHeld() {
    assertEquals(10_000L, ControlScheme.seekMs(1))
    assertEquals(10_000L, ControlScheme.seekMs(6))
    assertEquals(30_000L, ControlScheme.seekMs(7))
  }

  @Test
  fun stepperStepsOnPushThenRepeats() {
    val s = StickStepper(firstRepeatMs = 400, repeatMs = 200)
    assertEquals(StickStepper.Step(Dir.RIGHT, 1), s.update(false, false, false, true, 0))
    assertNull(s.update(false, false, false, true, 300))
    assertEquals(StickStepper.Step(Dir.RIGHT, 2), s.update(false, false, false, true, 400))
    assertNull(s.update(false, false, false, true, 500))
    assertEquals(StickStepper.Step(Dir.RIGHT, 3), s.update(false, false, false, true, 600))
    assertNull(s.update(false, false, false, false, 650))
    assertEquals(StickStepper.Step(Dir.LEFT, 1), s.update(false, false, true, false, 700))
  }

  @Test
  fun stepperKeepsTheFirstDirectionThroughTheArc() {
    // Quest: a sideways push arcs into down + right after a few frames; it must stay a seek.
    val s = StickStepper(firstRepeatMs = 400, repeatMs = 200)
    assertEquals(Dir.RIGHT, s.update(false, false, false, true, 0)?.dir)
    assertNull(s.update(false, true, false, true, 50))
    assertEquals(StickStepper.Step(Dir.RIGHT, 2), s.update(false, true, false, true, 400))
    // Up and down together (first frame) is vertical, down wins.
    val t = StickStepper()
    assertEquals(Dir.DOWN, t.update(true, true, false, false, 0)?.dir)
  }

  // --- Press timer -------------------------------------------------------------------------------

  @Test
  fun pressTimerTellsShortFromLong() {
    val p = PressTimer(longMs = 800)
    assertNull(p.update(true, 0))
    assertNull(p.update(true, 500))
    assertEquals(Press.SHORT, p.update(false, 600))
    assertNull(p.update(false, 700))
    assertNull(p.update(true, 1000))
    assertEquals(0f, p.fill(1100), 0f)
    assertTrue(p.fill(1400) > 0.4f)
    assertEquals(Press.LONG, p.update(true, 1800))
    assertNull(p.update(true, 2500))
    assertNull(p.update(false, 2600)) // a long press reports nothing on release
  }

  // --- Contexts, legend, hands -------------------------------------------------------------------

  @Test
  fun contextFollowsTheTopLayer() {
    assertEquals(Context.LIBRARY, ControlScheme.contextOf(wall))
    assertEquals(Context.VIDEO, ControlScheme.contextOf(video))
    assertEquals(Context.SPHERE, ControlScheme.contextOf(video.copy(sphere = true)))
    assertEquals(Context.STORM, ControlScheme.contextOf(storm))
    assertEquals(Context.DETAILS, ControlScheme.contextOf(video.copy(libraryVisible = true, detailsOpen = true)))
    assertEquals(Context.VIDEO, ControlScheme.contextOf(video.copy(detailsOpen = true)))
    assertEquals(Context.ROW, ControlScheme.contextOf(video.copy(rows = listOf(Row(Row.Kind.VIEW, "screen-1")))))
    assertEquals(Context.HELD, ControlScheme.contextOf(video.copy(holding = Hand.LEFT)))
    assertEquals(Context.BLACKOUT, ControlScheme.contextOf(video.copy(blackout = true, holding = Hand.LEFT)))
    assertEquals(Context.LOGIN, ControlScheme.contextOf(Snapshot(loggedIn = false)))
  }

  @Test
  fun legendForAVideoReadsLikeTheDoc() {
    val right = ControlScheme.legend(video, Hand.RIGHT).associate { it.input to it.text }
    assertEquals("Play / Pause", right["A"])
    assertEquals("Pause everything", right["Hold A"])
    assertEquals("Close video", right["B"])
    assertEquals("Library", right["Hold B"])
    assertEquals("−10 s / +10 s", right["Stick ←→"])
    assertEquals("Volume", right["Stick ↑↓"])
    assertEquals("Button help", right["Press stick"])
    assertNull(right["☰"]) // Menu is on the left controller
    assertEquals("Library", ControlScheme.legend(video, Hand.LEFT).associate { it.input to it.text }["☰"])
  }

  @Test
  fun legendWhileHoldingShowsPutBackAndNoDuplicateLongPress() {
    val l = ControlScheme.legend(video.copy(holding = Hand.RIGHT), Hand.RIGHT).map { it.input }
    assertTrue(l.contains("B"))
    assertFalse(l.contains("Hold B"))
    assertTrue(l.contains("Stick ↑↓"))
  }

  @Test
  fun microgesturesMirrorTheButtons() {
    assertEquals(Button.A to null, ControlScheme.microgesture(Microgesture.TAP, Hand.RIGHT))
    assertEquals(Button.Y to null, ControlScheme.microgesture(Microgesture.SWIPE_BACK, Hand.LEFT))
    assertEquals(null to Dir.LEFT, ControlScheme.microgesture(Microgesture.SWIPE_LEFT, Hand.RIGHT))
    assertEquals(Button.MENU to null, ControlScheme.microgesture(Microgesture.SWIPE_FORWARD, Hand.RIGHT))
  }
}
