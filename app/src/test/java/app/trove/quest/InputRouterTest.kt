package app.trove.quest

import app.trove.quest.drag.PanelRect
import app.trove.quest.drag.V3
import app.trove.quest.input.Action
import app.trove.quest.input.ActionSink
import app.trove.quest.input.ControlScheme
import app.trove.quest.input.Fired
import app.trove.quest.input.Hand
import app.trove.quest.input.HandInput
import app.trove.quest.input.LegendTimer
import app.trove.quest.input.PointerRegion
import app.trove.quest.input.PointerTargets
import app.trove.quest.input.RouterCore
import app.trove.quest.input.Row
import app.trove.quest.input.RowStack
import app.trove.quest.input.Snapshot
import app.trove.quest.input.Target
import app.trove.quest.input.run
import app.trove.quest.player.AppVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The router's brain with fake frames and pure state: no Spatial SDK. */
class InputRouterTest {
  private val none = HandInput()
  private val wall = Snapshot()
  private val video = Snapshot(screens = 1, libraryVisible = false)
  private val storm = Snapshot(storm = true, libraryVisible = false)

  private var now = 1_000L

  /** Feeds [frames] 16 ms apart; returns the actions fired. */
  private fun RouterCore.feed(s: Snapshot, left: HandInput = none, right: HandInput = none, frames: Int = 1): List<Fired> {
    val out = mutableListOf<Fired>()
    repeat(frames) {
      out += update(now, left, right, s).fired
      now += 16
    }
    return out
  }

  private fun actions(f: List<Fired>) = f.map { it.action }

  // --- context resolution -----------------------------------------------------------------------

  @Test
  fun contextFollowsTheAppState() {
    assertEquals("Library", ControlScheme.contextOf(wall).title)
    assertEquals("Video", ControlScheme.contextOf(video).title)
    assertEquals("Storm", ControlScheme.contextOf(storm).title)
    assertEquals("Holding a panel", ControlScheme.contextOf(video.copy(holding = Hand.RIGHT)).title)
    assertEquals("Black Out", ControlScheme.contextOf(video.copy(blackout = true, holding = Hand.RIGHT)).title)
    assertEquals("Log in", ControlScheme.contextOf(wall.copy(loggedIn = false, blackout = true)).title)
  }

  // --- face buttons -----------------------------------------------------------------------------

  @Test
  fun aPressedAndReleasedPlaysAndPauses_xDoesTheSame() {
    val r = RouterCore()
    r.feed(video, right = HandInput(primary = true), frames = 3)
    assertEquals(listOf(Action.TOGGLE_PLAY), actions(r.feed(video, right = none)))
    r.feed(video, left = HandInput(primary = true), frames = 3)
    assertEquals(listOf(Action.TOGGLE_PLAY), actions(r.feed(video, left = none)))
  }

  @Test
  fun longAPausesEverythingWhileHeldAndNothingOnRelease() {
    val r = RouterCore()
    val held = r.feed(video, right = HandInput(primary = true), frames = 60) // ~1 s
    assertEquals(listOf(Action.PAUSE_ALL), actions(held))
    assertTrue(r.feed(video, right = none).isEmpty())
  }

  @Test
  fun aInStormWithoutScreensPausesOrPlaysAll_onTheWallNothing() {
    val r = RouterCore()
    r.feed(storm, right = HandInput(primary = true), frames = 2)
    assertEquals(listOf(Action.STORM_TOGGLE_PLAY), actions(r.feed(storm)))
    r.feed(wall, right = HandInput(primary = true), frames = 2)
    assertTrue(r.feed(wall).isEmpty())
  }

  @Test
  fun shortBGoesBackThroughTheStack_yIsTheSame() {
    val r = RouterCore()
    val sort = Row(Row.Kind.SORT, Row.WALL)
    val s = wall.copy(rows = listOf(sort), screens = 1)
    r.feed(s, right = HandInput(back = true), frames = 2)
    val f = r.feed(s)
    assertEquals(listOf(Action.CLOSE_ROW), actions(f))
    assertEquals(sort, f.single().row)
    r.feed(s.copy(rows = emptyList()), left = HandInput(back = true), frames = 2)
    assertEquals(listOf(Action.HIDE_LIBRARY), actions(r.feed(s.copy(rows = emptyList()))))
  }

  @Test
  fun longBGoesHomeAtOnceAndDoesNotRepeatOnRelease() {
    val r = RouterCore()
    val s = video.copy(rows = listOf(Row(Row.Kind.VIEW, "screen-1")))
    val held = r.feed(s, right = HandInput(back = true), frames = 60)
    assertEquals(listOf(Action.HOME), actions(held))
    assertTrue(r.feed(s).isEmpty())
  }

  @Test
  fun bWhileHoldingPutsBack_menuDoesNothingThen() {
    val r = RouterCore()
    val s = video.copy(holding = Hand.RIGHT)
    r.feed(s, right = HandInput(back = true), frames = 2)
    val f = r.feed(s)
    assertEquals(listOf(Action.PUT_BACK), actions(f))
    assertEquals(Hand.RIGHT, f.single().hand)
    r.feed(s, left = HandInput(menu = true), frames = 3)
    assertTrue(r.feed(s).isEmpty())
  }

  @Test
  fun menuTogglesTheLibraryOnTheReleaseAndOnlyOnTheLeft() {
    val r = RouterCore()
    assertTrue(r.feed(video, left = HandInput(menu = true), frames = 3).isEmpty())
    assertEquals(listOf(Action.TOGGLE_LIBRARY), actions(r.feed(video)))
    assertTrue(r.feed(video, right = HandInput(menu = true), frames = 3).isEmpty())
    assertTrue(r.feed(video).isEmpty())
    r.feed(wall, left = HandInput(menu = true), frames = 3)
    assertTrue(r.feed(wall).isEmpty()) // nothing behind the library
  }

  @Test
  fun heldMenuTogglesTheControlsOnceAndNotTheLibrary() {
    val r = RouterCore()
    val f = r.feed(video, left = HandInput(menu = true), frames = 80) // 80 x 16 ms > 0.8 s
    assertEquals(listOf(Action.TOGGLE_CONTROLS), actions(f))
    assertTrue(r.feed(video).isEmpty()) // the release after a long press fires nothing
    // Storm has no control panels.
    r.feed(storm, left = HandInput(menu = true), frames = 80)
    assertTrue(r.feed(storm).isEmpty())
  }

  @Test
  fun bClosesTheVideoWhateverTheControlsAre() {
    val hidden = video.copy(controlsHidden = true)
    assertEquals(Action.CLOSE_SCREEN, ControlScheme.back(hidden))
    assertEquals(Action.CLOSE_SCREEN, ControlScheme.back(video))
    // Shown library over a video: B still hides the library first.
    assertEquals(Action.HIDE_LIBRARY, ControlScheme.back(hidden.copy(libraryVisible = true)))
    assertEquals(Action.EXIT_STORM, ControlScheme.back(storm.copy(controlsHidden = true)))
    assertEquals(Action.SHOW_LIBRARY, ControlScheme.backLong(hidden))
    assertEquals(Action.HOME, ControlScheme.backLong(storm))
  }

  // --- stick ------------------------------------------------------------------------------------

  @Test
  fun stickSeeksAndSetsVolumeOnAVideo_firstDirectionOwnsThePush() {
    val r = RouterCore()
    assertEquals(listOf(Action.SEEK_FORWARD), actions(r.feed(video, right = HandInput(right = true))))
    // The Quest arcs a sideways push into down + right: still the seek's push, no volume change.
    assertTrue(r.feed(video, right = HandInput(right = true, down = true), frames = 5).isEmpty())
    r.feed(video, right = none)
    assertEquals(listOf(Action.VOLUME_UP), actions(r.feed(video, left = HandInput(up = true))))
  }

  @Test
  fun heldStickRepeatsAndTheStepGrows() {
    val r = RouterCore()
    val f = r.feed(video, right = HandInput(left = true), frames = 200) // ~3.2 s
    assertTrue(f.size >= 8)
    assertTrue(f.all { it.action == Action.SEEK_BACK })
    assertEquals(1, f.first().count)
    assertEquals(ControlScheme.SEEK_MS, ControlScheme.seekMs(f.first().count))
    assertEquals(ControlScheme.SEEK_BIG_MS, ControlScheme.seekMs(f.last().count))
  }

  @Test
  fun stickOnAScrollingPanelIsTheSdksAndTheRouterStaysOut() {
    val r = RouterCore()
    val onPanel = video.copy(libraryVisible = true, pointer = mapOf(Hand.RIGHT to Target.PANEL))
    assertTrue(r.feed(onPanel, right = HandInput(right = true, up = true)).isEmpty())
    r.feed(onPanel, right = none)
    // The other hand points at nothing: its stick seeks.
    assertEquals(listOf(Action.SEEK_BACK), actions(r.feed(onPanel, left = HandInput(left = true))))
  }

  // --- "Stick: Suction" (VacuGlide panel) ---------------------------------------------------------

  private val suction = video.copy(stickSuction = true)

  @Test
  fun stickSuctionHoldsTighterUpAndLooserDown_releasedWhenCentred() {
    val r = RouterCore()
    val up = r.feed(suction, right = HandInput(up = true))
    assertEquals(listOf(Action.SUCTION_TIGHTER), actions(up))
    assertEquals(Hand.RIGHT, up.single().hand)
    // Held: no repeats, no volume steps, however long.
    assertTrue(r.feed(suction, right = HandInput(up = true), frames = 400).isEmpty())
    assertEquals(listOf(Action.SUCTION_RELEASE), actions(r.feed(suction, right = none)))
    assertEquals(listOf(Action.SUCTION_LOOSER), actions(r.feed(suction, left = HandInput(down = true))))
    assertEquals(listOf(Action.SUCTION_RELEASE), actions(r.feed(suction, left = none)))
    assertTrue("nothing more once let go", r.feed(suction, frames = 10).isEmpty())
  }

  @Test
  fun stickSuctionLeavesSeekAlone_andASidewaysArcNeverOpensAValve() {
    val r = RouterCore()
    assertEquals(listOf(Action.SEEK_FORWARD), actions(r.feed(suction, right = HandInput(right = true))))
    assertTrue(r.feed(suction, right = HandInput(right = true, up = true), frames = 5).isEmpty())
    assertTrue(r.feed(suction, right = none).isEmpty())
    // Pushed up, then arcing sideways while still up: still the valve's push.
    assertEquals(listOf(Action.SUCTION_TIGHTER), actions(r.feed(suction, right = HandInput(up = true))))
    assertTrue(r.feed(suction, right = HandInput(up = true, left = true), frames = 5).isEmpty())
    // Up let go but still left: the push ends for the valve.
    assertEquals(listOf(Action.SUCTION_RELEASE), actions(r.feed(suction, right = HandInput(left = true))))
  }

  @Test
  fun stickSuctionLetsGoWhenTheRuleGoes() {
    // (label, the snapshot the next frame sees while the stick is still pushed up)
    val gone =
        listOf(
            "toggle off / toy gone" to suction.copy(stickSuction = false),
            "pointing at a scrolling panel" to suction.copy(pointer = mapOf(Hand.RIGHT to Target.PANEL)),
            "Black Out" to suction.copy(blackout = true),
            "Storm opened" to suction.copy(storm = true),
            "grabbed a panel" to suction.copy(holding = Hand.LEFT),
            "last screen closed" to suction.copy(screens = 0),
        )
    for ((label, s) in gone) {
      val r = RouterCore()
      assertEquals(label, listOf(Action.SUCTION_TIGHTER), actions(r.feed(suction, right = HandInput(up = true))))
      val f = r.feed(s, right = HandInput(up = true))
      assertEquals(label, Action.SUCTION_RELEASE, f.first().action)
      assertFalse(label, f.any { ControlScheme.isSuction(it.action) })
    }
  }

  @Test
  fun stickIsVolumeWheneverSuctionWouldNotWork() {
    assertEquals(Action.VOLUME_UP, ControlScheme.onStick(video, Hand.RIGHT, app.trove.quest.input.Dir.UP))
    assertEquals(Action.VOLUME_UP, ControlScheme.onStick(storm.copy(stickSuction = true), Hand.RIGHT, app.trove.quest.input.Dir.UP))
    assertEquals(Action.SUCTION_LOOSER, ControlScheme.onStick(suction, Hand.LEFT, app.trove.quest.input.Dir.DOWN))
    assertEquals(Action.NONE, ControlScheme.onStick(suction.copy(pointer = mapOf(Hand.LEFT to Target.PANEL)), Hand.LEFT, app.trove.quest.input.Dir.DOWN))
    // Library over the video: the stick still drives the screen (pointing at nothing), so the valves too.
    assertEquals(Action.SUCTION_TIGHTER, ControlScheme.onStick(suction.copy(libraryVisible = true), Hand.RIGHT, app.trove.quest.input.Dir.UP))
  }

  @Test
  fun legendSaysWhatTheStickUpDownDoes() {
    fun ud(s: Snapshot) = ControlScheme.legend(s, Hand.RIGHT).first { it.input == "Stick ↑↓" }.text
    assertEquals("Volume", ud(video))
    assertEquals("Hold: suction tighter / looser", ud(suction))
    assertEquals("Volume", ud(storm.copy(stickSuction = true)))
    assertEquals("−10 s / +10 s", ControlScheme.legend(suction, Hand.RIGHT).first { it.input == "Stick ←→" }.text)
  }

  @Test
  fun stormRightIsNextClip_leftNothing_upIsVolume() {
    val r = RouterCore()
    assertEquals(listOf(Action.STORM_NEXT_CLIP), actions(r.feed(storm, right = HandInput(right = true))))
    r.feed(storm)
    assertTrue(r.feed(storm, right = HandInput(left = true)).isEmpty())
    r.feed(storm)
    assertEquals(listOf(Action.VOLUME_DOWN), actions(r.feed(storm, right = HandInput(down = true))))
  }

  @Test
  fun stickPressCountsOnlyWithTheStickCentred() {
    val r = RouterCore()
    // Pushed and clicked in the same frame: the Quest's phantom click.
    assertTrue(actions(r.feed(video, right = HandInput(click = true, up = true))).none { it == Action.SHOW_LEGEND })
    r.feed(video, right = none, frames = 30)
    assertEquals(listOf(Action.SHOW_LEGEND), actions(r.feed(video, right = HandInput(click = true))))
    // Held: one press, not one per frame.
    assertTrue(r.feed(video, right = HandInput(click = true), frames = 5).isEmpty())
  }

  @Test
  fun stickPressRightAfterAPushIsIgnored() {
    val r = RouterCore()
    r.feed(video, right = HandInput(up = true))
    r.feed(video, right = none, frames = 3) // 48 ms centred: not enough
    assertTrue(r.feed(video, right = HandInput(click = true)).none { it.action == Action.SHOW_LEGEND })
  }

  // --- holding ----------------------------------------------------------------------------------

  @Test
  fun holdingStickPressTogglesModeOnTheGrippingHandOnly() {
    val r = RouterCore()
    val s = video.copy(holding = Hand.LEFT)
    r.feed(s, frames = 20)
    assertEquals(listOf(Action.TOGGLE_HOLD_MODE), actions(r.feed(s, left = HandInput(click = true))))
    r.feed(s, frames = 20)
    assertTrue(r.feed(s, right = HandInput(click = true)).isEmpty())
  }

  @Test
  fun otherHandsStickResizesWhileHolding_theGrippingSticksAreHoldSystems() {
    val r = RouterCore()
    val s = video.copy(holding = Hand.LEFT)
    val out = r.update(now, none, HandInput(up = true), s)
    assertEquals(Hand.RIGHT, out.sizeFrom)
    assertEquals(1, out.sizeDir)
    assertTrue(out.fired.isEmpty()) // continuous, not a step action
    val down = r.update(now + 16, HandInput(down = true), HandInput(down = true), s)
    assertEquals(-1, down.sizeDir)
    assertTrue(down.fired.none { it.action == Action.HOLD_STICK || it.action == Action.HOLD_SIZE })
    // Nothing held: no resize stick.
    assertEquals(0, r.update(now + 32, none, HandInput(up = true), video).sizeDir)
  }

  @Test
  fun otherHandsStickLeftRightPitchesAfterAShortDelay_andNeverWithResize() {
    val r = RouterCore()
    val s = video.copy(holding = Hand.LEFT)
    var out = r.update(now, none, HandInput(right = true), s)
    assertEquals(0, out.pitchDir) // a flick does nothing yet
    for (i in 1..20) out = r.update(now + i * 16L, none, HandInput(right = true), s)
    assertEquals(1, out.pitchDir)
    assertEquals(0, out.sizeDir)
    assertTrue(out.pitchHeldS > 0f)
    r.update(now + 400, none, none, s)
    for (i in 1..20) out = r.update(now + 400 + i * 16L, none, HandInput(left = true), s)
    assertEquals(-1, out.pitchDir)
    // The thumb arcing up while pushed sideways stays a pitch, not a resize.
    out = r.update(now + 800, none, HandInput(left = true, up = true), s)
    assertEquals(0, out.sizeDir)
    assertEquals(-1, out.pitchDir)
    // Nothing held: no pitch.
    assertEquals(0, r.update(now + 900, none, HandInput(right = true), video).pitchDir)
  }

  // --- Black Out and safety ---------------------------------------------------------------------

  @Test
  fun blackOutIgnoresEverything() {
    val r = RouterCore()
    val s = video.copy(blackout = true)
    val all = HandInput(primary = true, back = true, menu = true, click = true, up = true)
    assertTrue(r.feed(s, left = all, right = all, frames = 80).isEmpty())
    assertTrue(r.feed(s).isEmpty())
    assertEquals(0, r.update(now, all, all, s.copy(holding = Hand.LEFT)).sizeDir)
  }

  @Test
  fun stoppedChangesNoMapping() {
    val a = RouterCore()
    val b = RouterCore()
    val frames = listOf(HandInput(primary = true), none)
    for (h in frames) assertEquals(actions(a.feed(video, right = h)), actions(b.feed(video.copy(stopped = true), right = h)))
  }

  @Test
  fun noButtonEverResumesOrPlaysAllByLongPress() {
    val r = RouterCore()
    for (s in listOf(video, storm, wall, video.copy(holding = Hand.LEFT))) {
      val f = r.feed(s, left = HandInput(primary = true, back = true), right = HandInput(primary = true, back = true), frames = 80)
      assertFalse(f.any { it.action == Action.STORM_TOGGLE_PLAY || it.action == Action.TOGGLE_PLAY })
      r.feed(s)
    }
  }

  // --- hands -----------------------------------------------------------------------------------

  @Test
  fun microgesturesRunThroughTheSameRules() {
    val r = RouterCore()
    val tap = r.microgesture(ControlScheme.Microgesture.TAP, Hand.RIGHT, video)
    assertEquals(listOf(Action.TOGGLE_PLAY), actions(tap))
    assertEquals(listOf(Action.SEEK_FORWARD), actions(r.microgesture(ControlScheme.Microgesture.SWIPE_RIGHT, Hand.LEFT, video)))
    assertEquals(listOf(Action.HIDE_LIBRARY), actions(r.microgesture(ControlScheme.Microgesture.SWIPE_BACK, Hand.LEFT, video.copy(libraryVisible = true))))
    assertTrue(r.microgesture(ControlScheme.Microgesture.TAP, Hand.RIGHT, video.copy(blackout = true)).isEmpty())
  }

  // --- dispatch ---------------------------------------------------------------------------------

  private class Recorder : ActionSink {
    val calls = mutableListOf<String>()

    override fun putBack(hand: Hand) { calls += "putBack $hand" }
    override fun cancelDrag() { calls += "cancelDrag" }
    override fun closeKeyboard() { calls += "closeKeyboard" }
    override fun closeRow(row: Row) { calls += "closeRow ${row.kind}" }
    override fun closeScreen() { calls += "closeScreen" }
    override fun closeDetails() { calls += "closeDetails" }
    override fun closePage() { calls += "closePage" }
    override fun showLibrary() { calls += "showLibrary" }
    override fun hideLibrary() { calls += "hideLibrary" }
    override fun toggleLibrary() { calls += "toggleLibrary" }
    override fun home() { calls += "home" }
    override fun toggleControls() { calls += "toggleControls" }
    override fun showControls() { calls += "showControls" }
    override fun togglePlay() { calls += "togglePlay" }
    override fun stormTogglePlay() { calls += "stormTogglePlay" }
    override fun pauseAll() { calls += "pauseAll" }
    override fun toggleLegend(hand: Hand?) { calls += "legend" }
    override fun toggleHoldMode(hand: Hand) { calls += "holdMode $hand" }
    override fun seek(ms: Long) { calls += "seek $ms" }
    override fun volume(dir: Int) { calls += "volume $dir" }
    override fun stormNextClip() { calls += "nextClip" }
    override fun exitStorm() { calls += "exitStorm" }
    override fun suctionHold(hand: Hand, tighter: Boolean) { calls += "suction $hand ${if (tighter) "tighter" else "looser"}" }
    override fun suctionRelease(hand: Hand) { calls += "suction $hand release" }
  }

  @Test
  fun dispatchMapsEveryActionToTheSink() {
    val sink = Recorder()
    val row = Row(Row.Kind.VIEW, "screen-1")
    for (f in listOf(
        Fired(Action.PUT_BACK, Hand.LEFT), Fired(Action.CANCEL_DRAG, Hand.LEFT), Fired(Action.CLOSE_KEYBOARD, Hand.LEFT),
        Fired(Action.CLOSE_ROW, Hand.LEFT, row = row), Fired(Action.CLOSE_DETAILS, Hand.LEFT), Fired(Action.CLOSE_PAGE, Hand.LEFT), Fired(Action.HIDE_LIBRARY, Hand.LEFT), Fired(Action.CLOSE_SCREEN, Hand.LEFT),
        Fired(Action.SHOW_LIBRARY, Hand.LEFT), Fired(Action.TOGGLE_LIBRARY, Hand.LEFT), Fired(Action.HOME, Hand.LEFT),
        Fired(Action.TOGGLE_PLAY, Hand.LEFT), Fired(Action.STORM_TOGGLE_PLAY, Hand.LEFT), Fired(Action.PAUSE_ALL, Hand.LEFT),
        Fired(Action.SHOW_LEGEND, Hand.LEFT), Fired(Action.TOGGLE_HOLD_MODE, Hand.RIGHT),
        Fired(Action.SEEK_BACK, Hand.LEFT, count = 1), Fired(Action.SEEK_FORWARD, Hand.LEFT, count = 9),
        Fired(Action.VOLUME_UP, Hand.LEFT), Fired(Action.VOLUME_DOWN, Hand.LEFT), Fired(Action.STORM_NEXT_CLIP, Hand.LEFT),
        Fired(Action.TOGGLE_CONTROLS, Hand.LEFT), Fired(Action.SHOW_CONTROLS, Hand.LEFT), Fired(Action.EXIT_STORM, Hand.LEFT),
        Fired(Action.SUCTION_TIGHTER, Hand.RIGHT), Fired(Action.SUCTION_LOOSER, Hand.LEFT), Fired(Action.SUCTION_RELEASE, Hand.LEFT),
        Fired(Action.HOLD_STICK, Hand.LEFT), Fired(Action.HOLD_SIZE, Hand.LEFT), Fired(Action.NONE, Hand.LEFT),
    )) sink.run(f)
    assertEquals(
        listOf(
            "putBack LEFT", "cancelDrag", "closeKeyboard", "closeRow VIEW", "closeDetails", "closePage", "hideLibrary", "closeScreen", "showLibrary",
            "toggleLibrary", "home", "togglePlay", "stormTogglePlay", "pauseAll", "legend", "holdMode RIGHT",
            "seek -10000", "seek 30000", "volume 1", "volume -1", "nextClip", "toggleControls", "showControls", "exitStorm",
            "suction RIGHT tighter", "suction LEFT looser", "suction LEFT release",
        ),
        sink.calls,
    )
  }

  // --- rows, pointer, volume, legend ---------------------------------------------------------------

  @Test
  fun rowStackKeepsTheOrderTheyWereOpenedIn() {
    val st = RowStack()
    val sort = Row(Row.Kind.SORT, Row.WALL)
    val markers = Row(Row.Kind.MARKERS, "screen-2")
    val account = Row(Row.Kind.ACCOUNT, Row.WALL)
    assertEquals(listOf(sort), st.update(listOf(sort)))
    assertEquals(listOf(sort, markers), st.update(listOf(markers, sort)))
    assertEquals(listOf(sort, markers, account), st.update(listOf(account, sort, markers)))
    assertEquals(markers, ControlScheme.rowToClose(Snapshot(rows = listOf(sort, markers), libraryVisible = true)))
    // One closed by its own button: gone; reopened, it is newest again.
    assertEquals(listOf(markers, account), st.update(listOf(account, markers)))
    assertEquals(listOf(markers, account, sort), st.update(listOf(sort, markers, account)))
  }

  private fun rect(z: Float) = PanelRect(V3(0f, 0f, z), V3(1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, 1f), 1f, 0.5f)

  @Test
  fun pointerTargetIsTheNearestThingTheRayHits() {
    val regions = listOf(PointerRegion(Target.VIDEO, rect(3f)), PointerRegion(Target.PANEL, rect(2f)), PointerRegion(Target.CONTROLS, rect(5f)))
    val o = V3(0f, 0f, 0f)
    assertEquals(Target.PANEL, PointerTargets.resolve(o, V3(0f, 0f, 1f), regions))
    assertEquals(Target.VIDEO, PointerTargets.resolve(o, V3(0f, 0f, 1f), regions.filter { it.target != Target.PANEL }))
    assertEquals(Target.NOTHING, PointerTargets.resolve(o, V3(1f, 0f, 0.1f).normalized(), regions))
    assertEquals(Target.NOTHING, PointerTargets.resolve(o, V3(0f, 0f, -1f), regions))
  }

  @Test
  fun appVolumeStepsInTenthsAndStaysInRange() {
    var saved = -1f
    val v = AppVolume(0.95f) { saved = it }
    assertEquals(1.0f, v.level, 0f)
    assertEquals(0.9f, v.step(-1), 0f)
    assertEquals(0.9f, saved, 0f)
    repeat(20) { v.step(-1) }
    assertEquals(0f, v.level, 0f)
    assertEquals(0.1f, v.step(1), 0f)
    assertEquals(10, v.percent)
    assertEquals(1f, AppVolume(Float.NaN).level, 0f)
  }

  @Test
  fun appVolumeSliderSetsTheSameLevelTheStickSteps() {
    val saved = mutableListOf<Float>()
    val v = AppVolume(0.5f) { saved += it }
    assertEquals(0.7f, v.set(0.68f), 0f)
    assertEquals(70, v.percent)
    assertEquals(0.8f, v.step(1), 0f)
    assertEquals(0.8f, v.set(0.8f), 0f)
    assertEquals("a slider dragged onto the same stop saves nothing more", listOf(0.7f, 0.8f), saved)
    assertEquals(1f, v.set(3f), 0f)
    assertEquals(0f, v.set(-1f), 0f)
    assertEquals(0f, v.set(Float.NaN), 0f)
    assertEquals(9, AppVolume.SLIDER_STEPS)
  }

  @Test
  fun legendTimerShowsHidesAndToggles() {
    val t = LegendTimer()
    assertFalse(t.visible(0))
    t.toggle(0)
    assertTrue(t.visible(LegendTimer.SHOW_MS - 1))
    assertFalse(t.visible(LegendTimer.SHOW_MS))
    t.toggle(100) // a second press hides
    assertFalse(t.visible(150))
    t.toggle(200)
    assertTrue(t.visible(300))
    t.show(0, LegendTimer.FIRST_RUN_MS)
    assertTrue(t.visible(10_000))
  }
}
