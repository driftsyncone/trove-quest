package app.trove.quest.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.api.ApiException
import app.trove.quest.api.Card
import app.trove.quest.api.DevicePairing
import app.trove.quest.api.Entity
import app.trove.quest.api.EntityQuery
import app.trove.quest.api.Facet
import app.trove.quest.api.PairingState
import app.trove.quest.api.Ref
import app.trove.quest.api.Scene
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.SceneScripts
import app.trove.quest.api.ScenesResponse
import app.trove.quest.api.Section
import app.trove.quest.api.TroveApi
import app.trove.quest.api.Suggestion
import app.trove.quest.api.User
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen {
  STARTING,
  LOGIN,
  WALL,
}

/** Login + wall state for the main panel. All calls on the main thread; network work is in [api]. */
class AppModel(
    private val api: TroveApi,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val onPlay: (Card) -> Unit,
    private val onLoggedOut: () -> Unit,
    /** The server said 401 (session gone): stop everything that plays before the login shows. */
    private val onSessionLost: () -> Unit = {},
    /** Warms the image cache with the covers of a page fetched ahead ([PageCache]); no UI. */
    private val prefetchCovers: (List<Card>) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  var screen by mutableStateOf(Screen.STARTING)
    private set

  var user by mutableStateOf<User?>(null)
    private set

  var serverUrl by mutableStateOf(api.baseUrl.toString().trimEnd('/'))
  var loginError by mutableStateOf<String?>(null)
    private set

  var busy by mutableStateOf(false)
    private set

  /** Device pairing on the login screen (code + QR, approved from a phone). */
  var pairing by mutableStateOf<PairingState>(PairingState.Starting)
    private set

  private var pairingJob: Job? = null

  // ---- wall ----
  var filters by mutableStateOf<List<Ref>>(emptyList())
    private set

  var sorts by mutableStateOf<List<String>>(emptyList())
    private set

  var query by mutableStateOf(SceneQuery(perPage = PER_PAGE))
    private set

  // ---- library sections (performers, studios, tags) ----

  /** Which section the wall shows; remembered across launches. */
  var section by mutableStateOf(Section.fromId(prefs.getString(PREF_SECTION, null)))
    private set

  /** Sort keys the server offers per kind (performer / studio / tag), from /api/filters. */
  private var serverEntitySorts by mutableStateOf<Map<String, List<String>>>(emptyMap())

  /** Each section grid keeps its own search, sort and page while you are in another section. */
  private val entityQueries = mutableMapOf<Section, EntityQuery>()

  var entityQuery by mutableStateOf(SectionRules.freshQuery(Section.PERFORMERS))
    private set

  var entities by mutableStateOf<List<Entity>>(emptyList())
    private set

  var entityTotal by mutableIntStateOf(0)
    private set

  var entityLoading by mutableStateOf(false)
    private set

  var entityError by mutableStateOf<String?>(null)
    private set

  private var entityJob: Job? = null

  /** A performer / studio / tag opened from its grid: the Scenes section filtered by it. B goes back to [EntityPage.origin]'s grid. */
  var page by mutableStateOf<EntityPage?>(null)
    private set

  private var pageJob: Job? = null

  /** What is typed in the search box; applied on submit. Set it through [typeSearch]. */
  var searchText by mutableStateOf("")
    private set

  /** Performer / studio / tag chips for what is typed (GET /api/suggest); tapping one adds a facet. */
  var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
    private set

  private var suggestJob: Job? = null

  /** Scene details shown in place of the grid (a card's info button); null = the grid. */
  var detail by mutableStateOf<SceneDetail?>(null)
    private set

  private var detailJob: Job? = null

  /** The Sort chip row is open (hoisted from the wall so B can close it, see input/RowStack). */
  var sortOpen by mutableStateOf(false)

  var scenes by mutableStateOf<List<Card>>(emptyList())
    private set

  var total by mutableIntStateOf(0)
    private set

  var wallError by mutableStateOf<String?>(null)
    private set

  var loading by mutableStateOf(false)
    private set

  val pages: Int
    get() =
        if (section == Section.SCENES) maxOf(1, (total + PER_PAGE - 1) / PER_PAGE)
        else maxOf(1, (entityTotal + entityQuery.perPage - 1) / entityQuery.perPage)

  /** The page number shown in the footer. */
  val currentPage: Int
    get() = if (section == Section.SCENES) query.page else entityQuery.page

  val isLoading: Boolean
    get() = if (section == Section.SCENES) loading else entityLoading

  val countLabel: String
    get() = SectionRules.countLabel(section, isLoading, if (section == Section.SCENES) total else entityTotal)

  val entitySorts: List<String>
    get() = SectionRules.sortOptions(section, serverEntitySorts)

  private var loadJob: Job? = null

  /** Wall pages already answered, and the next one fetched ahead while you look at this one. */
  private val pageCache = PageCache()
  private var prefetchJob: Job? = null

  fun start() {
    if (!api.hasSession()) {
      showLogin()
      return
    }
    scope.launch {
      try {
        user = api.me()
        enterWall()
      } catch (e: ApiException) {
        showLogin()
        if (e.status != 401) loginError = e.message
      } catch (e: IOException) {
        showLogin()
        loginError = "Can't reach ${api.baseUrl}: ${e.message}"
      }
    }
  }

  /** Applies the typed server URL; false (with [loginError] set) if it can't be parsed. */
  private fun applyServer(): Boolean {
    try {
      api.setBaseUrl(serverUrl.trim())
    } catch (e: IllegalArgumentException) {
      loginError = "Server URL looks wrong: ${e.message}"
      return false
    }
    prefs.edit().putString(PREF_SERVER, api.baseUrl.toString()).apply()
    return true
  }

  private fun showLogin() {
    screen = Screen.LOGIN
    startPairing()
  }

  /** (Re)starts device pairing against the server in the Server field. Also the "New code" button. */
  fun startPairing() {
    pairingJob?.cancel()
    if (api.baseUrl.toString().trimEnd('/') != serverUrl.trim().trimEnd('/') && !applyServer()) return
    loginError = null
    pairing = PairingState.Starting
    pairingJob =
        scope.launch {
          val result = DevicePairing(api).run { pairing = it }
          if (result is PairingState.Approved && screen == Screen.LOGIN) {
            user = result.user
            enterWall()
          }
        }
  }

  private fun stopPairing() {
    pairingJob?.cancel()
    pairingJob = null
  }

  fun login(name: String, password: String) {
    if (busy) return
    if (!applyServer()) return
    busy = true
    loginError = null
    scope.launch {
      try {
        user = api.login(name.trim(), password)
        enterWall()
      } catch (e: ApiException) {
        loginError = e.message
      } catch (e: IOException) {
        loginError = "Can't reach ${api.baseUrl}: ${e.message}"
      } finally {
        busy = false
      }
    }
  }

  fun logout() {
    scope.launch {
      api.logout()
      user = null
      scenes = emptyList()
      closeDetails()
      sortOpen = false
      searchText = ""
      clearSuggestions()
      query = query.copy(q = "", facets = emptyList(), page = 1)
      page = null
      entities = emptyList()
      entityQueries.clear()
      pageCache.clear()
      onLoggedOut()
      showLogin()
    }
  }

  private suspend fun enterWall() {
    stopPairing()
    screen = Screen.WALL
    pageCache.clear() // another server or user than the pages cached
    // The first page does not need the filter list: both requests go out at once (cold start was
    // me -> filters -> scenes, three round trips in a row). A 401 is handled by the page's request.
    if (section == Section.SCENES) reload() else enterSection(section)
    try {
      val f = api.filters()
      filters = f.filters
      sorts = f.sorts
      serverEntitySorts = f.sections?.sorts.orEmpty()
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      if (e is ApiException && e.status == 401) return
      // The scene wall shows its own error (or its scenes); the section grids had only this one.
      if (section != Section.SCENES) wallError = e.message
    }
  }

  // ---- wall actions ----

  fun selectFilter(id: String?) = update(query.copy(filterId = id, sort = null, dir = null, page = 1))

  fun search() {
    clearSuggestions()
    if (section != Section.SCENES) return updateEntities(entityQuery.copy(q = searchText.trim(), page = 1))
    update(query.copy(q = searchText.trim(), page = 1))
  }

  /** The search box changed: suggestions follow after a short pause, as on the web wall. */
  fun typeSearch(text: String) {
    searchText = text
    suggestJob?.cancel()
    val q = text.trim()
    if (q.length < 2 || section != Section.SCENES) { // the section grids search by name on submit
      suggestions = emptyList()
      return
    }
    suggestJob =
        scope.launch {
          delay(SUGGEST_DELAY_MS)
          val items =
              try {
                api.suggest(q)
              } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList() // suggestions are a nicety; the search itself still works
              }
          if (searchText.trim() == q) suggestions = items
        }
  }

  /** A suggestion chip: becomes a filter chip and clears the box (web: pick()). */
  fun pickSuggestion(s: Suggestion) {
    val facet = s.toFacet() ?: return
    searchText = ""
    clearSuggestions()
    update(query.copy(facets = Facets.add(query.facets, facet), q = "", page = 1))
  }

  /** Opens the details of [card] over the grid and loads the full scene (tags, markers, file). */
  fun showDetails(card: Card) {
    detailJob?.cancel()
    detail = SceneDetail(card)
    detailJob =
        scope.launch {
          try {
            val s = api.scene(card.id)
            if (detail?.card?.id == card.id) detail = SceneDetail(card, scene = s)
            if (s.interactive) loadScripts(card.id)
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!sessionEnded(e) && detail?.card?.id == card.id) detail = SceneDetail(card, error = e.message ?: e.toString())
          }
        }
  }

  /**
   * The open scene's script variants (best effort: an older server or Stash trouble just shows no picker). Asked
   * again after a pick was answered, and every second while the server still loads a new choice onto the toy.
   */
  fun loadScripts(sceneId: String) {
    scope.launch {
      try {
        val r = api.sceneScripts(sceneId)
        detail?.takeIf { it.card.id == sceneId && it.scene != null }?.let { detail = it.copy(scripts = r) }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
      }
    }
  }

  fun closeDetails() {
    detailJob?.cancel()
    detail = null
  }

  /** A performer / studio / tag tapped elsewhere (scene details) becomes a filter chip. */
  fun addFacet(f: Facet) = update(query.copy(facets = Facets.add(query.facets, f), page = 1))

  fun removeFacet(f: Facet) = update(query.copy(facets = Facets.remove(query.facets, f), page = 1))

  fun clearQueryText() {
    searchText = ""
    if (section != Section.SCENES) return updateEntities(entityQuery.copy(q = "", page = 1))
    update(query.copy(q = "", page = 1))
  }

  /** "Clear all": search text and every facet; the saved-filter tab and sort stay. */
  fun clearChips() {
    searchText = ""
    clearSuggestions()
    update(query.copy(q = "", facets = emptyList(), page = 1))
  }

  /** "Show everything": search text, facets, the saved-filter tab and the sort go back to default. */
  fun clearAllFilters() {
    searchText = ""
    clearSuggestions()
    update(query.copy(q = "", facets = emptyList(), filterId = null, sort = null, dir = null, page = 1))
  }

  private fun clearSuggestions() {
    suggestJob?.cancel()
    suggestions = emptyList()
  }

  fun selectSort(sort: String) {
    val seed = if (sort == "random") Random.nextInt(1, 1_000_000) else query.seed
    if (section != Section.SCENES) return updateEntities(entityQuery.copy(sort = sort, dir = null, seed = seed, page = 1))
    update(query.copy(sort = sort, seed = seed, page = 1))
  }

  fun toggleDir() {
    if (section != Section.SCENES) return updateEntities(entityQuery.copy(dir = if (currentDir == "DESC") "ASC" else "DESC", page = 1))
    update(query.copy(dir = if (currentDir == "DESC") "ASC" else "DESC", page = 1))
  }

  fun shuffle() {
    if (section != Section.SCENES) return updateEntities(entityQuery.copy(seed = Random.nextInt(1, 1_000_000), page = 1))
    update(query.copy(seed = Random.nextInt(1, 1_000_000), page = 1))
  }

  fun goToPage(p: Int) {
    if (section != Section.SCENES) return updateEntities(entityQuery.copy(page = p.coerceIn(1, pages)))
    update(query.copy(page = p.coerceIn(1, pages)))
  }

  fun play(card: Card) = onPlay(card)

  val currentDir: String
    get() =
        if (section == Section.SCENES) query.dir ?: "DESC"
        else entityQuery.dir ?: SectionRules.defaultDir(entityQuery.sort)

  private fun update(q: SceneQuery) {
    closeDetails() // any change to what the wall shows goes back to the grid
    // Another chip, a search or a tab makes it an ordinary filtered wall, no longer that page.
    page?.let { if (!SectionRules.stillPage(it.facet, q)) page = null }
    query = q
    reload()
  }

  // ---- sections ----

  /** The section switcher. From a performer / studio / tag page it leaves the page (and its filter). */
  fun selectSection(s: Section) {
    if (s == section && page == null) return
    closeDetails()
    leavePage()
    section = s
    prefs.edit().putString(PREF_SECTION, s.name).apply()
    enterSection(s)
  }

  private fun enterSection(s: Section) {
    clearSuggestions()
    if (s == Section.SCENES) {
      searchText = query.q
      reload()
    } else {
      entityQuery = entityQueries[s] ?: SectionRules.freshQuery(s)
      searchText = entityQuery.q
      entities = emptyList()
      reloadEntities()
    }
  }

  fun toggleFavorites() = updateEntities(entityQuery.copy(favorite = !entityQuery.favorite, page = 1))

  private fun updateEntities(q: EntityQuery) {
    entityQuery = q
    entityQueries[q.section] = q
    reloadEntities()
  }

  fun reloadEntities() {
    entityJob?.cancel()
    val q = entityQuery
    if (q.section == Section.SCENES) return
    entityLoading = true
    entityError = null
    entityJob =
        scope.launch {
          try {
            val res = api.entities(q)
            entityTotal = res.count
            entities = res.items
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!sessionEnded(e)) entityError = e.message ?: e.toString()
          } finally {
            if (entityQuery == q) entityLoading = false
          }
        }
  }

  /** A card in a section grid: the Scenes section, filtered by it, under a header with its details. */
  fun openEntity(e: Entity) {
    val origin = section
    val type = origin.facet ?: return
    val facet = Facet(type, e.id, e.name)
    closeDetails()
    pageJob?.cancel()
    page = EntityPage(origin, facet, e)
    section = Section.SCENES
    searchText = ""
    clearSuggestions()
    query = SectionRules.pageQuery(facet, PER_PAGE)
    reload()
    // The grid card has the basics; the details (text, aliases) come with the page request.
    pageJob =
        scope.launch {
          try {
            val full = api.entity(type, e.id)
            page?.let { if (it.facet == facet) page = it.copy(entity = full) }
          } catch (x: Exception) {
            if (x is kotlinx.coroutines.CancellationException) throw x
            sessionEnded(x) // otherwise the header just keeps the card's facts
          }
        }
  }

  /** B on a page: back to the grid it was opened from (search, sort and page of that grid are as left). */
  fun closePage() {
    val p = page ?: return
    closeDetails()
    leavePage()
    section = p.origin
    enterSection(p.origin)
  }

  /** Drops the page and its filter; the scene wall goes back to unfiltered. */
  private fun leavePage() {
    if (page == null) return
    pageJob?.cancel()
    page = null
    query = SceneQuery(perPage = PER_PAGE)
    scenes = emptyList()
  }

  fun reload() {
    loadJob?.cancel()
    prefetchJob?.cancel()
    val q = query
    wallError = null
    pageCache.get(q, clock())?.let { res ->
      loading = false
      total = res.count
      scenes = res.scenes
      prefetchAfter(q, res)
      return
    }
    loading = true
    loadJob =
        scope.launch {
          try {
            val res = api.scenes(q)
            total = res.count
            scenes = res.scenes
            pageCache.put(q, res, clock())
            prefetchAfter(q, res)
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!sessionEnded(e)) wallError = e.message ?: e.toString()
          } finally {
            if (query == q) loading = false
          }
        }
  }

  /**
   * After a page shows: fetch the next one and its covers, a moment later so this page's covers go
   * first. Cancelled by the next [reload]; failures are ignored (Next just asks the server then).
   */
  private fun prefetchAfter(q: SceneQuery, res: ScenesResponse) {
    val next = PageCache.next(q, res.count) ?: return
    if (pageCache.has(next, clock())) return
    prefetchJob =
        scope.launch {
          delay(PREFETCH_DELAY_MS)
          val ahead =
              try {
                api.scenes(next)
              } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@launch
              }
          pageCache.put(next, ahead, clock())
          prefetchCovers(ahead.scenes)
        }
  }

  private fun sessionEnded(e: Exception): Boolean {
    if (e is ApiException && e.status == 401) {
      // Screens and Storm would keep playing behind the login on a dead session (and keep the sync
      // report saying "playing"): pause them first.
      onSessionLost()
      user = null
      showLogin()
      loginError = "Your session ended. Log in again."
      return true
    }
    return false
  }

  companion object {
    const val PER_PAGE = 12 // 4 x 3, like the web wall
    const val PREF_SERVER = "server_url"
    const val PREF_SECTION = "wall_section"
    const val SUGGEST_DELAY_MS = 250L

    /** How long a page shows before the next one is fetched ahead. */
    const val PREFETCH_DELAY_MS = 800L
  }
}

/** The open performer / studio / tag page: its [facet] filters the wall, [origin] is the grid B returns to. */
data class EntityPage(val origin: Section, val facet: Facet, val entity: Entity)

/** A card's details: [scene] once loaded, or [error]. */
data class SceneDetail(val card: Card, val scene: Scene? = null, val error: String? = null, val scripts: SceneScripts? = null)

/** Facet list rules (pure, unit tested): no duplicates, order of picking kept. */
object Facets {
  fun add(list: List<Facet>, f: Facet): List<Facet> =
      if (list.any { it.type == f.type && it.id == f.id }) list else list + f

  fun remove(list: List<Facet>, f: Facet): List<Facet> = list.filterNot { it.type == f.type && it.id == f.id }
}
