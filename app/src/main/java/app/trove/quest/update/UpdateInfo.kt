package app.trove.quest.update

import app.trove.quest.api.TroveApi
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** Something about an update went wrong; [message] is shown to the user as is. */
class UpdateException(message: String) : IOException(message)

/** The server's GET /app/quest/latest.json: the newest published APK. */
@Serializable
data class UpdateInfo(
    /** Android versionCode; the only thing that decides "newer". */
    val versionCode: Long,
    val versionName: String = "",
    val sizeBytes: Long = 0,
    /** Lowercase hex SHA-256 of the APK. */
    val sha256: String,
    /** User-facing release notes (Markdown-ish lines from CHANGELOG.md). */
    val notes: String = "",
    /** Server-relative, e.g. "/app/quest/trove-0.2.0-2.apk". */
    val url: String,
    val publishedAt: String? = null,
) {
  /** "0.2.0", or the code when the name is missing. */
  val label: String
    get() = versionName.ifBlank { versionCode.toString() }
}

object UpdateRules {
  private val SHA256 = Regex("^[0-9a-f]{64}$")

  /** Parses and checks latest.json; anything unusable is an [UpdateException]. */
  fun parse(json: String): UpdateInfo {
    val info =
        try {
          TroveApi.JSON_PARSER.decodeFromString<UpdateInfo>(json)
        } catch (e: SerializationException) {
          throw UpdateException("The server's update info is not readable (${e.message?.take(80)})")
        } catch (e: IllegalArgumentException) {
          throw UpdateException("The server's update info is not readable (${e.message?.take(80)})")
        }
    val normalized = info.copy(sha256 = info.sha256.trim().lowercase())
    if (normalized.versionCode < 1) throw UpdateException("The server's update has no valid version code")
    if (!SHA256.matches(normalized.sha256)) throw UpdateException("The server's update has no valid checksum")
    if (normalized.url.isBlank()) throw UpdateException("The server's update has no download link")
    if (normalized.sizeBytes < 0) throw UpdateException("The server's update has an invalid size")
    return normalized
  }

  /**
   * CHANGELOG.md text as plain text for the card: wrapped list items joined back into one line
   * each, Markdown emphasis and backticks dropped.
   */
  fun displayNotes(notes: String): String =
      notes.trim().replace(Regex("\n[ \t]+(?=\\S)(?![-*] )"), " ").replace("**", "").replace("`", "")

  /**
   * versionCode decides; the name is only for people. Equal codes are not an update (same build
   * or a rebuild), and a lower code is never offered (Android refuses downgrades anyway).
   */
  fun isNewer(info: UpdateInfo, installedVersionCode: Long): Boolean = info.versionCode > installedVersionCode
}

/** At most one automatic check per [INTERVAL_MS]; "Check for updates" ignores it. */
object UpdateThrottle {
  const val INTERVAL_MS = 6L * 60 * 60 * 1000

  /**
   * [lastCheckMs] is the last check that got an answer (0 = never). A clock that went backwards
   * (last check "in the future") counts as due, so a bad clock can't block checks for days.
   */
  fun isDue(nowMs: Long, lastCheckMs: Long, intervalMs: Long = INTERVAL_MS): Boolean =
      lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs
}
