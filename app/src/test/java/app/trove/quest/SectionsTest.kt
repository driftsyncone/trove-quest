package app.trove.quest

import android.content.SharedPreferences
import app.trove.quest.api.Entity
import app.trove.quest.api.EntityQuery
import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.Ref
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.Section
import app.trove.quest.api.TroveApi
import app.trove.quest.input.Action
import app.trove.quest.input.ControlScheme
import app.trove.quest.input.Snapshot
import app.trove.quest.storm.StormInfo
import app.trove.quest.ui.AppModel
import app.trove.quest.ui.SectionRules
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Library sections (Scenes / Performers / Studios / Tags): rules, request paths, section state, the page back stack. */
class SectionsTest {
  // ---- pure rules ----

  @Test
  fun sortOptionsComeFromTheServerElseTheBuiltInList() {
    val server = mapOf("performer" to listOf("name", "age"))
    assertEquals(listOf("name", "age"), SectionRules.sortOptions(Section.PERFORMERS, server))
    assertTrue("birthdate" in SectionRules.sortOptions(Section.PERFORMERS, emptyMap()))
    assertFalse("rating" in SectionRules.sortOptions(Section.TAGS, emptyMap()))
    assertEquals(listOf("name", "scenes", "rating", "random", "created"), SectionRules.sortOptions(Section.STUDIOS, mapOf("studio" to emptyList())))
  }

  @Test
  fun nameSortsAscendingEverythingElseDescending() {
    assertEquals("ASC", SectionRules.defaultDir(null))
    assertEquals("ASC", SectionRules.defaultDir("name"))
    assertEquals("DESC", SectionRules.defaultDir("scenes"))
    assertEquals("DESC", SectionRules.defaultDir("age"))
  }

  @Test
  fun layoutAndWording() {
    assertEquals(5, SectionRules.columns(Section.PERFORMERS))
    assertEquals(4, SectionRules.columns(Section.STUDIOS))
    assertEquals(15, SectionRules.perPage(Section.PERFORMERS))
    assertEquals("Loading…", SectionRules.countLabel(Section.TAGS, true, 0))
    assertEquals("1 performer", SectionRules.countLabel(Section.PERFORMERS, false, 1))
    assertEquals("24 studios", SectionRules.countLabel(Section.STUDIOS, false, 24))
    assertEquals("0 tags", SectionRules.countLabel(Section.TAGS, false, 0))
    assertEquals("1 scene", SectionRules.countLabel(Section.SCENES, false, 1))
    assertEquals("‹ Studios", SectionRules.backLabel(Section.STUDIOS))
  }

  private val ann = Entity("1", "Ann Test", image = "/media/performer/1/thumb?v=3", sceneCount = 5, age = 36, country = "Norway", favorite = true, gender = "FEMALE")
  private val label = Entity("2", "Beta Label", sceneCount = 1, parent = Ref("1", "Alpha Network"))

  @Test
  fun cardAndPageFacts() {
    assertEquals("5 scenes · 36 y · Norway", SectionRules.cardMeta(Section.PERFORMERS, ann))
    assertEquals("1 scene · Alpha Network", SectionRules.cardMeta(Section.STUDIOS, label))
    assertEquals("1 scene", SectionRules.cardMeta(Section.TAGS, label.copy(parent = null)))
    assertEquals("5 scenes · 36 years · Norway · Female", SectionRules.pageFacts(ann))
    assertEquals("1 scene · part of Alpha Network", SectionRules.pageFacts(label))
  }

  @Test
  fun aPageIsThatFacetAloneAndStaysSoUntilTheChipChanges() {
    val f = SectionRules.facetOf(Section.PERFORMERS, ann)
    assertEquals(Facet(FacetType.PERFORMER, "1", "Ann Test"), f)
    val q = SectionRules.pageQuery(f, 12)
    assertEquals(listOf(f), q.facets)
    assertNull(q.filterId); assertNull(q.sort); assertEquals("", q.q)
    // The same facet-alone rule Storm uses to re-Storm (it also keeps the Storm's own sort).
    val storm = StormInfo.restormQuery(SceneQuery(filterId = "9", q = "x", sort = "date", perPage = 12), f)
    assertEquals(q.copy(sort = "date"), storm)
    assertTrue(SectionRules.stillPage(f, q.copy(q = "beach", sort = "title", page = 2)))
    assertFalse(SectionRules.stillPage(f, q.copy(facets = emptyList())))
    assertFalse(SectionRules.stillPage(f, q.copy(facets = q.facets + Facet(FacetType.TAG, "4", "Outdoors"))))
    assertFalse(SectionRules.stillPage(f, q.copy(filterId = "7")))
  }

  // ---- back stack ----

  @Test
  fun backClosesDetailsThenThePageThenNothing() {
    val page = Snapshot(libraryVisible = true, pageOpen = true, detailsOpen = true)
    assertEquals(Action.CLOSE_DETAILS, ControlScheme.back(page))
    assertEquals(Action.CLOSE_PAGE, ControlScheme.back(page.copy(detailsOpen = false)))
    assertEquals(Action.NONE, ControlScheme.back(page.copy(detailsOpen = false, pageOpen = false)))
    // A row (Sort) is newer than the page: closed first.
    val row = app.trove.quest.input.Row(app.trove.quest.input.Row.Kind.SORT, app.trove.quest.input.Row.WALL)
    assertEquals(Action.CLOSE_ROW, ControlScheme.back(page.copy(rows = listOf(row))))
    // Over a playing video the library hides first; the page is still there when it comes back.
    assertEquals(Action.HIDE_LIBRARY, ControlScheme.back(Snapshot(screens = 1, libraryVisible = true, pageOpen = false)))
    assertEquals(Action.CLOSE_PAGE, ControlScheme.back(Snapshot(screens = 1, libraryVisible = true, pageOpen = true)))
    // Hidden library: the page is not on screen, B closes the video.
    assertEquals(Action.CLOSE_SCREEN, ControlScheme.back(Snapshot(screens = 1, libraryVisible = false, pageOpen = true)))
    // Held B on a page goes home (which also leaves the page).
    assertEquals(Action.HOME, ControlScheme.backLong(page.copy(detailsOpen = false)))
  }

  @Test
  fun theLegendNamesTheBackAction() {
    val lines = ControlScheme.legend(Snapshot(pageOpen = true), app.trove.quest.input.Hand.RIGHT)
    assertTrue(lines.any { it.text == Action.CLOSE_PAGE.label })
  }

  // ---- API ----

  private fun api() = TroveApi("https://t.example/", PersistentCookieJar(MemoryCookieStorage()))

  @Test
  fun entityRequestPaths() {
    val a = api()
    assertEquals("api/performers?perPage=24", a.entitiesPath(EntityQuery(Section.PERFORMERS)))
    assertEquals(
        "api/studios?q=foo%20bar&sort=random&dir=ASC&page=3&seed=42&favorite=1&perPage=12",
        a.entitiesPath(EntityQuery(Section.STUDIOS, q = " foo bar ", sort = "random", dir = "ASC", seed = 42, page = 3, perPage = 12, favorite = true)),
    )
    assertEquals("api/tags?sort=scenes&perPage=12", a.entitiesPath(EntityQuery(Section.TAGS, sort = "scenes", perPage = 12)))
    assertTrue(runCatching { a.entitiesPath(EntityQuery(Section.SCENES)) }.isFailure)
  }

  @Test
  fun entityThumbsResolveAgainstTheServer() {
    val a = api()
    assertEquals("https://t.example/media/performer/1/thumb?v=3&w=320", a.entityThumbUrl(ann))
    assertNull(a.entityThumbUrl(label))
  }

  // ---- model ----

  private lateinit var server: MockWebServer
  private val executor = Executors.newSingleThreadExecutor()
  private val main = executor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + main)
  private val store = HashMap<String, String>()
  private val paths = CopyOnWriteArrayList<String>()

  private val prefs: SharedPreferences =
      Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, args ->
        when (m.name) {
          "getString" -> store[args[0] as String] ?: args[1]
          "edit" ->
              Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { p, em, eargs ->
                if (em.name == "putString") store[eargs[0] as String] = eargs[1] as String
                if (em.name == "apply" || em.name == "commit") null else p
              }
          else -> null
        }
      } as SharedPreferences

  @Before
  fun setUp() {
    server = MockWebServer()
    server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val p = request.path.orEmpty()
            paths += p
            val body =
                when {
                  p.startsWith("/api/performers/1") -> """{"performer":{"id":"1","name":"Ann Test","sceneCount":5,"aliases":["Annie"],"details":"About her"}}"""
                  p.startsWith("/api/performers") ->
                      """{"count":30,"items":[{"id":"1","name":"Ann Test","sceneCount":5},{"id":"2","name":"Bob Sample","sceneCount":2}]}"""
                  p.startsWith("/api/studios") -> """{"count":1,"items":[{"id":"7","name":"Alpha Network","sceneCount":3}]}"""
                  p.startsWith("/api/scenes") -> """{"count":1,"scenes":[{"id":"9","title":"Made up"}]}"""
                  p.startsWith("/api/filters") -> """{"filters":[],"sorts":["date"],"sections":{"sorts":{"performer":["name","age"]}}}"""
                  else -> return MockResponse().setResponseCode(404).setBody("""{"error":"no"}""")
                }
            return MockResponse().setBody(body)
          }
        }
    server.start()
  }

  @After
  fun tearDown() {
    scope.cancel()
    executor.shutdownNow()
    server.shutdown()
  }

  private fun waitFor(what: String, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + 5000
    while (!cond()) {
      if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
      Thread.sleep(20)
    }
  }

  private fun model() =
      AppModel(TroveApi(server.url("/").toString(), PersistentCookieJar(MemoryCookieStorage())), prefs, scope, onPlay = {}, onLoggedOut = {})

  private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

  @Test
  fun openingAPerformerShowsItsScenesAndBackReturnsToTheGrid() {
    val m = model()
    onMain { m.selectSection(Section.PERFORMERS) }
    waitFor("performers") { m.entities.size == 2 }
    assertEquals(30, m.entityTotal)
    assertEquals(2, m.pages) // 30 performers at 15 a page
    assertEquals("30 performers", m.countLabel)

    onMain { m.searchSetAndRun("ann") }
    waitFor("search") { paths.any { it.startsWith("/api/performers?q=ann") } }

    onMain { m.openEntity(m.entities[0]) }
    assertEquals(Section.SCENES, m.section)
    assertEquals(listOf(Facet(FacetType.PERFORMER, "1", "Ann Test")), m.query.facets)
    assertEquals(Section.PERFORMERS, m.page?.origin)
    waitFor("scenes") { m.scenes.size == 1 }
    waitFor("details") { m.page?.entity?.aliases == listOf("Annie") }
    assertTrue(paths.any { it.startsWith("/api/scenes?performers=1") })

    // Searching inside the page keeps it; adding another chip ends it.
    onMain { m.search() }
    assertEquals(Section.PERFORMERS, m.page?.origin)

    onMain { m.closePage() }
    assertNull(m.page)
    assertEquals(Section.PERFORMERS, m.section)
    assertTrue(m.query.facets.isEmpty())
    waitFor("grid again") { m.entities.size == 2 }
    // The grid comes back as it was left: the search is still there.
    assertEquals("ann", m.entityQuery.q)
    assertEquals("ann", m.searchText)
  }

  @Test
  fun removingTheFacetEndsThePageAndTheSectionIsRemembered() {
    val m = model()
    onMain { m.selectSection(Section.STUDIOS) }
    waitFor("studios") { m.entities.size == 1 }
    assertEquals("STUDIOS", store["wall_section"])
    onMain { m.openEntity(m.entities[0]) }
    assertEquals(FacetType.STUDIO, m.query.facets.single().type)
    onMain { m.removeFacet(m.query.facets.single()) }
    assertNull(m.page)
    assertEquals(Section.SCENES, m.section)
    // A new model (next launch) opens on the remembered section.
    store["wall_section"] = "PERFORMERS"
    assertEquals(Section.PERFORMERS, model().section)
  }

  @Test
  fun eachGridKeepsItsOwnSearchSortAndPage() {
    val m = model()
    onMain { m.selectSection(Section.PERFORMERS) }
    waitFor("performers") { m.entities.isNotEmpty() }
    onMain { m.selectSort("age") }
    onMain { m.goToPage(2) }
    assertEquals(2, m.entityQuery.page)
    assertEquals("DESC", m.currentDir)
    onMain { m.selectSection(Section.STUDIOS) }
    waitFor("studios") { m.entities.size == 1 }
    assertEquals(1, m.entityQuery.page)
    assertNull(m.entityQuery.sort)
    assertEquals("ASC", m.currentDir)
    onMain { m.selectSection(Section.PERFORMERS) }
    assertEquals("age", m.entityQuery.sort)
    assertEquals(2, m.entityQuery.page)
    onMain { m.toggleFavorites() }
    assertTrue(m.entityQuery.favorite)
    waitFor("favorite request") { paths.any { it.contains("favorite=1") } }
  }

  @Test
  fun selectingScenesFromAPageLeavesItUnfiltered() {
    val m = model()
    onMain { m.selectSection(Section.TAGS) }
    onMain { m.openEntity(Entity("4", "Outdoors", sceneCount = 4)) }
    assertEquals(Section.TAGS, m.page?.origin)
    onMain { m.selectSection(Section.SCENES) }
    assertNull(m.page)
    assertTrue(m.query.facets.isEmpty())
    assertEquals(Section.SCENES, m.section)
  }
}

private fun AppModel.searchSetAndRun(text: String) {
  typeSearch(text)
  search()
}
