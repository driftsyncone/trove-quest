package app.trove.quest.ui

import app.trove.quest.api.Card
import app.trove.quest.api.Projection

/** What the wall's main area shows. */
enum class WallContent {
  /** First load: nothing to show yet. */
  LOADING,
  ERROR,
  /** Loaded, nothing matches the search / chips / tab. */
  EMPTY_FILTERED,
  /** Loaded and there is nothing at all. */
  EMPTY,
  /** The grid (dimmed while the next page loads). */
  GRID,
}

/** What the session group of the wall header offers. */
data class SessionControls(
    /** "Open in: This screen | New screen". */
    val openIn: Boolean,
    val room: Boolean,
    /** Label of the restore button, or null for none. */
    val restoreLabel: String?,
)

/** Layout and wording decisions of the wall that need no Compose (unit tested in WallLogicTest). */
object WallLogic {
  fun content(loading: Boolean, error: String?, sceneCount: Int, filtered: Boolean): WallContent =
      when {
        sceneCount > 0 -> WallContent.GRID
        loading -> WallContent.LOADING
        error != null -> WallContent.ERROR
        filtered -> WallContent.EMPTY_FILTERED
        else -> WallContent.EMPTY
      }

  /** The search text, any facet chip or a saved-filter tab narrows the wall. */
  fun isFiltered(query: String, facetCount: Int, filterId: String?): Boolean =
      query.isNotEmpty() || facetCount > 0 || filterId != null

  fun session(openScreens: Int, savedScreens: Int?): SessionControls =
      if (openScreens > 0) SessionControls(openIn = true, room = false, restoreLabel = null)
      else SessionControls(openIn = false, room = true, restoreLabel = savedScreens?.let(::restoreLabel))

  fun restoreLabel(count: Int): String = if (count == 1) "Restore last screen" else "Restore last $count screens"

  /** The second "Open in" chip: how many screens are open, and what a tap does at the limit. */
  fun newScreenLabel(open: Int, max: Int, full: Boolean): String =
      if (full) "New screen $max/$max (replaces)" else "New screen $open/$max"

  fun countLabel(loading: Boolean, total: Int): String =
      if (loading) "Loading…" else "$total scene${if (total == 1) "" else "s"}"

  fun sortSummary(label: String): String = "Sort: $label"

  /** Resume progress for a card's thumbnail, 0..1, or null when there is no point worth showing. */
  fun resumeFraction(resumeMs: Long?, durationSeconds: Double): Float? {
    if (resumeMs == null || resumeMs <= 0 || durationSeconds <= 0) return null
    return (resumeMs / (durationSeconds * 1000.0)).toFloat().coerceIn(0f, 1f)
  }

  /** Resolution and projection in one badge ("8K · 3D SBS"); null when there is nothing to say. */
  fun qualityBadge(card: Card): String? {
    val res = fmtRes(card.width, card.height)
    val proj = card.projectionEnum
    return listOfNotNull(res.ifEmpty { null }, proj.short.takeIf { proj != Projection.FLAT })
        .joinToString(" · ")
        .ifEmpty { null }
  }

  fun cardMeta(card: Card): String = listOfNotNull(card.studio?.name, card.date).joinToString(" · ")
}
