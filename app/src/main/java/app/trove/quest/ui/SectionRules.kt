package app.trove.quest.ui

import app.trove.quest.api.Entity
import app.trove.quest.api.EntityQuery
import app.trove.quest.api.Facet
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.Section

/**
 * The library sections (Scenes / Performers / Studios / Tags) without Compose: sort lists, page size,
 * wording, and what opening a performer / studio / tag does to the scene query. Unit tested in SectionsTest.
 */
object SectionRules {
  /** Sorts to offer when an older server does not list them in /api/filters (same words as the server). */
  private val FALLBACK_SORTS =
      mapOf(
          Section.PERFORMERS to listOf("name", "scenes", "rating", "random", "created", "birthdate", "age"),
          Section.STUDIOS to listOf("name", "scenes", "rating", "random", "created"),
          Section.TAGS to listOf("name", "scenes", "random", "created"),
      )

  /** Sort keys of [section]: the server's list (by facet kind), else the built-in one. */
  fun sortOptions(section: Section, server: Map<String, List<String>>): List<String> =
      section.facet?.let { server[it.id]?.takeIf { l -> l.isNotEmpty() } } ?: FALLBACK_SORTS[section].orEmpty()

  /** Name sorts A to Z; everything else (counts, ratings, dates) starts with the most. */
  fun defaultDir(sort: String?): String = if ((sort ?: "name") == "name") "ASC" else "DESC"

  /** Columns and rows per page: portraits are narrow (5 across), logos and tag images 16:9 (4 across, like scenes). */
  fun columns(section: Section): Int = if (section == Section.PERFORMERS) 5 else 4

  fun perPage(section: Section): Int =
      when (section) {
        Section.PERFORMERS -> 15
        Section.SCENES -> AppModel.PER_PAGE
        else -> 12
      }

  /** Width/height of an entity's picture. */
  fun imageAspect(section: Section): Float = if (section == Section.PERFORMERS) 2f / 3f else 16f / 9f

  fun plural(section: Section, n: Int): String {
    val word = section.label.lowercase()
    return if (n == 1) word.removeSuffix("s") else word
  }

  fun countLabel(section: Section, loading: Boolean, total: Int): String =
      if (loading) "Loading…" else "$total ${if (section == Section.SCENES) "scene${if (total == 1) "" else "s"}" else plural(section, total)}"

  fun scenesLabel(n: Int): String = "$n scene${if (n == 1) "" else "s"}"

  /** The line under a card's name. */
  fun cardMeta(section: Section, e: Entity): String =
      when (section) {
        Section.PERFORMERS -> listOfNotNull(scenesLabel(e.sceneCount), e.age?.let { "$it y" }, e.country)
        Section.STUDIOS -> listOfNotNull(scenesLabel(e.sceneCount), e.parent?.name)
        else -> listOf(scenesLabel(e.sceneCount))
      }.joinToString(" · ")

  /** The facts line of a page header. */
  fun pageFacts(e: Entity): String =
      listOfNotNull(
              scenesLabel(e.sceneCount),
              e.age?.let { "$it years" },
              e.country,
              e.gender?.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.uppercase() },
              e.parent?.let { "part of ${it.name}" },
          )
          .joinToString(" · ")

  fun facetOf(section: Section, e: Entity): Facet = Facet(section.facet ?: error("Scenes have no facet"), e.id, e.name)

  /** The scene query of a performer / studio / tag page: that facet alone, everything else default (same rule as Storm's re-Storm). */
  fun pageQuery(facet: Facet, perPage: Int): SceneQuery = SceneQuery(facets = listOf(facet), perPage = perPage)

  /** Still the page of [facet]? Searching or sorting inside it is fine; another chip, removing the chip or a tab turns it into an ordinary filtered wall. */
  fun stillPage(facet: Facet, q: SceneQuery): Boolean = q.facets == listOf(facet) && q.filterId == null

  /** Back button label of a page opened from [origin]. */
  fun backLabel(origin: Section): String = "‹ ${origin.label}"

  fun emptyTitle(section: Section): String = "No ${section.label.lowercase()} match"

  /** Entity query for [section] given the remembered one: perPage is always the section's. */
  fun freshQuery(section: Section): EntityQuery = EntityQuery(section = section, perPage = perPage(section))
}
