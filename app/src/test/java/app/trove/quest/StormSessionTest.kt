package app.trove.quest

import app.trove.quest.storm.LoopSlot
import app.trove.quest.storm.PanelSlot
import app.trove.quest.storm.Storm
import app.trove.quest.storm.StormCounts
import app.trove.quest.storm.StormSlots
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StormSessionTest {
  private class FakeTile(val slot: Int) {
    var closed = false
  }

  /** The manager's structure with fakes: slots + one loop + one panel, driven by the same transitions. */
  private class Harness {
    val scope = TestScope(UnconfinedTestDispatcher())
    val allTiles = mutableListOf<FakeTile>()
    val slots = StormSlots(create = { FakeTile(it).also(allTiles::add) }, dispose = { check(!it.closed) { "closed twice" }; it.closed = true })
    val loop = LoopSlot(scope)
    var panelsOpen = 0
    val panel = PanelSlot(open = { panelsOpen++ }, close = { panelsOpen-- })
    var active = false
    var planned = 0

    // StormManager.start (the pool is ready) / applyPlan(rebuild) / applyPlan(resize) / close
    fun start(count: Int) {
      if (active) slots.closeAll()
      panel.ensureOpen()
      active = true
      planned = count
      slots.rebuild(count)
      loop.ensure { while (true) delay(100) }
    }

    fun rebuild(count: Int) { if (active) { planned = count; slots.rebuild(count) } }

    fun resize(count: Int) { if (active) { planned = count; slots.resize(count) } }

    fun swap(k: Int) { if (active && k in 1 until slots.items.size) { val t = slots.items[0]; slots.items[0] = slots.items[k]; slots.items[k] = t } }

    fun close() {
      loop.stop()
      slots.closeAll()
      panel.ensureClosed()
      active = false
      planned = 0
    }

    fun loopsRunning() = scope.coroutineContext[Job]!!.children.count { it.isActive }

    fun counts(): StormCounts {
      val live = allTiles.count { !it.closed }
      return StormCounts(active, planned, slots.items.size, slots.live, live, live, loopsRunning(), panelsOpen)
    }

    fun assertSingle(what: String) {
      val c = counts()
      assertTrue("$what: ${c.problems()} $c", c.problems().isEmpty())
      assertEquals("$what: listed tiles are the live ones", slots.items.toSet(), allTiles.filter { !it.closed }.toSet())
    }
  }

  @Test
  fun startTwiceKeepsOneLoopOnePanelAndOnlyThePlannedTiles() {
    val h = Harness()
    h.start(9)
    h.assertSingle("first")
    h.start(9)
    h.assertSingle("re-storm")
    assertEquals(1, h.panel.opens)
    assertEquals(18, h.allTiles.size) // all 9 old closed before/with the 9 new
    assertEquals(9, h.allTiles.count { !it.closed })
    assertEquals(1, h.loopsRunning())
    h.scope.coroutineContext[Job]!!.cancel()
  }

  @Test
  fun closeLeavesNothingAndCanRepeat() {
    val h = Harness()
    h.start(6)
    h.close()
    h.close()
    h.assertSingle("closed")
    assertEquals(0, h.allTiles.count { !it.closed })
    assertEquals(0, h.panelsOpen)
    assertEquals(0, h.loopsRunning())
  }

  @Test
  fun loopEnsureDoesNotStackAndRestartReplaces() {
    val h = Harness()
    assertTrue(h.loop.ensure { while (true) delay(10) })
    assertFalse(h.loop.ensure { while (true) delay(10) })
    assertEquals(1, h.loopsRunning())
    assertTrue(h.loop.ensure(force = true) { while (true) delay(10) })
    assertEquals(1, h.loopsRunning())
    h.loop.stop()
    assertEquals(0, h.loopsRunning())
  }

  @Test
  fun resizeOnlyTouchesTheLastSlots() {
    val h = Harness()
    h.start(9)
    val first = h.slots.items.take(6).toList()
    h.resize(6)
    assertEquals(first, h.slots.items)
    h.resize(12)
    assertEquals(first, h.slots.items.take(6))
    h.assertSingle("resized")
    h.close()
  }

  @Test
  fun rapidAlternationNeverExceedsThePlan() {
    val rnd = Random(7)
    val h = Harness()
    var wasActive = false
    repeat(3000) { i ->
      val what =
          when (rnd.nextInt(8)) {
            0 -> { h.start(listOf(4, 6, 9, 12, 16)[rnd.nextInt(5)]); "start" } // Storm / Re-storm
            1 -> { h.rebuild(listOf(7, 9, 13)[rnd.nextInt(3)]); "layout" }
            2, 3 -> { h.resize(listOf(3, 6, 9, 12, 16)[rnd.nextInt(5)]); "plan" } // Source chip, budget, screens
            4 -> { h.swap(rnd.nextInt(1, 9)); "swap" }
            5 -> { if (rnd.nextInt(4) == 0) h.close(); "close" }
            6 -> { h.start(9); h.start(9); "double start" }
            else -> { if (h.active) h.loop.ensure { while (true) delay(100) }; "tick restart" }
          }
      h.assertSingle("step $i ($what)")
      wasActive = wasActive || h.active
    }
    assertTrue(wasActive)
    h.close()
    h.assertSingle("end")
    assertEquals(h.allTiles.size, h.slots.created)
    assertEquals(h.slots.created, h.slots.disposed)
  }

  @Test
  fun problemsAreSpottedWhenSomethingLeaks() {
    val ok = StormCounts(true, 9, 9, 9, 9, 9, 1, 1)
    assertTrue(ok.problems().isEmpty())
    assertTrue(ok.copy(players = 10).problems().isNotEmpty()) // an extra player
    assertTrue(ok.copy(loops = 2).problems().isNotEmpty()) // a second loop
    assertTrue(ok.copy(controlPanels = 2).problems().isNotEmpty())
    assertTrue(ok.copy(tiles = 10, liveTiles = 10, players = 10, videoPanels = 10).problems().isNotEmpty()) // over the plan
    assertTrue(ok.copy(active = false, planned = 0, tiles = 0, liveTiles = 0, players = 3, videoPanels = 0, loops = 0, controlPanels = 0).problems().isNotEmpty())
  }

  @Test
  fun duplicateTriggersCountOnce() {
    assertTrue(Storm.triggerAccepted(0L, 1_000L)) // the first ever
    assertFalse(Storm.triggerAccepted(1_000L, 1_100L)) // the SDK's second delivery
    assertTrue(Storm.triggerAccepted(1_000L, 1_000L + Storm.TRIGGER_GAP_MS))
  }
}
