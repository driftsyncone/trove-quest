package app.trove.quest.player

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.api.Scene
import app.trove.quest.play.RemoteResume
import app.trove.quest.play.ResumeMerge
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where a scene was left ([ms], scene time) and when that was written ([at], wall clock ms). */
@Serializable data class ResumePoint(val ms: Long, val at: Long)

/**
 * Resume positions per scene id (pure, unit tested in ResumePointsTest). Kept on the headset only:
 * a resume point is a convenience, losing it costs nothing.
 *
 * Rules: the first [MIN_MS] of a scene never count (opening a scene and closing it again must not
 * wipe where you were); reaching the last [END_MARGIN_MS] (or [END_FRACTION]) counts as finished and
 * forgets the point; at most [MAX_ENTRIES] scenes, the oldest go first.
 */
class ResumeBook(entries: Map<String, ResumePoint> = emptyMap()) {
  private val points = entries.toMutableMap()

  val entries: Map<String, ResumePoint>
    get() = points

  fun get(sceneId: String): Long? = points[sceneId]?.ms

  fun point(sceneId: String): ResumePoint? = points[sceneId]

  /** True if anything changed (so it is worth saving). [durationMs] <= 0 = unknown. */
  fun record(sceneId: String, positionMs: Long, durationMs: Long, now: Long): Boolean {
    if (positionMs < MIN_MS) return false
    if (finished(positionMs, durationMs)) return points.remove(sceneId) != null
    val old = points[sceneId]
    // Same second: not worth a write (the autosave calls this every few seconds while paused too).
    if (old != null && old.ms / 1000 == positionMs / 1000) return false
    points[sceneId] = ResumePoint(positionMs, now)
    if (points.size > MAX_ENTRIES) {
      points.entries.sortedBy { it.value.at }.take(points.size - MAX_ENTRIES).forEach { points.remove(it.key) }
    }
    return true
  }

  fun forget(sceneId: String): Boolean = points.remove(sceneId) != null

  companion object {
    const val MIN_MS = 30_000L
    const val END_MARGIN_MS = 30_000L
    const val END_FRACTION = 0.95
    const val MAX_ENTRIES = 300

    fun finished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && (positionMs >= durationMs - END_MARGIN_MS || positionMs >= durationMs * END_FRACTION)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ResumePoint.serializer())

    fun encode(book: ResumeBook): String = json.encodeToString(serializer, book.points)

    /** Anything malformed = an empty book (resume points are not worth crashing over). */
    fun decode(text: String?): ResumeBook =
        if (text.isNullOrBlank()) ResumeBook()
        else runCatching { ResumeBook(json.decodeFromString(serializer, text)) }.getOrElse { ResumeBook() }
  }
}

/**
 * [ResumeBook] in SharedPreferences. [PlayerManager] records every open screen with its layout
 * autosave, on the Meta menu and when a screen closes; the wall and new screens read it for
 * "Resume at mm:ss".
 *
 * Play tracking: the server's resume point (shared with the Stash UI and the web player) comes from
 * `GET /api/scenes/<id>` and from the answers of the activity reports ([noteRemote]); [get] merges it
 * with the local point ([ResumeMerge]) and falls back to the local one when the server is unknown
 * (offline, older server). The local copy stays as the cache.
 */
class ResumeStore(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
  private val book = ResumeBook.decode(prefs.getString(PREF_KEY, null))

  /** Bumped on every change, so Compose re-reads [get] (the details' "Resume at"). */
  var version by mutableIntStateOf(0)
    private set

  private val remote = HashMap<String, RemoteResume>()

  /** The point to offer: the server's if known, else the local one. */
  fun get(sceneId: String): Long? = ResumeMerge.merge(remote[sceneId], book.point(sceneId))

  /** What the server knows about a scene (newest answer wins). */
  fun noteRemote(sceneId: String, r: RemoteResume) {
    if (remote.put(sceneId, r) != r) version++
  }

  fun noteScene(scene: Scene) =
      noteRemote(scene.id, ResumeMerge.remoteOf(scene.resumeTime, scene.lastPlayedAt, (scene.duration * 1000).toLong()))

  fun record(sceneId: String, positionMs: Long, durationMs: Long) {
    if (book.record(sceneId, positionMs, durationMs, now())) save()
  }

  fun forget(sceneId: String) {
    if (book.forget(sceneId)) save()
  }

  private fun save() {
    prefs.edit().putString(PREF_KEY, ResumeBook.encode(book)).apply()
    version++
  }

  companion object {
    const val PREF_KEY = "resume_points"
  }
}
