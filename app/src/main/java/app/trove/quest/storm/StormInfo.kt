package app.trove.quest.storm

import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.Scene
import app.trove.quest.api.SceneQuery

/** Pure rules of the Focus scene-info panel (README "Storm"); unit tested in `StormInfoTest`. */
object StormInfo {
  /** The scenes whose details are kept, so swapping back to a scene shows it at once. */
  const val CACHE_SIZE = 32

  /** Studio, then performers, then tags of [s] as filter facets. */
  fun facets(s: Scene): List<Facet> =
      listOfNotNull(s.studio?.let { Facet(FacetType.STUDIO, it.id, it.name) }) +
          s.performers.map { Facet(FacetType.PERFORMER, it.id, it.name) } +
          s.tags.map { Facet(FacetType.TAG, it.id, it.name) }

  /**
   * The query a facet chip re-Storms with: that performer / studio / tag alone (no saved filter, other
   * facets or search text; the Storm's sort and page size are kept), so the pool is all of their scenes.
   */
  fun restormQuery(last: SceneQuery, facet: Facet): SceneQuery =
      last.copy(filterId = null, facets = listOf(facet), q = "", page = 1)
}

/** Small least-recently-used cache of scene details by id (the info panel fetches one per centre scene). */
class SceneCache<T>(private val max: Int = StormInfo.CACHE_SIZE) {
  private val map = object : LinkedHashMap<String, T>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > max
  }

  val size: Int
    get() = map.size

  operator fun get(id: String): T? = map[id]

  fun put(id: String, v: T) {
    map[id] = v
  }
}
