package app.trove.quest

import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.TroveApi
import app.trove.quest.ui.Facets
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FacetSearchTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After fun tearDown() = server.shutdown()

  private fun api() = TroveApi(server.url("/").toString(), PersistentCookieJar(MemoryCookieStorage()))

  private val perf1 = Facet(FacetType.PERFORMER, "11", "Performer One")
  private val perf2 = Facet(FacetType.PERFORMER, "12", "Performer Two")
  private val studio = Facet(FacetType.STUDIO, "3", "Some Studio")
  private val tag = Facet(FacetType.TAG, "40", "Some Tag")

  @Test
  fun facetsGoIntoTheSceneQueryAsCommaSeparatedIdsPerType() {
    val path = api().scenesPath(SceneQuery(filterId = "7", facets = listOf(perf1, tag, perf2, studio)))
    val u = server.url("/").resolve(path)!!
    assertEquals("7", u.queryParameter("filter"))
    assertEquals("11,12", u.queryParameter("performers"))
    assertEquals("3", u.queryParameter("studios"))
    assertEquals("40", u.queryParameter("tags"))
  }

  @Test
  fun noFacetsNoFacetParams() {
    assertEquals("api/scenes?perPage=12", api().scenesPath(SceneQuery()))
  }

  @Test
  fun suggestParsesItemsAndDropsUnknownKinds() = runBlocking {
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"items":[{"type":"performer","id":"11","name":"Performer One","count":5},
                  {"type":"studio","id":"3","name":"Some Studio","count":2},
                  {"type":"group","id":"9","name":"Not a facet","count":1},
                  {"type":"tag","id":"40","name":"Some Tag","count":30}]}"""))
    val items = api().suggest(" pe ")
    assertEquals(listOf("performer", "studio", "tag"), items.map { it.type })
    assertEquals(perf1, items[0].toFacet())
    assertEquals(5, items[0].count)
    val req = server.takeRequest()
    assertEquals("/api/suggest?q=pe", req.path)
  }

  @Test
  fun suggestEncodesTheQuery() = runBlocking {
    server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}"""))
    api().suggest("a&b c")
    assertEquals("a&b c", server.takeRequest().requestUrl!!.queryParameter("q"))
  }

  @Test
  fun suggestBelowTwoCharactersAsksNothing() = runBlocking {
    assertTrue(api().suggest(" a ").isEmpty())
    assertEquals(0, server.requestCount)
  }

  @Test
  fun facetsHaveNoDuplicatesAndRemoveByIdentity() {
    var list = Facets.add(emptyList(), perf1)
    list = Facets.add(list, studio)
    list = Facets.add(list, perf1.copy(name = "renamed"))
    assertEquals(listOf(perf1, studio), list)
    // Same id, other kind: a different facet.
    list = Facets.add(list, Facet(FacetType.TAG, "11", "Tag Eleven"))
    assertEquals(3, list.size)
    assertEquals(listOf(studio, Facet(FacetType.TAG, "11", "Tag Eleven")), Facets.remove(list, perf1.copy(name = "")))
  }
}
