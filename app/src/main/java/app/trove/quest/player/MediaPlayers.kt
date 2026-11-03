package app.trove.quest.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Every ExoPlayer in the app comes from here, so they share one memory budget (0.2.8 crashed with
 * OutOfMemoryError on the 256 MB Java heap):
 * - **Buffer cap**: ExoPlayer's default video buffer is up to 2000 × 64 KB ≈ 128 MB of Java heap per
 *   player, a few seconds of 8K; two screens filled the heap. [bufferBytes] caps it per player, and
 *   size wins over time (`prioritizeTimeOverSizeThresholds = false`).
 * - **HTTP/1.1 for media**: over HTTP/2 (NPM speaks h2) all streams share one connection, and OkHttp
 *   reads every stream's frames into heap buffers whether the player is reading or not (a paused
 *   screen kept receiving until its 16 MB window was full); the OOMs were thrown in Http2Reader. With
 *   HTTP/1.1 each stream has its own connection and a paused one just stops being read.
 * - **Own dispatcher and pool**: ExoPlayer and Coil both `enqueue` their calls, and OkHttp's default
 *   dispatcher lets only 5 run per host until their headers arrive. Storm starts up to ~14 tiles at
 *   once, so they used to start in waves of 5 (and covers waited behind them). Media gets its own
 *   [Dispatcher] ([MAX_REQUESTS_PER_HOST]) and [ConnectionPool] ([IDLE_CONNECTIONS]), so a Storm cut
 *   reuses a warm connection instead of evicting the API's HTTP/2 one from the shared 5-slot pool.
 */
@OptIn(UnstableApi::class)
object MediaPlayers {
  /** A screen (original or transcode): ~24 MB is several seconds of 8K HEVC and minutes of 720p. */
  const val SCREEN_BUFFER_BYTES = 24 * 1024 * 1024

  /** A small tile (Storm previews, short H.264). */
  const val TILE_BUFFER_BYTES = 4 * 1024 * 1024

  /** Media calls in their header phase at once, per host: Storm's tiles + up to 6 screens and their pending swaps. */
  const val MAX_REQUESTS_PER_HOST = 32

  /** Idle media connections kept warm (one per Storm tile and screen, roughly). */
  const val IDLE_CONNECTIONS = 20

  private var mediaHttp: Pair<OkHttpClient, OkHttpClient>? = null

  /**
   * [api] with HTTP/1.1 only and its own dispatcher and connection pool (same cookies, interceptors,
   * timeouts otherwise).
   */
  @Synchronized
  fun mediaClient(api: OkHttpClient): OkHttpClient {
    mediaHttp?.let { (from, to) -> if (from === api) return to }
    val dispatcher =
        Dispatcher().apply {
          maxRequests = MAX_REQUESTS_PER_HOST * 2
          maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }
    val c =
        api.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
            .build()
    mediaHttp = api to c
    return c
  }

  /** [bufferMs]: optional min, max, playback, rebuffer durations (ms) for DefaultLoadControl. */
  fun create(context: Context, http: OkHttpClient, bufferBytes: Int = SCREEN_BUFFER_BYTES, bufferMs: IntArray? = null): ExoPlayer =
      ExoPlayer.Builder(context)
          .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
          .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(mediaClient(http))))
          .setLoadControl(
              DefaultLoadControl.Builder()
                  .apply { bufferMs?.let { setBufferDurationsMs(it[0], it[1], it[2], it[3]) } }
                  .setTargetBufferBytes(bufferBytes)
                  .setPrioritizeTimeOverSizeThresholds(false)
                  .build())
          .build()
}
