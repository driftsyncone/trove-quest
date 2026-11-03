package app.trove.quest.update

import app.trove.quest.api.ApiException
import app.trove.quest.api.ErrorResponse
import app.trove.quest.api.TroveApi
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What GET /app/quest/latest.json said. */
sealed interface LatestResult {
  /** A newer build than the installed one. */
  data class Available(val info: UpdateInfo) : LatestResult

  /** The published build is the installed one (or older). */
  data class UpToDate(val info: UpdateInfo) : LatestResult

  /** The server supports updates but has nothing published (404). */
  data object NoRelease : LatestResult

  /** An older server without /app/ routes (it answers with the web app's HTML). */
  data object Unsupported : LatestResult
}

/**
 * Checks for and downloads app updates from the Trove server. [http] must carry the session
 * cookie (the app passes [TroveApi.http]); [resolve] turns a server-relative path into a URL.
 */
class UpdateClient(private val http: OkHttpClient, private val resolve: (String) -> String) {
  constructor(api: TroveApi) : this(api.http, api::resolve)

  /** Throws [ApiException] (401 = not logged in) or IOException when offline. */
  suspend fun latest(installedVersionCode: Long): LatestResult =
      withContext(Dispatchers.IO) {
        val req = Request.Builder().url(resolve(LATEST_PATH)).header("Accept", "application/json").build()
        http.newCall(req).execute().use { res ->
          val text = res.body.string()
          val json = res.header("Content-Type").orEmpty().contains("json")
          when {
            res.code == 404 && json -> LatestResult.NoRelease
            !res.isSuccessful -> throw apiError(res, text)
            !json -> LatestResult.Unsupported
            else -> {
              val info = UpdateRules.parse(text)
              if (UpdateRules.isNewer(info, installedVersionCode)) LatestResult.Available(info)
              else LatestResult.UpToDate(info)
            }
          }
        }
      }

  /**
   * Downloads [info]'s APK into [dir] and checks size and SHA-256; returns the verified file.
   * Resumes a partial download (Range) and reuses an already verified file. Other APKs in [dir]
   * (older downloads) are deleted. [onProgress] gets (bytes so far, total) on the IO thread.
   */
  suspend fun download(info: UpdateInfo, dir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
      withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "trove-${info.versionCode}.apk")
        val part = File(dir, target.name + ".part")
        dir.listFiles()?.filter { it != target && it != part }?.forEach { it.delete() }

        if (target.isFile && Sha256.matches(target, info.sha256)) {
          onProgress(target.length(), target.length())
          return@withContext target
        }
        target.delete()

        var offset = if (part.isFile && (info.sizeBytes <= 0 || part.length() < info.sizeBytes)) part.length() else 0L
        if (offset == 0L) part.delete()

        val req = Request.Builder().url(resolve(info.url))
        if (offset > 0) req.header("Range", "bytes=$offset-")
        http.newCall(req.build()).execute().use { res ->
          when (res.code) {
            206 -> Unit
            200 -> offset = 0 // server ignored the range: start over
            416 -> {
              part.delete()
              throw UpdateException("The download could not be resumed. Try again.")
            }
            else -> throw apiError(res, res.body.string())
          }
          val total = info.sizeBytes.takeIf { it > 0 } ?: (offset + res.body.contentLength()).coerceAtLeast(0)
          FileOutputStream(part, offset > 0).use { out ->
            res.body.byteStream().use { input ->
              val buf = ByteArray(1 shl 16)
              var done = offset
              onProgress(done, total)
              while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress(done, total)
              }
            }
          }
        }

        if (info.sizeBytes > 0 && part.length() != info.sizeBytes) {
          val got = part.length()
          if (got > info.sizeBytes) part.delete() // can't be fixed by resuming
          throw UpdateException("The download is incomplete ($got of ${info.sizeBytes} bytes). Try again.")
        }
        if (!Sha256.matches(part, info.sha256)) {
          part.delete()
          throw UpdateException("The download is damaged (checksum mismatch). Try again.")
        }
        if (!part.renameTo(target)) throw UpdateException("Could not save the download")
        target
      }

  private fun apiError(res: Response, text: String): ApiException {
    val msg =
        runCatching { TroveApi.JSON_PARSER.decodeFromString<ErrorResponse>(text).error }.getOrNull()
            ?: "HTTP ${res.code}"
    return ApiException(res.code, msg)
  }

  companion object {
    const val LATEST_PATH = "app/quest/latest.json"
  }
}
