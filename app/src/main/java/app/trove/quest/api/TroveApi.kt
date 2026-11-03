package app.trove.quest.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Non-2xx answer from the server. [status] 401 means "log in again". */
class ApiException(val status: Int, message: String) : IOException(message)

/**
 * Client for the Trove server. The app never talks to Stash itself: the server holds the Stash
 * API key and proxies media under /media/ behind the session cookie.
 *
 * [http] is shared with ExoPlayer and Coil so every media and thumbnail request carries the cookie.
 */
class TroveApi(
    baseUrl: String,
    val cookieJar: PersistentCookieJar,
    client: OkHttpClient? = null,
    /** Sent on every request; the server's /pair page shows it so the phone sees which device asks. */
    userAgent: String? = null,
) {
  val http: OkHttpClient =
      (client?.newBuilder()
              // Coil enqueues the covers: OkHttp's default 5 per host fetched a 12-card page in three
              // waves over the one HTTP/2 connection. (Media has its own client, MediaPlayers.)
              ?: OkHttpClient.Builder().dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }))
          .apply {
            if (userAgent != null) {
              addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            }
          }
          .cookieJar(cookieJar)
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .build()

  @Volatile
  var baseUrl: HttpUrl = normalize(baseUrl)
    private set

  fun setBaseUrl(url: String) {
    baseUrl = normalize(url)
  }

  /** True if a session cookie is stored for the current server (it may still have been revoked). */
  fun hasSession(): Boolean =
      cookieJar.hasCookie(baseUrl, SESSION_COOKIE)

  suspend fun me(): User = get<UserResponse>("api/me").user

  suspend fun login(name: String, password: String): User {
    val body = buildJsonObject {
      put("name", JsonPrimitive(name))
      put("password", JsonPrimitive(password))
    }
    return post<UserResponse>("api/login", body.toString()).user
  }

  /** Device pairing: asks the server for a code a logged-in phone can approve on /pair. */
  suspend fun pairStart(): PairStart = post("api/pair/start", "{}")

  /**
   * One poll. "approved" means the server set the normal session cookie (kept by [cookieJar]),
   * so the rest of the app works as after [login].
   */
  suspend fun pairPoll(pairId: String, secret: String): PairPoll {
    val body = buildJsonObject {
      put("pairId", JsonPrimitive(pairId))
      put("secret", JsonPrimitive(secret))
    }
    return post("api/pair/poll", body.toString())
  }

  /** Ends the session on the server (best effort) and forgets the cookie either way. */
  suspend fun logout() {
    try {
      post<Map<String, Boolean>>("api/logout", "{}")
    } catch (_: IOException) {
      // offline or already logged out: the local cookie goes regardless
    } finally {
      cookieJar.clear()
    }
  }

  suspend fun filters(): FiltersResponse = get("api/filters")

  suspend fun previewConfig(): PreviewConfig = get("api/preview-config")

  suspend fun scenes(q: SceneQuery): ScenesResponse = get(scenesPath(q))

  /** A page of performers, studios or tags ([EntityQuery.section] must not be SCENES). */
  suspend fun entities(q: EntityQuery): EntitiesResponse = get(entitiesPath(q))

  /** One performer / studio / tag with its details (the page header). */
  suspend fun entity(type: FacetType, id: String): Entity {
    val obj = get<kotlinx.serialization.json.JsonObject>("api/${Section.of(type).path}/$id")
    val el = obj[type.id] ?: throw ApiException(502, "Unexpected answer from the server")
    return JSON_PARSER.decodeFromJsonElement(Entity.serializer(), el)
  }

  suspend fun scene(id: String): Scene = get<SceneResponse>("api/scenes/$id").scene

  /** A scene's script variants and the user's choice (server README "Script variants"); choosing goes over the sync socket. */
  suspend fun sceneScripts(id: String): SceneScripts = get("api/scenes/$id/scripts")

  /**
   * Play tracking: one report for a scene (see play/ActivityReporter). 404 = the server has no
   * such endpoint; 502 = Stash refused the write, send the seconds again with the next report.
   */
  suspend fun activity(id: String, report: app.trove.quest.play.ActivityReport): app.trove.quest.play.ActivityAck =
      post("api/scenes/$id/activity", report.toJson())

  /**
   * Performer / studio / tag names matching [q] (the web search box's dropdown). The server answers
   * an empty list below 2 characters, so we don't ask.
   */
  suspend fun suggest(q: String): List<Suggestion> {
    val t = q.trim().take(100)
    if (t.length < 2) return emptyList()
    return get<SuggestResponse>("api/suggest?" + query("q" to t)).items.filter { FacetType.fromId(it.type) != null }
  }

  /** Saves a per-scene projection for everyone; null resets to the detected one. */
  suspend fun setProjection(id: String, projection: Projection?) {
    val body = buildJsonObject { put("projection", projection?.id?.let(::JsonPrimitive) ?: JsonNull) }
    send<Map<String, Boolean>>(
        Request.Builder().url(url("api/scenes/$id/projection")).put(body.toString().toRequestBody(JSON)))
  }

  // ---- media URLs (all behind the session cookie) ----

  fun streamUrl(sceneId: String): String = url("media/scene/$sceneId/stream").toString()

  /** A stream from Scene.streams; those URLs are server-relative ("/media/scene/..."). */
  fun resolve(path: String): String = baseUrl.resolve(path.trimStart('/'))?.toString() ?: path

  fun thumbUrl(card: Card, width: Int = 480): String =
      url("media/scene/${card.id}/thumb")
          .newBuilder()
          .addQueryParameter("w", width.toString())
          .addQueryParameter("v", card.thumbVersion)
          .build()
          .toString()

  /** [Entity.image] resolved against the server, sized for a grid card; null without an image. */
  fun entityThumbUrl(e: Entity, width: Int = 320): String? {
    val path = e.image ?: return null
    return baseUrl.resolve(path.trimStart('/'))?.newBuilder()?.addQueryParameter("w", width.toString())?.build()?.toString()
  }

  fun previewUrl(sceneId: String): String = url("media/scene/$sceneId/preview").toString()

  fun funscriptUrl(sceneId: String): String = url("media/scene/$sceneId/funscript").toString()

  // ---- plumbing ----

  fun scenesPath(q: SceneQuery): String {
    val b = url("api/scenes").newBuilder()
    q.filterId?.let { b.addQueryParameter("filter", it) }
    if (q.q.isNotBlank()) b.addQueryParameter("q", q.q.trim().take(200))
    for (type in FacetType.entries) {
      val ids = q.facets.filter { it.type == type }.map { it.id }
      if (ids.isNotEmpty()) b.addQueryParameter(type.param, ids.joinToString(","))
    }
    q.sort?.let { b.addQueryParameter("sort", it) }
    q.dir?.let { b.addQueryParameter("dir", it) }
    if (q.page > 1) b.addQueryParameter("page", q.page.toString())
    if (q.sort == "random") b.addQueryParameter("seed", q.seed.toString())
    b.addQueryParameter("perPage", q.perPage.toString())
    val u = b.build()
    return u.encodedPath.trimStart('/') + (u.encodedQuery?.let { "?$it" } ?: "")
  }

  fun entitiesPath(q: EntityQuery): String {
    val path = q.section.path ?: throw IllegalArgumentException("Scenes are not an entity list")
    val b = url("api/$path").newBuilder()
    if (q.q.isNotBlank()) b.addQueryParameter("q", q.q.trim().take(200))
    q.sort?.let { b.addQueryParameter("sort", it) }
    q.dir?.let { b.addQueryParameter("dir", it) }
    if (q.page > 1) b.addQueryParameter("page", q.page.toString())
    if (q.sort == "random") b.addQueryParameter("seed", q.seed.toString())
    if (q.favorite) b.addQueryParameter("favorite", "1")
    b.addQueryParameter("perPage", q.perPage.toString())
    val u = b.build()
    return u.encodedPath.trimStart('/') + (u.encodedQuery?.let { "?$it" } ?: "")
  }

  private fun query(vararg params: Pair<String, String>): String {
    val b = HttpUrl.Builder().scheme("https").host("x")
    for ((k, v) in params) b.addQueryParameter(k, v)
    return b.build().encodedQuery.orEmpty()
  }

  private fun url(path: String): HttpUrl =
      baseUrl.resolve(path) ?: throw IllegalArgumentException("Bad path $path")

  private suspend inline fun <reified T> get(path: String): T =
      send(Request.Builder().url(url(path)).get())

  private suspend inline fun <reified T> post(path: String, json: String): T =
      send(Request.Builder().url(url(path)).post(json.toRequestBody(JSON)))

  private suspend inline fun <reified T> send(builder: Request.Builder): T =
      withContext(Dispatchers.IO) {
        http.newCall(builder.header("Accept", "application/json").build()).execute().use { res ->
          val text = res.body.string()
          if (!res.isSuccessful) {
            val msg =
                runCatching { JSON_PARSER.decodeFromString<ErrorResponse>(text).error }.getOrNull()
                    ?: "HTTP ${res.code}"
            throw ApiException(res.code, msg)
          }
          JSON_PARSER.decodeFromString<T>(text)
        }
      }

  companion object {
    const val SESSION_COOKIE = "trove_session"
    const val DEFAULT_SERVER = "https://trove.example.com"

    /** Async calls (Coil's covers) in flight at once on the API client; a wall page is 12 cards. */
    const val MAX_REQUESTS_PER_HOST = 16
    private val JSON = "application/json; charset=utf-8".toMediaType()
    val JSON_PARSER = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    /** "vr.example.com" -> "https://vr.example.com/"; keeps a sub-path if the server lives under one. */
    fun normalize(input: String): HttpUrl {
      var s = input.trim()
      if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
      if (!s.endsWith("/")) s += "/"
      return s.toHttpUrl()
    }
  }
}
