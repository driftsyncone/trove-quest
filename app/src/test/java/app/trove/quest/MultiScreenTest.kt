package app.trove.quest

import app.trove.quest.api.Card
import app.trove.quest.api.Stream
import app.trove.quest.player.LayoutCodec
import app.trove.quest.player.MultiScreen
import app.trove.quest.player.SavedLayout
import app.trove.quest.player.SavedScreen
import app.trove.quest.player.ScreenHandle
import app.trove.quest.player.ScreenSet
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A screen that records what the set tells it, and plays "original" until told otherwise. */
private class FakeScreen(
    val name: String,
    var sphere: Boolean = false,
    val big: Boolean = true,
    val log: MutableList<String>? = null,
) : ScreenHandle {
  var audible = false
  var highlighted = false
  var original: Boolean? = null // null = never told
  var hidden = false
  val qualityCalls = mutableListOf<Boolean>()

  override val isSphere: Boolean
    get() = sphere

  override val canDowngrade: Boolean
    get() = big

  override val heavyNow: Boolean
    get() = big && original == true && !hidden

  override fun applyFocus(audible: Boolean, highlighted: Boolean) {
    this.audible = audible
    this.highlighted = highlighted
  }

  override fun applyQuality(original: Boolean) {
    this.original = original
    qualityCalls += original
    log?.add("$name:" + if (original) "original" else "transcode")
  }

  override fun setSuspended(suspended: Boolean) {
    hidden = suspended
  }

  override fun toString() = name
}

class MultiScreenTest {
  private var now = 0L
  private val set = ScreenSet<FakeScreen>(6) { now }

  private fun dwell() {
    now += MultiScreen.FOCUS_DWELL_MS
    assertTrue("quality change due", set.applyQualityIfDue())
  }

  // ---- focus and sound ----

  @Test
  fun aLoneScreenHasSoundAndTheOriginalAndNoHighlight() {
    val a = FakeScreen("a")
    set.add(a)
    assertTrue(a.audible)
    assertFalse(a.highlighted)
    assertEquals(true, a.original)
  }

  @Test
  fun onlyTheFocusedScreenHasSoundAndTheHighlight() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    set.add(a)
    set.add(b) // a new screen takes focus
    assertSame(b, set.focused)
    assertTrue(b.audible && b.highlighted)
    assertFalse(a.audible || a.highlighted)

    set.focus(a) // trigger on a
    assertTrue(a.audible && a.highlighted)
    assertFalse(b.audible || b.highlighted)
  }

  @Test
  fun addingWithoutFocusKeepsTheFocus() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    set.add(a)
    set.add(b, focus = false)
    assertSame(a, set.focused)
    assertFalse(b.audible)
    assertEquals("an unfocused newcomer starts on the transcode", false, b.original)
  }

  @Test
  fun closingTheFocusedScreenFocusesThePreviousOne() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    val c = FakeScreen("c")
    set.add(a)
    set.add(b)
    set.add(c)
    set.focus(a)
    set.focus(c)
    set.remove(c)
    assertSame("most recently focused before c", a, set.focused)
    assertTrue(a.audible)
    set.remove(a)
    assertSame(b, set.focused)
    assertTrue(b.audible)
    assertFalse("alone again: no highlight", b.highlighted)
  }

  // ---- streams ----

  @Test
  fun qualityFollowsFocusAfterTheDwell() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    set.add(a)
    set.add(b)
    assertEquals("new focused screen starts on the original", true, b.original)
    assertEquals("old focus keeps its original until the dwell", true, a.original)
    now += MultiScreen.FOCUS_DWELL_MS - 1
    assertFalse(set.applyQualityIfDue())
    now += 1
    assertTrue(set.applyQualityIfDue())
    assertEquals(false, a.original)
    assertEquals(true, b.original)

    set.focus(a)
    assertEquals("sound moves at once, the stream waits", false, a.original)
    dwell()
    assertEquals(true, a.original)
    assertEquals(false, b.original)
  }

  @Test
  fun downgradesAreAppliedBeforeUpgrades() {
    val log = mutableListOf<String>()
    val a = FakeScreen("a", log = log)
    val b = FakeScreen("b", log = log)
    set.add(a)
    set.add(b)
    dwell()
    log.clear()
    set.focus(a)
    dwell()
    assertEquals("frees the big decoder first", listOf("b:transcode", "a:original"), log)
  }

  @Test
  fun smallFilesAlwaysPlayTheOriginal() {
    val a = FakeScreen("a")
    val small = FakeScreen("small", big = false)
    set.add(small)
    set.add(a)
    dwell()
    assertTrue(set.wantsOriginal(small))
    assertEquals(true, small.original)
  }

  @Test
  fun upgradeWaitsWhileAnotherScreenDecodesABigOriginal() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    set.add(a)
    set.add(b) // a still on its original until the dwell
    assertFalse(set.mayUpgrade(b))
    dwell()
    assertTrue(set.mayUpgrade(b))
  }

  @Test
  fun fullAtSix() {
    repeat(6) { set.add(FakeScreen("s$it")) }
    assertTrue(set.isFull)
  }

  // ---- spheres ----

  @Test
  fun aSphereSuspendsTheOthersAndTheyComeBackWhenItCloses() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    set.add(a)
    set.add(b)
    val dome = FakeScreen("dome", sphere = true)
    set.add(dome, focus = false) // even unfocused, a sphere takes over
    assertSame(dome, set.focused)
    assertTrue(a.hidden && b.hidden)
    assertTrue(dome.audible)
    assertFalse("the only visible screen: no highlight", dome.highlighted)
    assertEquals(true, dome.original)

    set.focus(a) // hidden screens can't be focused
    assertSame(dome, set.focused)

    set.remove(dome)
    assertFalse(a.hidden || b.hidden)
    assertSame("focus goes back to the screen focused before", b, set.focused)
    assertTrue(b.audible)
    assertEquals("resumes on the stream it should play now", false, a.original)
    assertEquals(true, b.original)
  }

  @Test
  fun switchingAScreenToASphereAndBack() {
    val a = FakeScreen("a")
    val b = FakeScreen("b")
    set.add(a)
    set.add(b)
    set.focus(a)
    a.sphere = true
    set.changed(a)
    assertTrue(b.hidden)
    assertFalse(a.hidden)
    a.sphere = false
    set.changed(a)
    assertFalse(b.hidden)
    assertSame(a, set.focused)
    assertTrue(a.highlighted)
  }

  @Test
  fun aSuspendedScreenTurningIntoASphereStaysHidden() {
    val dome = FakeScreen("dome", sphere = true)
    val a = FakeScreen("a")
    set.add(dome)
    set.add(a, focus = false)
    assertTrue(a.hidden)
    a.sphere = true // its details arrived: it is a 180 too
    set.changed(a)
    assertSame(dome, set.focused)
    assertTrue(a.hidden)
  }

  // ---- stream URLs ----

  @Test
  fun bigFilesAreThoseOver3840x1080() {
    assertTrue(MultiScreen.needsLowRes(7680, 2160))
    assertTrue(MultiScreen.needsLowRes(3840, 2160))
    assertFalse(MultiScreen.needsLowRes(3840, 1080))
    assertFalse(MultiScreen.needsLowRes(1920, 1080))
    assertFalse(MultiScreen.needsLowRes(0, 0))
  }

  @Test
  fun transcodePathUsesTheListedStreamAndAddsStart() {
    val streams =
        listOf(
            Stream("Direct", "video/mp4", "/media/scene/42/stream"),
            Stream("1080p", "video/mp4", "/media/scene/42/stream.mp4?resolution=FULL_HD"),
            Stream("720p", "video/mp4", "/media/scene/42/stream.mp4?resolution=STANDARD_HD"),
            Stream("720p webm", "video/webm", "/media/scene/42/stream.webm?resolution=STANDARD_HD"),
        )
    assertEquals(
        "/media/scene/42/stream.mp4?resolution=STANDARD_HD&start=83.4",
        MultiScreen.transcodePath("42", streams, 83_412))
    assertEquals("/media/scene/42/stream.mp4?resolution=STANDARD_HD", MultiScreen.transcodePath("42", streams, 0))
  }

  @Test
  fun transcodePathWithoutDetailsBuildsTheProxyPath() {
    assertEquals("/media/scene/7/stream.mp4?resolution=STANDARD_HD&start=12", MultiScreen.transcodePath("7", null, 12_000))
  }

  @Test
  fun withStartReplacesAnExistingStart() {
    assertEquals("/a.mp4?resolution=X&start=5", MultiScreen.withStart("/a.mp4?start=1&resolution=X", 5000))
    assertEquals("/a.mp4?resolution=X", MultiScreen.withStart("/a.mp4?resolution=X&start=9", 0))
    assertEquals("/a.mp4", MultiScreen.withStart("/a.mp4?start=9", 0))
    assertEquals(83_400, MultiScreen.startOffsetMs(83_412))
  }

  // ---- placement ----

  @Test
  fun firstExtraScreenGoesRightNextToTheFirst() {
    val first = MultiScreen.Arc(0f, MultiScreen.halfWidthDeg(MultiScreen.FIRST_WIDTH_M, MultiScreen.FIRST_DISTANCE_M))
    val half = MultiScreen.halfWidthDeg(MultiScreen.EXTRA_WIDTH_M, MultiScreen.EXTRA_DISTANCE_M)
    val yaw = MultiScreen.pickYaw(listOf(first), half)!!
    assertTrue("left first: $yaw", yaw > 0)
    assertTrue("no overlap", yaw >= first.halfWidthDeg + half + 4f)
    assertTrue("but close", yaw < first.halfWidthDeg + half + 4f + 5f)
  }

  @Test
  fun sixScreensFitOnTheArcWithoutOverlapping() {
    val arcs = mutableListOf(MultiScreen.Arc(0f, MultiScreen.halfWidthDeg(2.4f, 2.2f)))
    val half = MultiScreen.halfWidthDeg(1.6f, 2.6f)
    repeat(5) {
      val yaw = MultiScreen.pickYaw(arcs, half)
      assertNotNull("screen ${it + 2} fits", yaw)
      arcs += MultiScreen.Arc(yaw!!, half)
    }
    for (i in arcs.indices) for (j in i + 1 until arcs.size) {
      val gap = abs(MultiScreen.angleDiff(arcs[i].yawDeg, arcs[j].yawDeg))
      assertTrue("$i/$j overlap", gap >= arcs[i].halfWidthDeg + arcs[j].halfWidthDeg)
    }
    // Alternates sides, left first: three left, two right of the first.
    assertEquals(3, arcs.count { it.yawDeg > 0 })
    assertEquals(2, arcs.count { it.yawDeg < 0 })
  }

  @Test
  fun aFullArcGivesNull() {
    val ring = (0 until 360 step 30).map { MultiScreen.Arc(it.toFloat(), 15f) }
    assertNull(MultiScreen.pickYaw(ring, 10f))
  }

  @Test
  fun angleDiffWraps() {
    assertEquals(-20f, MultiScreen.angleDiff(170f, -170f), 1e-4f)
    assertEquals(20f, MultiScreen.angleDiff(-170f, 170f), 1e-4f)
  }

  // ---- saved layout ----

  private fun saved(id: String, time: Long = 1000) =
      SavedScreen(
          card = Card(id = id, title = "Scene $id", width = 7680, height = 2160, projection = "sbs"),
          pos = listOf(0.5f, 1.6f, 2.2f),
          rot = listOf(1f, 0f, 0f, 0f),
          widthM = 1.6f,
          timeMs = time,
          playing = false,
      )

  @Test
  fun layoutRoundTrips() {
    val layout = SavedLayout(listOf(saved("1"), saved("2", 65_000)), focus = 1)
    val back = LayoutCodec.decode(LayoutCodec.encode(layout))
    assertEquals(layout, back)
  }

  @Test
  fun nothingToRestoreFromGarbageOrEmpty() {
    assertNull(LayoutCodec.decode(null))
    assertNull(LayoutCodec.decode(""))
    assertNull(LayoutCodec.decode("{not json"))
    assertNull(LayoutCodec.decode(LayoutCodec.encode(SavedLayout(emptyList()))))
  }

  @Test
  fun badScreensAreDroppedAndFocusFollowsTheSameScreen() {
    val bad = saved("x").copy(pos = listOf(Float.NaN, 0f, 0f))
    val layout = SavedLayout(listOf(bad, saved("1"), saved("2")), focus = 2)
    val back = LayoutCodec.decode(LayoutCodec.encode(layout))!!
    assertEquals(listOf("1", "2"), back.screens.map { it.card.id })
    assertEquals(1, back.focus)
  }

  @Test
  fun atMostSixScreensAndAnOutOfRangeFocusFallsBackToTheFirst() {
    val layout = SavedLayout((1..8).map { saved("$it") }, focus = 12)
    val back = LayoutCodec.decode(LayoutCodec.encode(layout))!!
    assertEquals(6, back.screens.size)
    assertEquals(0, back.focus)
  }

  @Test
  fun unknownFieldsFromANewerVersionAreIgnored() {
    val text = LayoutCodec.encode(SavedLayout(listOf(saved("1")))).replace("\"focus\"", "\"preset\":\"arc\",\"focus\"")
    assertEquals("1", LayoutCodec.decode(text)!!.screens.single().card.id)
  }

  @Test
  fun portraitScreensAreCappedInHeight() {
    // 16:9 keeps its width; a 9:16 video at 2.4 m would be 4.3 m tall.
    assertEquals(2.4f, MultiScreen.fitWidth(2.4f, 16f / 9f), 0.001f)
    val w = MultiScreen.fitWidth(2.4f, 9f / 16f)
    assertEquals(MultiScreen.MAX_QUAD_HEIGHT_M, w / (9f / 16f), 0.001f)
    assertEquals(2.4f, MultiScreen.fitWidth(2.4f, 0f), 0.001f)
  }
}
