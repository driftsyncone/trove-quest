package app.trove.quest

import app.trove.quest.api.Card
import app.trove.quest.storm.ClipKind
import app.trove.quest.storm.DecoderBudget
import app.trove.quest.storm.SlotMove
import app.trove.quest.storm.SourceMode
import app.trove.quest.storm.StormStereo
import app.trove.quest.storm.Storm
import app.trove.quest.storm.StormLayout
import app.trove.quest.storm.StormLayoutMode
import app.trove.quest.storm.StormPool
import app.trove.quest.storm.StormScheduler
import app.trove.quest.sync.HeadsetStatus
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StormRulesTest {
  private fun card(id: String, w: Int = 1920, h: Int = 1080, codec: String = "h264", dur: Double = 600.0, proj: String = "flat") =
      Card(id = id, title = "Scene $id", duration = dur, width = w, height = h, codec = codec, projection = proj)

  private fun chord(radius: Float, fromDeg: Float, toDeg: Float) =
      2 * radius * sin(Math.toRadians((toDeg - fromDeg) / 2.0)).toFloat()

  // ---- pool ----

  @Test
  fun poolKeepsTilesShapedScenesOnly() {
    val cards =
        listOf(
            card("1"),
            card("2", proj = "sbs", w = 3840, h = 1080), // full SBS: eye is 16:9
            card("3", proj = "mono180"),
            card("8", proj = "fisheye190"),
            card("9", proj = "sbs", w = 1080, h = 1920), // portrait SBS
            card("4", w = 1080, h = 1920), // portrait
            card("5", w = 640, h = 480), // 4:3
            card("6", w = 0, h = 0), // size unknown
            card("1"), // duplicate
            card("7", w = 3840, h = 2160),
        )
    assertEquals(listOf("1", "2", "6", "7"), Storm.filterPool(cards).map { it.id })
  }

  @Test
  fun poolDealsEveryScenePerRoundAndAvoidsOnesOnScreen() {
    val pool = StormPool((1..6).map { card("$it") }, Random(1))
    val seen = mutableSetOf<String>()
    repeat(6) { seen += pool.next(emptySet())!!.id }
    assertEquals(6, seen.size)
    // 2 of 3 on screen: the third comes up, again and again.
    val small = StormPool((1..3).map { card("$it") }, Random(2))
    repeat(10) { assertEquals("3", small.next(setOf("1", "2"))!!.id) }
  }

  @Test
  fun poolWithEverythingOnScreenStillAnswersAndBadOnesLeave() {
    val pool = StormPool(listOf(card("1"), card("2")), Random(3))
    assertTrue(pool.next(setOf("1", "2")) != null)
    pool.markBad("1")
    assertEquals(1, pool.size)
    repeat(5) { assertEquals("2", pool.next(emptySet())!!.id) }
    pool.markBad("2")
    assertNull(pool.next(emptySet()))
    assertNull(StormPool(emptyList(), Random(4)).next(emptySet()))
  }

  @Test
  fun poolPagesCoverSmallQueriesAndSampleBigOnes() {
    assertEquals(listOf(1), Storm.poolPages(0, Random(1)))
    assertEquals(listOf(1), Storm.poolPages(60, Random(1)))
    assertEquals(listOf(1, 2), Storm.poolPages(61, Random(1)))
    val big = Storm.poolPages(6000, Random(1))
    assertEquals(Storm.POOL_MAX_PAGES, big.size)
    assertEquals(big.sorted(), big)
    assertTrue(big.all { it in 1..100 })
    assertEquals(big.size, big.toSet().size)
  }

  // ---- sources ----

  @Test
  fun seekOnlyForSmallH264FilesOtherwisePreview() {
    assertEquals(ClipKind.SEEK, Storm.kindFor(card("1"), SourceMode.SEEKS))
    assertEquals(ClipKind.PREVIEW, Storm.kindFor(card("1"), SourceMode.PREVIEWS))
    assertEquals(ClipKind.PREVIEW, Storm.kindFor(card("2", codec = "hevc"), SourceMode.SEEKS))
    assertEquals(ClipKind.PREVIEW, Storm.kindFor(card("3", w = 3840, h = 2160), SourceMode.SEEKS))
    assertEquals(ClipKind.PREVIEW, Storm.kindFor(card("4", dur = 20.0), SourceMode.SEEKS))
  }

  @Test
  fun clipStartStaysInsideTheSceneAwayFromTheEdges() {
    val rnd = Random(5)
    repeat(200) {
      val start = Storm.clipStartMs(600.0, 10_000, rnd)
      assertTrue(start >= 24_000)
      assertTrue(start + 10_000 <= 600_000 - 24_000)
    }
    assertEquals(0L, Storm.clipStartMs(8.0, 10_000, rnd)) // shorter than a clip
    assertEquals(0L, Storm.clipStartMs(0.0, 10_000, rnd))
  }

  // ---- scheduling ----

  @Test
  fun delaysJitterAroundTheClipLengthWithAFloor() {
    val rnd = Random(6)
    val delays = List(500) { Storm.nextDelayMs(10_000, rnd) }
    assertTrue(delays.all { it in 6_000..14_000 })
    assertTrue("they actually vary", delays.toSet().size > 100)
    assertEquals(Storm.MIN_CLIP_MS, Storm.nextDelayMs(1_000, rnd, jitter = 0.0))
  }

  @Test
  fun firstCutsAreStaggeredAndNothingIsDueEarly() {
    val s = StormScheduler(Random(7))
    s.reset(9, now = 1_000, clipMs = 10_000)
    val d = (0 until 9).map { s.deadline(it) }
    assertTrue("none before 30 % of the shortest clip", d.all { it >= 1_000 + (0.3 * 6_000).toLong() })
    assertTrue(d.all { it <= 1_000 + 14_000 })
    assertTrue("spread out, not one moment", d.max() - d.min() > 2_000)
    assertNull(s.due(1_000))
  }

  @Test
  fun dueHandsOutOneTileAtATimeWithAGapAndTheMostOverdueFirst() {
    val s = StormScheduler(Random(8))
    s.reset(3, now = 0, clipMs = 10_000)
    s.shuffleNow(100)
    assertEquals(0, s.due(100))
    s.switched(0, 100, 10_000)
    assertNull("gap rule", s.due(100 + Storm.MIN_SWITCH_GAP_MS - 1))
    assertEquals(1, s.due(100 + Storm.MIN_SWITCH_GAP_MS))
    s.switched(1, 100 + Storm.MIN_SWITCH_GAP_MS, 10_000)
    assertEquals(2, s.due(100 + 2 * Storm.MIN_SWITCH_GAP_MS))
    s.switched(2, 100 + 2 * Storm.MIN_SWITCH_GAP_MS, 10_000)
    assertNull(s.due(100 + 3 * Storm.MIN_SWITCH_GAP_MS))
  }

  @Test
  fun postponeShiftsEveryDeadline() {
    val s = StormScheduler(Random(9))
    s.reset(4, 0, 10_000)
    val before = (0 until 4).map { s.deadline(it) }
    s.postpone(5_000)
    assertEquals(before.map { it + 5_000 }, (0 until 4).map { s.deadline(it) })
  }

  // ---- decoder budget ----

  @Test
  fun blocksFollowTheReadmeNumbers() {
    // 8K SBS 60 fps is half of what the hardware decodes.
    assertEquals(3_888_000L, DecoderBudget.blocksPerSecond(7680, 2160, 60))
    assertEquals(DecoderBudget.MAX_BLOCKS_PER_S / 2, DecoderBudget.blocksPerSecond(7680, 2160, 60))
  }

  @Test
  fun nineTilesFitAndSixteenAndTwentyFiveAreCappedWithAReason() {
    val pv = DecoderBudget.PREVIEW_BLOCKS
    assertEquals(9, DecoderBudget.plan(9, 0, 0, pv).tiles)
    assertEquals(null, DecoderBudget.plan(9, 0, 0, pv).note())
    val sixteen = DecoderBudget.plan(16, 0, 0, pv)
    assertEquals(14, sixteen.tiles)
    assertEquals(DecoderBudget.Limit.INSTANCES, sixteen.limit)
    assertTrue(sixteen.note()!!.startsWith("14 of 16"))
    assertEquals(14, DecoderBudget.plan(25, 0, 0, pv).tiles)
  }

  @Test
  fun neverMoreThanSixteenInstancesWithScreensIncluded() {
    val pv = DecoderBudget.PREVIEW_BLOCKS
    for (screens in 0..6) for (req in Storm.TILE_COUNTS) {
      val p = DecoderBudget.plan(req, screens, 0, pv)
      assertTrue(p.tiles + screens + DecoderBudget.SPARE_INSTANCES <= DecoderBudget.MAX_INSTANCES)
    }
    assertEquals(8, DecoderBudget.plan(25, 6, 0, pv).tiles)
    assertEquals(0, DecoderBudget.plan(9, 15, 0, pv).tiles)
    assertEquals("No decoder room for tiles: close some screens.", DecoderBudget.plan(9, 15, 0, pv).note())
  }

  @Test
  fun heavyScreensAndSeekTilesEatTheBlockBudget() {
    // An 8K SBS 60 fps original on the focused screen takes half; tiles get 80 % of the whole minus that.
    val screens = DecoderBudget.screenBlocks(listOf(7680 to 2160), focused = 0)
    assertEquals(3_888_000L, screens)
    val seek = DecoderBudget.plan(16, 1, screens, DecoderBudget.SEEK_BLOCKS)
    assertEquals(DecoderBudget.Limit.BLOCKS, seek.limit)
    assertTrue(seek.tiles in 1..7)
    assertTrue(screens + seek.tiles * DecoderBudget.SEEK_BLOCKS <= DecoderBudget.MAX_BLOCKS_PER_S * DecoderBudget.BLOCK_SHARE)
    // Previews barely register (the instance cap binds: 16 - 2 spare - 1 screen).
    assertEquals(13, DecoderBudget.plan(16, 1, screens, DecoderBudget.PREVIEW_BLOCKS).tiles)
  }

  @Test
  fun unfocusedBigScreensCountAs720pTranscodes() {
    val both = DecoderBudget.screenBlocks(listOf(7680 to 2160, 7680 to 2160), focused = 0)
    assertEquals(3_888_000L + DecoderBudget.blocksPerSecond(2560, 720, 30), both)
  }

  // ---- layout ----

  @Test
  fun gridPicksTheObviousShapes() {
    assertEquals(2 to 2, StormLayout.grid(4))
    assertEquals(3 to 3, StormLayout.grid(9))
    assertEquals(4 to 4, StormLayout.grid(16))
    assertEquals(5 to 5, StormLayout.grid(25))
    for (n in 1..25) {
      val (c, r) = StormLayout.grid(n)
      assertTrue("$n fits in ${c}x$r", c * r >= n && c * r - n < c)
    }
  }

  @Test
  fun curvedWallIsOnACylinderCentredAheadAndTilesDontOverlap() {
    for (n in listOf(4, 9, 14, 16)) {
      val poses = StormLayout.poses(n, StormLayoutMode.WALL)
      assertEquals(n, poses.size)
      assertTrue(poses.all { it.horizM == StormLayout.WALL_RADIUS_M })
      assertEquals("centred", 0f, poses.map { it.yawDeg }.average().toFloat(), 0.01f)
      assertEquals("symmetric", poses.maxOf { it.yawDeg }, -poses.minOf { it.yawDeg }, 0.01f)
      // Same row: neighbours are further apart than a tile is wide.
      val (cols, _) = StormLayout.grid(n)
      for (row in poses.chunked(cols)) for ((a, b) in row.zipWithNext()) {
        assertTrue("tiles overlap in a row ($n)", chord(StormLayout.WALL_RADIUS_M, a.yawDeg, b.yawDeg) > a.widthM)
      }
      // Rows are further apart than a tile is high.
      val ys = poses.map { it.dyM }.distinct().sortedDescending()
      for ((a, b) in ys.zipWithNext()) assertTrue(a - b > poses[0].heightM)
      assertTrue("arc stays within the field of view", poses.maxOf { it.yawDeg } < 70f)
    }
  }

  @Test
  fun wallTilesAreSixteenNineAndLastRowIsCentred() {
    val poses = StormLayout.poses(7, StormLayoutMode.WALL)
    val (cols, rows) = StormLayout.grid(7)
    assertTrue(poses.all { abs(it.widthM / it.heightM - 16f / 9f) < 0.001f })
    val last = poses.drop(cols * (rows - 1))
    assertTrue(last.size < cols)
    assertEquals(0f, last.map { it.yawDeg }.average().toFloat(), 0.001f)
  }

  @Test
  fun surroundPutsTilesOnASphereAroundTheHeadWithoutOverlap() {
    for (n in listOf(4, 9, 14)) {
      val poses = StormLayout.poses(n, StormLayoutMode.SURROUND)
      assertEquals(n, poses.size)
      for (p in poses) {
        assertEquals("on a sphere", StormLayout.SURROUND_RADIUS_M, hypot(p.horizM, p.dyM), 0.001f)
        assertTrue(abs(p.yawDeg) < 180f)
      }
    }
    // Round the head: well past the field of view.
    val nine = StormLayout.poses(9, StormLayoutMode.SURROUND)
    assertTrue(nine.maxOf { it.yawDeg } - nine.minOf { it.yawDeg } > 180f)
    // Same-ring neighbours don't overlap.
    val ring = StormLayout.poses(8, StormLayoutMode.SURROUND)
    for ((a, b) in ring.zipWithNext()) assertTrue(chord(StormLayout.SURROUND_RADIUS_M, a.yawDeg, b.yawDeg) > a.widthM)
    // Two rings above 8 tiles: one above eye level, one below.
    val two = StormLayout.poses(12, StormLayoutMode.SURROUND)
    assertTrue(two.any { it.dyM > 0.05f } && two.any { it.dyM < -0.05f })
    assertTrue(StormLayout.poses(0, StormLayoutMode.WALL).isEmpty())
  }

  // ---- partner console ----

  @Test
  fun consoleLabelAndHeadsetMap() {
    assertNull(Storm.syncLabel(false, 9))
    assertEquals("on, 9 tiles", Storm.syncLabel(true, 9))
    assertEquals("on, 1 tile", Storm.syncLabel(true, 1))
    assertEquals(
        mapOf<String, Any>("inOurApp" to true, "storm" to "on, 9 tiles"),
        HeadsetStatus(inOurApp = true, storm = "on, 9 tiles").toMap())
    assertFalse(HeadsetStatus(inOurApp = true).toMap().containsKey("storm"))
  }

  // ---- Focus layout ----

  private data class R(val x: Float, val y: Float, val w: Float, val h: Float)

  private fun rects(ring: Int) =
      StormLayout.focus(ring).map {
        val a = Math.toRadians(it.yawDeg.toDouble())
        R((it.horizM * sin(a)).toFloat(), it.dyM - StormLayout.FOCUS_DY_M, it.widthM, it.heightM)
      }

  @Test
  fun focusHasBigCentreAndNonOverlappingRing() {
    for (ring in Storm.FOCUS_RING_COUNTS + listOf(1, 3, 20)) {
      val r = rects(ring)
      assertEquals(ring + 1, r.size)
      assertEquals(0f, r[0].x, 0.001f)
      assertEquals(0f, r[0].y, 0.001f)
      for (i in 1 until r.size) assertTrue(r[i].w < r[0].w * 0.65f)
      for (i in r.indices) for (j in i + 1 until r.size) {
        val overlapX = abs(r[i].x - r[j].x) < (r[i].w + r[j].w) / 2
        val overlapY = abs(r[i].y - r[j].y) < (r[i].h + r[j].h) / 2
        assertFalse("ring $ring tiles $i/$j overlap", overlapX && overlapY)
      }
      for (p in StormLayout.focus(ring)) assertTrue(abs(p.yawDeg) < 70f)
    }
  }

  @Test
  fun focusCentreIs14mAndRingTilesAreAsBigAsFit() {
    assertEquals(1.4f, StormLayout.FOCUS_CENTRE_W_M, 0.0001f)
    for (ring in Storm.FOCUS_RING_COUNTS) {
      val r = rects(ring)
      assertEquals(1.4f, r[0].w, 0.001f)
      assertEquals(1.4f * 9 / 16, r[0].h, 0.001f)
      for (i in 1 until r.size) {
        assertTrue("ring $ring tile no bigger than the cap", r[i].w <= StormLayout.focusRingWidth(ring) + 0.001f)
        assertTrue("ring $ring tiles still sizeable (${r[i].w})", r[i].w >= 0.4f)
        assertEquals(r[i].w * 9 / 16, r[i].h, 0.001f)
      }
    }
  }

  /** 0.4.5 put 12 ring tiles on an ellipse ~2 m to each side, outside the view; the frame must fit. */
  @Test
  fun focusFrameFitsTheFieldOfView() {
    for (ring in Storm.FOCUS_RING_COUNTS + listOf(1, 3, 20)) {
      for (p in StormLayout.focus(ring)) {
        val x = p.horizM * sin(Math.toRadians(p.yawDeg.toDouble())).toFloat()
        val y = p.dyM - StormLayout.FOCUS_DY_M
        val halfYaw = Math.toDegrees(kotlin.math.atan2((abs(x) + p.widthM / 2).toDouble(), StormLayout.FOCUS_RADIUS_M.toDouble()))
        val halfPitch = Math.toDegrees(kotlin.math.atan2((abs(y) + p.heightM / 2).toDouble(), StormLayout.FOCUS_RADIUS_M.toDouble()))
        assertTrue("ring $ring: tile edge at ${halfYaw}° yaw", halfYaw <= StormLayout.FOCUS_MAX_HALF_YAW_DEG + 0.5)
        assertTrue("ring $ring: tile edge at ${halfPitch}° pitch", halfPitch <= StormLayout.FOCUS_MAX_HALF_PITCH_DEG + 0.5)
      }
    }
  }

  /** Rows only: every ring tile is in the row above or the row below the centre, none beside it. */
  @Test
  fun focusRingIsARowAboveAndARowBelowOnly() {
    for (ring in Storm.FOCUS_RING_COUNTS + listOf(1, 3, 5, 7, 11)) {
      val r = rects(ring)
      val c = r[0]
      val above = r.drop(1).count { it.y > c.y }
      val below = r.drop(1).count { it.y < c.y }
      assertEquals("ring $ring: no tile level with the centre", ring, above + below)
      assertEquals("top gets the extra one", (ring + 1) / 2, above)
      assertEquals(ring / 2, below)
      for (t in r.drop(1)) assertTrue("ring $ring: tile clear above/below the centre", abs(t.y - c.y) >= (t.h + c.h) / 2 + StormLayout.FOCUS_GAP_M - 0.001f)
      // The rows are centred on the centre.
      val topXs = r.drop(1).filter { it.y > c.y }.map { it.x }
      if (topXs.isNotEmpty()) assertEquals(0f, (topXs.min() + topXs.max()) / 2, 0.001f)
    }
  }

  // ---- the still tile never shuffles ----

  @Test
  fun theSkippedTileIsNeverDueAcrossSwapsAndShuffles() {
    val s = StormScheduler(Random(21))
    s.reset(9, 0, 10_000)
    var t = 0L
    val cuts = IntArray(9)
    // 5 minutes of ticks; the Focus centre (slot 0) stays slot 0 across "swaps" (the tiles move, the slot does not).
    repeat(3_000) { n ->
      t += 100
      if (n % 400 == 0) s.shuffleNow(t) // "Shuffle now"
      if (n % 250 == 0) s.rearm(3, t, 10_000) // a swapped-out centre rejoins the shuffle in slot 3
      s.due(t, skip = 0)?.let { i ->
        assertTrue("centre came due", i != 0)
        cuts[i]++
        s.switched(i, t, 10_000)
      }
    }
    assertEquals(0, cuts[0])
    for (i in 1 until 9) assertTrue("tile $i cut ${cuts[i]}", cuts[i] > 0)
  }

  @Test
  fun theSkippedTileDoesNotUseUpTheCutGap() {
    val s = StormScheduler(Random(5))
    s.reset(3, 0, 1_000)
    s.shuffleNow(100)
    assertEquals(1, s.due(100, skip = 0)) // 0 is overdue too, but 1 is next
    s.switched(1, 100, 1_000)
    assertEquals(2, s.due(100 + Storm.MIN_SWITCH_GAP_MS, skip = 0))
  }

  @Test
  fun focusRingWidths() {
    val w = Storm.FOCUS_RING_COUNTS.associateWith { StormLayout.focus(it)[1].widthM }
    for ((ring, width) in w) assertTrue("ring $ring $width", width >= 0.4f)
  }

  @Test
  fun focusRingSpacingKeepsTheGapAndStaysInFront() {
    for (ring in Storm.FOCUS_RING_COUNTS) {
      val r = rects(ring)
      val c = r[0]
      for (i in 1 until r.size) {
        // Clear of the centre by at least the gap on one axis.
        val gx = abs(r[i].x - c.x) - (r[i].w + c.w) / 2
        val gy = abs(r[i].y - c.y) - (r[i].h + c.h) / 2
        assertTrue("ring $ring tile $i touches the centre", maxOf(gx, gy) >= StormLayout.FOCUS_GAP_M - 0.001f)
        for (j in i + 1 until r.size) {
          val dx = abs(r[i].x - r[j].x) - (r[i].w + r[j].w) / 2
          val dy = abs(r[i].y - r[j].y) - (r[i].h + r[j].h) / 2
          assertTrue("ring $ring tiles $i/$j closer than the gap", maxOf(dx, dy) >= StormLayout.FOCUS_GAP_M - 0.001f)
        }
      }
      // Everything stays in a comfortable field of view.
      for (p in StormLayout.focus(ring)) assertTrue(abs(p.yawDeg) < 60f)
    }
  }

  @Test
  fun focusTileCountAndDecoderCostDoNotDependOnSize() {
    // Sizes changed, instances did not: a ring of 8 plus the centre is still 9 tiles for the budget.
    assertEquals(9, Storm.requestedTotal(StormLayoutMode.FOCUS, 25, 8))
    assertEquals(9, StormLayout.poses(9, StormLayoutMode.FOCUS).size)
    val plan = DecoderBudget.plan(13, 0, 0L, DecoderBudget.tileBlocks(SourceMode.PREVIEWS))
    assertEquals(13, plan.tiles)
  }

  @Test
  fun focusPosesComeFromPoses() {
    assertEquals(9, StormLayout.poses(9, StormLayoutMode.FOCUS).size)
    assertEquals(1, StormLayout.poses(1, StormLayoutMode.FOCUS).size)
    assertTrue(StormLayout.poses(0, StormLayoutMode.FOCUS).size <= 1)
  }

  // ---- swap tween ----

  @Test
  fun tweenEndsExactlyAndEasesInAndOut() {
    val m = SlotMove(0f, 0f, 2f, 1f, 2f, 3f, 1f, 3f, startMs = 1000, durationMs = 400, bulgeM = 0.25f)
    val s = m.at(1000)
    assertEquals(0f, s.x, 1e-6f); assertEquals(1f, s.scale, 1e-6f); assertEquals(0f, s.depthM, 1e-6f); assertFalse(s.done)
    val mid = m.at(1200)
    assertEquals(0.5f, mid.x, 1e-4f); assertEquals(2f, mid.scale, 1e-4f); assertEquals(0.25f, mid.depthM, 1e-4f)
    val e = m.at(1400)
    assertEquals(1f, e.x, 1e-6f); assertEquals(3f, e.z, 1e-6f); assertEquals(3f, e.scale, 1e-6f); assertEquals(0f, e.depthM, 1e-6f); assertTrue(e.done)
    assertTrue(m.at(5000).done)
    // slow at the ends: 10 % of the time covers far less than 10 % of the way
    assertTrue(m.at(1040).x < 0.05f)
    var last = -1f
    for (t in 1000..1400 step 20) { val x = m.at(t.toLong()).x; assertTrue(x >= last); last = x }
  }

  @Test
  fun zeroDurationTweenIsDoneAtOnce() {
    val m = SlotMove(0f, 0f, 0f, 5f, 0f, 0f, 1f, 1f, 0, 0)
    assertTrue(m.at(0).done)
    assertEquals(5f, m.at(0).x, 1e-6f)
  }

  // ---- slider / timing ----

  @Test
  fun sliderMapsToWholeSecondsInRange() {
    assertEquals(5, Storm.clipSecondsFromSlider(-3f))
    assertEquals(20, Storm.clipSecondsFromSlider(19.6f))
    assertEquals(60, Storm.clipSecondsFromSlider(99f))
    assertEquals(20, Storm.DEFAULT_CLIP_S)
    assertEquals(60_000L, Storm.clipMs(60))
  }

  @Test
  fun schedulerFollowsSliderLength() {
    for (sec in listOf(5, 20, 60)) {
      val sch = StormScheduler(Random(sec))
      val clip = Storm.clipMs(sec)
      sch.reset(8, 0, clip)
      for (i in 0 until 8) {
        assertTrue(sch.deadline(i) <= (clip * 1.4).toLong())
        assertTrue(sch.deadline(i) >= Storm.MIN_CLIP_MS * 3 / 10)
      }
      sch.switched(0, 1000, clip)
      val d = sch.deadline(0) - 1000
      assertTrue(d >= maxOf(Storm.MIN_CLIP_MS, (clip * 0.6).toLong()) - 1 && d <= (clip * 1.4).toLong())
    }
  }

  @Test
  fun schedulerResizeKeepsExistingDeadlines() {
    val sch = StormScheduler(Random(3))
    sch.reset(4, 0, 20_000)
    val before = (0 until 4).map { sch.deadline(it) }
    sch.resize(6, 500, 20_000)
    assertEquals(before, (0 until 4).map { sch.deadline(it) })
    assertTrue(sch.deadline(5) > 500)
    sch.resize(2, 900, 20_000)
    assertEquals(before.take(2), (0 until 2).map { sch.deadline(it) })
  }

  // ---- SBS tiles ----

  @Test
  fun sbsAndOuAreTilesWithStereo() {
    assertTrue(Storm.eligible(card("a", proj = "sbs", w = 1920, h = 1080))) // half SBS
    assertTrue(Storm.eligible(card("b", proj = "sbs", w = 3840, h = 1080)))
    assertTrue(Storm.eligible(card("c", proj = "ou", w = 1920, h = 2160)))
    assertFalse(Storm.eligible(card("d", proj = "sbs180")))
    assertFalse(Storm.eligible(card("e", proj = "mono360")))
    assertFalse(Storm.eligible(card("f", proj = "fisheye200")))
    assertFalse(Storm.eligible(card("g", proj = "sbs", w = 1000, h = 1000)))
    assertEquals(StormStereo.LEFT_RIGHT, Storm.stereoFor(card("a", proj = "sbs")))
    assertEquals(StormStereo.UP_DOWN, Storm.stereoFor(card("c", proj = "ou")))
    assertEquals(StormStereo.NONE, Storm.stereoFor(card("h")))
  }

  @Test
  fun poolPrefersTheTilesStereoKind() {
    val pool = StormPool(listOf(card("f1"), card("s1", proj = "sbs", w = 3840, h = 1080), card("f2")), Random(4))
    assertEquals(StormStereo.LEFT_RIGHT, Storm.stereoFor(pool.next(emptySet(), StormStereo.LEFT_RIGHT)!!))
    // none of that kind left in the deck: any scene still comes
    assertEquals(StormStereo.NONE, Storm.stereoFor(pool.next(emptySet(), StormStereo.LEFT_RIGHT)!!))
  }

  @Test
  fun smallPoolsExplainThemselves() {
    assertNull(Storm.poolProblem(10, 100))
    assertTrue(Storm.poolProblem(1, 60)!!.contains("1 of 60"))
    assertTrue(Storm.poolProblem(0, 0)!!.contains("no scenes"))
  }

  // ---- incremental plan ----

  @Test
  fun resizeClosesTheLastSlotsOrOpensNewOnes() {
    assertEquals(Storm.Resize(3 until 9, IntRange.EMPTY), Storm.resize(9, 3))
    assertEquals(Storm.Resize(IntRange.EMPTY, 9 until 14), Storm.resize(9, 14))
    assertTrue(Storm.resize(9, 9).unchanged)
    assertEquals(0 until 4, Storm.resize(4, 0).remove)
    assertEquals(9, Storm.requestedTotal(StormLayoutMode.FOCUS, 25, 8))
    assertEquals(25, Storm.requestedTotal(StormLayoutMode.WALL, 25, 8))
  }

  @Test
  fun centreUpgradeNeedsBlockHeadroom() {
    val perTile = DecoderBudget.PREVIEW_BLOCKS
    assertTrue(DecoderBudget.centreFits(3840, 2160, 9, 0, perTile))
    assertFalse(DecoderBudget.centreFits(7680, 4320, 9, 6_000_000, perTile))
  }
}
