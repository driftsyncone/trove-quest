package app.trove.quest.sync

import app.trove.quest.api.TroveApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The sync link as the UI shows it. */
enum class SyncLink {
  /** Not logged in, or the app is not in use: no socket. */
  OFF,
  /** First connect since it was switched on. */
  CONNECTING,
  CONNECTED,
  /** Lost: retrying with backoff. */
  RECONNECTING,
}

/**
 * [stopped]: the server's latch (STOP ALL, a dead-man stop, or "not started yet" after a server
 * start). [byPartner]: the latch came from the partner's STOP ALL, which the app shows loudly;
 * the others are the console's business.
 */
data class SyncState(
    val link: SyncLink = SyncLink.OFF,
    val stopped: Boolean = false,
    val byPartner: Boolean = false,
    /** The latch is the wearer's own escape ([SyncProtocol.REASON_WEARER]): shown as "Stopped — resume from the console". */
    val byWearer: Boolean = false,
    val reason: String? = null,
    val device: String? = null,
    /** The server drives a real toy (status `armed`); false = its fake driver, null = not reported (older server). */
    val armed: Boolean? = null,
    /** What the headset's own Start / Resume would do now (status `resume`); see [ToyResume]. */
    val resume: ResumeMode = ResumeMode.NO,
    /** The VacuGlide as the server last reported it (status `vg`); the default (not connected) before one and from an older server. */
    val vg: VgState = VgState(),
    /** The script variants of the scene the toy follows (status `script`); null = none / one / an older server. */
    val script: ScriptState? = null,
    /** The session timer as the server last reported it (status `timer`); null = none. */
    val timer: TimerState? = null,
    /**
     * The server's clock minus this headset's (from hello `serverNow`; 0 before one and from an older
     * server). The timer counts [TimerState.endsAt] down on local time + this, so a Quest whose clock is
     * off still shows the right time left.
     */
    val clockOffsetMs: Long = 0L,
    /** A short-lived tag text ("Black Out lifted"), set by the activity, not by the server. */
    val notice: String? = null,
)

/**
 * The app as a funscript-sync "player" (Trove server/sync.ts, README "Funscript sync and
 * partner console"): a WebSocket to `/api/sync/ws?role=player&client=quest` with the normal
 * session cookie (the native app sends no Origin, which the server allows for players).
 *
 * It reports the open screens and headset status ([SyncReporter] decides when) and hands the
 * server's commands to [onCommand]. The server drives the toy from the focused screen's reported
 * clock and stops it when the reports stop, so this only needs to keep reporting while the app is
 * in use; it never talks to a device itself.
 *
 * Everything runs on [scope] (the main thread in the app, where ExoPlayer may be read); OkHttp's
 * callbacks are posted onto it, tagged with the socket they belong to so a late callback of an
 * old socket changes nothing.
 */
class SyncClient(
    private val api: TroveApi,
    private val scope: CoroutineScope,
    /** The open screens, read on [scope] at every poll. */
    private val screens: () -> List<SyncScreen>,
    /** Flat headset values for the console (battery, charging, inOurApp, ...). */
    private val headset: () -> Map<String, Any>,
    private val onCommand: (ServerMessage.Command) -> Unit,
    /** Monotonic ms. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clientName: String = "quest",
    /** What the headset really shows (Black Out), for the heartbeat; null = not reported. */
    private val blackout: () -> Boolean? = { null },
    /** The socket dropped or was closed by us: Black Out must lift locally. */
    private val onLinkLost: () -> Unit = {},
    /** A Director command (open / swap / close ...): obey on [scope], then answer with [sendAck]. */
    private val onDirector: (DirectorCommand) -> Unit = {},
    /** A wearer stop that never reached the server was dropped as stale (see [EscapeRules]). */
    private val onEscapeDropped: () -> Unit = {},
    /** Where the head points and which screen it faces (the console's map); null = not reported. */
    private val view: () -> HeadView? = { null },
    /** The server's answer to a VacuGlide message ([VacuGlideControl] shows a refusal). */
    private val onVgAck: (ServerMessage.VgAck) -> Unit = {},
    /** The session timer's 5 / 1 min warnings and the answers to [sendTimer]. */
    private val onTimer: (ServerMessage) -> Unit = {},
    /** Wall clock (epoch ms), against the server's hello `serverNow`. */
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
  private val _state = MutableStateFlow(SyncState())
  val state: StateFlow<SyncState> = _state.asStateFlow()

  /** Long-lived socket: no read timeout (the server may say nothing for minutes), OkHttp pings for dead links. */
  private val http: OkHttpClient =
      api.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(PING_S, TimeUnit.SECONDS).build()

  private val reporter = SyncReporter()
  private var active = false
  private var socket: WebSocket? = null
  private var open = false
  private var retryMs = RETRY_MIN_MS
  private var retryJob: Job? = null
  private var pollJob: Job? = null

  /** Clock time of a wearer stop that the dead link could not carry; sent after the reconnect if still relevant. */
  private var escapeQueuedAt: Long? = null

  /** A wearer stop is waiting for the link. */
  val escapeQueued: Boolean
    get() = escapeQueuedAt != null

  /** Server's heartbeat request from its hello (1 s today); the reporter sends more often anyway. */
  var heartbeatMs = 1000L
    private set

  /** Logged in and the app in use: connect (and keep reconnecting); false closes the socket. */
  fun setActive(on: Boolean) {
    if (on == active) return
    active = on
    if (on) {
      retryMs = RETRY_MIN_MS
      _state.value = SyncState(link = SyncLink.CONNECTING)
      connect()
      pollJob =
          scope.launch {
            while (true) {
              delay(POLL_MS)
              report()
            }
          }
    } else {
      pollJob?.cancel()
      retryJob?.cancel()
      socket?.close(1000, "app not in use")
      socket = null
      open = false
      onLinkLost()
      _state.value = SyncState()
    }
  }

  /** Tell the server what the headset shows at once (reply to a blackout command, or the wearer's lift). */
  fun sendBlackout(on: Boolean, seq: Long? = null, liftedBy: String? = null) {
    if (open) socket?.send(SyncProtocol.encodeBlackout(on, seq, liftedBy))
  }

  /** Answer a Director command ([DirectorCommand.seq]): done, or failed with [error]. */
  fun sendAck(seq: Long, ok: Boolean, error: String? = null) {
    if (open) socket?.send(SyncProtocol.encodeAck(seq, ok, error))
  }

  /**
   * The wearer's escape: tell the server now. With the link down it is kept and sent after the
   * reconnect, once, only if the server is not stopped by then and it is not stale; it never
   * resumes anything.
   */
  fun sendWearerStop() {
    if (open && socket?.send(SyncProtocol.encodeWearerStop()) == true) {
      escapeQueuedAt = null
    } else {
      escapeQueuedAt = clock()
    }
  }

  /**
   * Start / Resume from the headset ([ToyResume]); [confirm] after the held press. Only sent with the
   * link up; the server refuses what the status did not offer, so nothing local changes here.
   */
  fun sendResume(confirm: Boolean = false): Boolean = open && socket?.send(SyncProtocol.encodeResume(confirm)) == true

  /** A VacuGlide valve press or release; false when the link is down (nothing was sent). */
  fun sendVgValve(key: ValveKey, on: Boolean): Boolean = open && socket?.send(SyncProtocol.encodeVgValve(key, on)) == true

  fun sendVgSet(offsetMs: Int? = null, intensity: Int? = null, speedLimit: Int? = null, suction: Int? = null): Boolean =
      open && socket?.send(SyncProtocol.encodeVgSet(offsetMs, intensity, speedLimit, suction)) == true

  fun sendVgReload(): Boolean = open && socket?.send(SyncProtocol.encodeVgReload()) == true

  fun sendVgScript(sceneId: String, id: String): Boolean = open && socket?.send(SyncProtocol.encodeVgScript(sceneId, id)) == true

  /** Set / extend / cancel the session timer; false when the link is down (nothing was sent). */
  fun sendTimer(op: TimerOp): Boolean = open && socket?.send(SyncProtocol.encodeTimer(op)) == true

  /** Send now (after a command was obeyed, or a change the poll would only see later). */
  fun reportNow() = report(force = true)

  private fun connect() {
    if (!active) return
    val url =
        api.baseUrl.resolve("api/sync/ws")!!.newBuilder()
            .addQueryParameter("role", "player")
            .addQueryParameter("client", clientName)
            .addQueryParameter("caps", "director")
            .build()
    open = false
    // Callbacks get the same instance newWebSocket returns, so they can tell an old socket's apart.
    socket =
        http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) = post(webSocket) { opened() }

              override fun onMessage(webSocket: WebSocket, text: String) = post(webSocket) { message(text) }

              override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                post(webSocket) { lost(unauthorized = false) }
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                  post(webSocket) { lost(unauthorized = response?.code == 401) }
            })
  }

  private inline fun post(ws: WebSocket, crossinline block: () -> Unit) {
    scope.launch { if (ws === socket && active) block() }
  }

  private fun opened() {
    open = true
    retryMs = RETRY_MIN_MS
    _state.value = _state.value.copy(link = SyncLink.CONNECTED)
    reporter.reset()
    report(force = true)
  }

  private fun message(text: String) {
    when (val m = SyncProtocol.decode(text)) {
      is ServerMessage.Hello -> {
        heartbeatMs = m.heartbeatMs
        _state.value = _state.value.copy(clockOffsetMs = SessionTimer.clockOffsetMs(m.serverNow, wallClock()))
      }
      is ServerMessage.Status -> {
          _state.value =
              _state.value.copy(
                  stopped = m.stopped,
                  byPartner = m.stopped && m.reason?.startsWith(SyncProtocol.REASON_STOP_ALL) == true,
                  byWearer = m.stopped && m.reason == SyncProtocol.REASON_WEARER,
                  reason = m.reason,
                  device = m.device,
                  armed = m.armed,
                  resume = m.resume,
                  vg = m.vg ?: VgState(),
                  script = m.script,
                  timer = m.timer,
              )
          statusArrived(m.stopped)
      }
      is ServerMessage.VgAck -> onVgAck(m)
      is ServerMessage.TimerAck, is ServerMessage.TimerWarn -> onTimer(m)
      is ServerMessage.Director -> {
        onDirector(m.command)
        report(force = true)
      }
      is ServerMessage.DirectorInvalid -> if (m.seq > 0) sendAck(m.seq, false, "Bad ${m.cmd} command")
      is ServerMessage.Command -> {
        onCommand(m)
        report(force = true)
      }
      null -> Unit
    }
  }

  /** The status after a (re)connect decides what becomes of a wearer stop that was queued. */
  private fun statusArrived(stopped: Boolean) {
    val at = escapeQueuedAt ?: return
    if (EscapeRules.stillRelevant(at, clock(), stopped)) {
      sendWearerStop()
    } else {
      escapeQueuedAt = null
      if (!stopped) onEscapeDropped()
    }
  }

  /**
   * Socket gone: retry with backoff. The server has already stopped the toy if it was ours (its
   * dead-man rule); the latch stays shown until the server says otherwise after the reconnect.
   */
  private fun lost(unauthorized: Boolean) {
    socket = null
    open = false
    onLinkLost()
    _state.value = _state.value.copy(link = SyncLink.RECONNECTING, vg = VgState())
    val wait = if (unauthorized) RETRY_UNAUTHORIZED_MS else retryMs
    retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
    retryJob?.cancel()
    retryJob =
        scope.launch {
          delay(wait)
          if (active && socket == null) connect()
        }
  }

  private fun report(force: Boolean = false) {
    val ws = socket ?: return
    if (!open) return
    val list = screens()
    val v = view()
    if (!reporter.shouldSend(list, clock(), force, v?.key().orEmpty())) return
    ws.send(SyncProtocol.encodeState(list, headset(), blackout(), v))
  }

  companion object {
    /** The poll; sends happen on change or every [SyncReporter.HEARTBEAT_MS]. */
    const val POLL_MS = 250L
    const val RETRY_MIN_MS = 1000L
    const val RETRY_MAX_MS = 5000L
    /** Upgrade refused with 401 (session ended): the wall's next API call shows the login. */
    const val RETRY_UNAUTHORIZED_MS = 30_000L
    const val PING_S = 15L
  }
}
