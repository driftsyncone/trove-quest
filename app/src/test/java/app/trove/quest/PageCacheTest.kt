package app.trove.quest

import app.trove.quest.api.Card
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.ScenesResponse
import app.trove.quest.ui.PageCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PageCacheTest {
  private fun res(vararg ids: String) = ScenesResponse(30, ids.map { Card(it, "Scene $it") })

  @Test
  fun aPageIsServedForExactlyItsQueryWhileFresh() {
    val c = PageCache(ttlMs = 1000)
    val q = SceneQuery(page = 2)
    val r = res("a")
    c.put(q, r, nowMs = 0)
    assertSame(r, c.get(q, 999))
    assertNull(c.get(q.copy(page = 3), 10))
    assertNull(c.get(q.copy(sort = "date"), 10))
    assertNull(c.get(q.copy(seed = 7), 10)) // a new shuffle is a new page
    assertNull(c.get(q, 1001)) // stale
    assertNull(c.get(q, 10)) // and dropped
  }

  @Test
  fun onlyTheNewestPagesAreKept() {
    val c = PageCache(maxPages = 2)
    for (p in 1..3) c.put(SceneQuery(page = p), res("$p"), 0)
    assertNull(c.get(SceneQuery(page = 1), 0))
    assertEquals("2", c.get(SceneQuery(page = 2), 0)!!.scenes[0].id)
    assertEquals("3", c.get(SceneQuery(page = 3), 0)!!.scenes[0].id)
  }

  @Test
  fun aClockThatWentBackIsNotTrusted() {
    val c = PageCache()
    c.put(SceneQuery(), res("a"), 5000)
    assertNull(c.get(SceneQuery(), 10))
  }

  @Test
  fun nextPageStopsAtTheLast() {
    assertEquals(2, PageCache.next(SceneQuery(page = 1, perPage = 12), 13)!!.page)
    assertNull(PageCache.next(SceneQuery(page = 2, perPage = 12), 13))
    assertNull(PageCache.next(SceneQuery(page = 1, perPage = 12), 12))
    assertNull(PageCache.next(SceneQuery(page = 1, perPage = 12), 0))
  }
}
