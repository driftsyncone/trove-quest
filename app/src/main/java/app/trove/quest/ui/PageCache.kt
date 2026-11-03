package app.trove.quest.ui

import app.trove.quest.api.SceneQuery
import app.trove.quest.api.ScenesResponse

/**
 * The last few wall pages the server answered, by their exact query (pure, unit tested). The wall
 * fetches the next page while you look at this one ([AppModel]), so "Next ›" (and "‹ Prev" back
 * to a page just seen) shows at once instead of waiting for the server. Pages are short-lived
 * ([ttlMs]): play counts and new scenes show up again after that.
 */
class PageCache(private val maxPages: Int = 4, private val ttlMs: Long = 60_000L) {
  private class Entry(val res: ScenesResponse, val atMs: Long)

  // Insertion order = age; the oldest goes first.
  private val pages = LinkedHashMap<SceneQuery, Entry>()

  fun put(q: SceneQuery, res: ScenesResponse, nowMs: Long) {
    pages.remove(q)
    pages[q] = Entry(res, nowMs)
    while (pages.size > maxPages) pages.remove(pages.keys.first())
  }

  /** The page for exactly [q] if it is fresh; a stale one is dropped. */
  fun get(q: SceneQuery, nowMs: Long): ScenesResponse? {
    val e = pages[q] ?: return null
    if (nowMs - e.atMs > ttlMs || nowMs < e.atMs) {
      pages.remove(q)
      return null
    }
    return e.res
  }

  fun has(q: SceneQuery, nowMs: Long): Boolean = get(q, nowMs) != null

  fun clear() = pages.clear()

  companion object {
    /** The page after [q], or null on the last one ([total] scenes in all). */
    fun next(q: SceneQuery, total: Int): SceneQuery? {
      val pages = maxOf(1, (total + q.perPage - 1) / q.perPage)
      return if (q.page < pages) q.copy(page = q.page + 1) else null
    }
  }
}
