package app.trove.quest.api

import kotlinx.serialization.Serializable

// Mirrors the JSON of the Trove server (server/index.ts, server/stash.ts) and web/src/api.ts.

@Serializable data class User(val id: Int, val name: String, val isAdmin: Boolean = false)

@Serializable data class Ref(val id: String, val name: String)

@Serializable
data class Card(
    val id: String,
    val title: String,
    val date: String? = null,
    val duration: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val rating: Int? = null,
    val interactive: Boolean = false,
    val oCount: Int = 0,
    val playCount: Int = 0,
    val studio: Ref? = null,
    val performers: List<Ref> = emptyList(),
    val thumbVersion: String = "0",
    val projection: String = "flat",
) {
  val projectionEnum: Projection
    get() = Projection.fromId(projection)
}

@Serializable data class Marker(val id: String, val title: String, val seconds: Double)

@Serializable
data class FileInfo(val name: String, val size: Long = 0, val fps: Double = 0.0, val audio: String = "")

/** A playable stream; [url] is a server-relative path under /media/. */
@Serializable data class Stream(val label: String, val mime: String, val url: String)

@Serializable
data class Scene(
    val id: String,
    val title: String,
    val date: String? = null,
    val duration: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val interactive: Boolean = false,
    val studio: Ref? = null,
    val performers: List<Ref> = emptyList(),
    val thumbVersion: String = "0",
    val projection: String = "flat",
    val detectedProjection: String = "flat",
    val details: String? = null,
    val tags: List<Ref> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val file: FileInfo? = null,
    val streams: List<Stream> = emptyList(),
    /** Play tracking (server): resume point and total seconds played, 0 = none; [lastPlayedAt] ISO time. */
    val resumeTime: Double = 0.0,
    val playDuration: Double = 0.0,
    val lastPlayedAt: String? = null,
)

@Serializable data class UserResponse(val user: User)

/** [sections] (newer servers): the sort keys each library section accepts, by kind (performer / studio / tag). */
@Serializable data class FiltersResponse(val filters: List<Ref>, val sorts: List<String>, val sections: SectionsInfo? = null)

@Serializable data class SectionsInfo(val sorts: Map<String, List<String>> = emptyMap())

@Serializable data class ScenesResponse(val count: Int, val scenes: List<Card>)

@Serializable data class SceneResponse(val scene: Scene)

/** One of a scene's funscripts (GET /api/scenes/<id>/scripts): [id] is what a choice sends, [default] = the scene's own. */
@Serializable
data class SceneScript(val id: String, val label: String = id, val default: Boolean = false, val hasContent: Boolean = true)

/**
 * GET /api/scenes/<id>/scripts: Stash Interactive Tools' variants (or just the scene's own script), the one in
 * effect for this user ([chosen]) and whether a new choice is still being loaded onto the toy.
 */
@Serializable
data class SceneScripts(
    val sceneId: String,
    val scripts: List<SceneScript> = emptyList(),
    val chosen: String? = null,
    val loading: Boolean = false,
)

@Serializable data class ErrorResponse(val error: String? = null)

/** POST /api/pair/start: [code] and [url] are for people; [pairId] + [secret] stay in the app. */
@Serializable
data class PairStart(
    val code: String,
    val pairId: String,
    val secret: String,
    val expiresIn: Int = 300,
    val interval: Int = 2,
    val url: String,
)

/** POST /api/pair/poll: status is pending | denied | expired | approved (then [user] is set). */
@Serializable data class PairPoll(val status: String, val user: User? = null)

/** What a facet filters on; [param] is the /api/scenes query parameter (comma-separated ids). */
enum class FacetType(val id: String, val param: String) {
  PERFORMER("performer", "performers"),
  STUDIO("studio", "studios"),
  TAG("tag", "tags");

  companion object {
    fun fromId(id: String): FacetType? = entries.firstOrNull { it.id == id }
  }
}

/** A performer, studio or tag picked as a wall filter (web/src/api.ts `Facet`). */
data class Facet(val type: FacetType, val id: String, val name: String)

/** GET /api/suggest item: [type] is performer | studio | tag; [count] = scenes with it. */
@Serializable
data class Suggestion(val type: String, val id: String, val name: String, val count: Int = 0) {
  fun toFacet(): Facet? = FacetType.fromId(type)?.let { Facet(it, id, name) }
}

@Serializable data class SuggestResponse(val items: List<Suggestion> = emptyList())

/** Wall query; mirrors the params /api/scenes reads. Null means "server default". */
data class SceneQuery(
    val filterId: String? = null,
    val q: String = "",
    /** ANDed with the saved filter on the server, like the web wall's chips. */
    val facets: List<Facet> = emptyList(),
    val sort: String? = null,
    val dir: String? = null,
    val seed: Int = 0,
    val page: Int = 1,
    val perPage: Int = 12,
)

/** GET api/preview-config: Stash's preview montage settings (the server falls back to Stash's defaults, `fallback` = true). */
@Serializable
data class PreviewConfig(
    val segments: Int = 12,
    val segmentDuration: Double = 0.75,
    val excludeStart: String = "0",
    val excludeEnd: String = "0",
    val fallback: Boolean = false,
)

/**
 * A performer, studio or tag in a section grid (GET /api/performers|studios|tags). [image] is a
 * server-relative /media path, null when Stash has only its stock silhouette. The kind-specific
 * fields are null for the other kinds.
 */
@Serializable
data class Entity(
    val id: String,
    val name: String,
    val image: String? = null,
    val sceneCount: Int = 0,
    val favorite: Boolean = false,
    val rating: Int? = null,
    val age: Int? = null,
    val birthdate: String? = null,
    val country: String? = null,
    val gender: String? = null,
    val parent: Ref? = null,
    val description: String? = null,
    val details: String? = null,
    val aliases: List<String> = emptyList(),
    val tags: List<Ref> = emptyList(),
    val children: List<Ref> = emptyList(),
    val parents: List<Ref> = emptyList(),
)

@Serializable data class EntitiesResponse(val count: Int = 0, val items: List<Entity> = emptyList())

/** The library sections, in the order of the wall's switcher. [scenes] has no entity list: it is the scene wall. */
enum class Section(val label: String, val path: String?, val facet: FacetType?) {
  SCENES("Scenes", null, null),
  PERFORMERS("Performers", "performers", FacetType.PERFORMER),
  STUDIOS("Studios", "studios", FacetType.STUDIO),
  TAGS("Tags", "tags", FacetType.TAG);

  companion object {
    fun fromId(id: String?): Section = entries.firstOrNull { it.name == id } ?: SCENES
    fun of(type: FacetType): Section = entries.first { it.facet == type }
  }
}

/** Section grid query; mirrors the params /api/performers|studios|tags read. Null sort = name. */
data class EntityQuery(
    val section: Section,
    val q: String = "",
    val sort: String? = null,
    val dir: String? = null,
    val seed: Int = 0,
    val page: Int = 1,
    val perPage: Int = 24,
    val favorite: Boolean = false,
)
