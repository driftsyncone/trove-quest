package app.trove.quest.api

import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where cookies live between app starts. One string per cookie, in Set-Cookie form. */
interface CookieStorage {
  fun load(): Set<String>

  fun save(cookies: Set<String>)
}

class SharedPrefsCookieStorage(private val prefs: SharedPreferences) : CookieStorage {
  override fun load(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

  override fun save(cookies: Set<String>) {
    prefs.edit().putStringSet(KEY, cookies).apply()
  }

  private companion object {
    const val KEY = "cookies"
  }
}

class MemoryCookieStorage : CookieStorage {
  private var data: Set<String> = emptySet()

  override fun load() = data

  override fun save(cookies: Set<String>) {
    data = cookies
  }
}

/**
 * Keeps the server's `trove_session` cookie (HttpOnly, Max-Age = session length) across restarts.
 * Only persistent cookies are written to storage; expired ones are dropped on load and on use.
 * The original request URL is stored alongside each cookie so Cookie.parse can re-validate it.
 */
class PersistentCookieJar(private val storage: CookieStorage) : CookieJar {
  private val cookies = mutableListOf<Cookie>()
  private val origins = mutableMapOf<String, String>()

  init {
    val now = System.currentTimeMillis()
    for (line in storage.load()) {
      val tab = line.indexOf('\t')
      if (tab < 0) continue
      val origin = line.substring(0, tab)
      val url = origin.toHttpUrlOrNull() ?: continue
      val c = Cookie.parse(url, line.substring(tab + 1)) ?: continue
      if (c.expiresAt > now) {
        cookies += c
        origins[key(c)] = origin
      }
    }
  }

  @Synchronized
  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    for (c in cookies) {
      this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
      if (c.expiresAt > System.currentTimeMillis()) {
        this.cookies += c
        origins[key(c)] = url.newBuilder().encodedPath("/").query(null).build().toString()
      }
    }
    persist()
  }

  @Synchronized
  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val now = System.currentTimeMillis()
    if (cookies.removeAll { it.expiresAt <= now }) persist()
    return cookies.filter { it.matches(url) }
  }

  @Synchronized
  fun hasCookie(url: HttpUrl, name: String): Boolean = loadForRequest(url).any { it.name == name }

  @Synchronized
  fun clear() {
    cookies.clear()
    origins.clear()
    persist()
  }

  private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

  private fun persist() {
    storage.save(
        cookies
            .filter { it.persistent }
            .map { c ->
              val origin = origins[key(c)] ?: "${if (c.secure) "https" else "http"}://${c.domain}/"
              "$origin\t$c"
            }
            .toSet())
  }
}
