package app.trove.quest.update

import android.app.Activity
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.api.ApiException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data object UpToDate : UpdateState

  /** The server has update support but nothing published. */
  data object NoRelease : UpdateState

  /** The server is too old for updates (no /app/ routes). */
  data object Unsupported : UpdateState

  data class Available(val info: UpdateInfo) : UpdateState

  data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState

  /** Handed to the system installer; its confirm dialog may be up. */
  data class Installing(val info: UpdateInfo, val waitingForUser: Boolean = false) : UpdateState

  /** The system reported success; Android stops the old app right after. */
  data class Installed(val info: UpdateInfo) : UpdateState

  data class Failed(val info: UpdateInfo?, val message: String) : UpdateState
}

/**
 * Self-update state for the wall: checks GET /app/quest/latest.json on launch and when the wall
 * opens (at most every [UpdateThrottle.INTERVAL_MS]), or on "Check for updates"; downloads with
 * the session cookie, verifies SHA-256 and installs through [ApkInstaller]. Main thread only.
 */
class UpdateModel(
    private val activity: Activity,
    private val client: UpdateClient,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    val installedVersionCode: Long,
    val installedVersionName: String,
    private val installer: ApkInstaller = ApkInstaller(activity),
    private val now: () -> Long = System::currentTimeMillis,
) {
  var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    private set

  /** Last check that got an answer from the server (ms, 0 = never). */
  var lastCheckMs by mutableLongStateOf(prefs.getLong(PREF_LAST_CHECK, 0L))
    private set

  /** The small About / updates area on the wall. */
  var aboutOpen by mutableStateOf(false)

  /**
   * Button hints: Auto (the legend shows by itself the first time after an update) or Off. A press
   * of the thumbstick always shows it (docs/control-scheme.md section 6).
   */
  var buttonHints by mutableStateOf(prefs.getBoolean(PREF_BUTTON_HINTS, true))
    private set

  fun toggleButtonHints() {
    buttonHints = !buttonHints
    prefs.edit().putBoolean(PREF_BUTTON_HINTS, buttonHints).apply()
  }

  /** "Later" hides the card for this version until the app restarts. */
  private var dismissedCode by mutableLongStateOf(0L)

  private var job: Job? = null
  private var pendingApk: File? = null
  private val downloadDir: File
    get() = File(activity.cacheDir, "updates")

  init {
    InstallResultReceiver.listener = ::onInstallEvent
  }

  /** The info of the update being offered or installed, if any. */
  val offered: UpdateInfo?
    get() =
        when (val s = state) {
          is UpdateState.Available -> s.info
          is UpdateState.Downloading -> s.info
          is UpdateState.Installing -> s.info
          is UpdateState.Installed -> s.info
          is UpdateState.Failed -> s.info
          else -> null
        }

  /** Whether the update card shows on the wall. */
  val cardVisible: Boolean
    get() {
      val info = offered ?: return false
      return state !is UpdateState.Available || info.versionCode != dismissedCode
    }

  private val busy: Boolean
    get() =
        job?.isActive == true ||
            (state as? UpdateState.Installing)?.waitingForUser == true

  /** Launch / wall opened: checks unless one ran in the last 6 h. Errors stay quiet. */
  fun checkIfDue() {
    if (busy || state is UpdateState.Available) return
    if (!UpdateThrottle.isDue(now(), lastCheckMs)) return
    check(manual = false)
  }

  /** "Check for updates": ignores the throttle and shows errors. */
  fun checkNow() {
    if (job?.isActive == true) return
    dismissedCode = 0
    check(manual = true)
  }

  private fun check(manual: Boolean) {
    val before = state
    if (manual) state = UpdateState.Checking
    job =
        scope.launch {
          try {
            val result = client.latest(installedVersionCode)
            lastCheckMs = now()
            prefs.edit().putLong(PREF_LAST_CHECK, lastCheckMs).apply()
            state =
                when (result) {
                  is LatestResult.Available -> UpdateState.Available(result.info)
                  is LatestResult.UpToDate -> UpdateState.UpToDate
                  LatestResult.NoRelease -> UpdateState.NoRelease
                  LatestResult.Unsupported -> UpdateState.Unsupported
                }
            if (result is LatestResult.Available) Log.i(TAG, "update available: ${result.info}")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            state =
                if (!manual) before
                else UpdateState.Failed(null, describe(e, "Couldn't check for updates"))
          }
        }
  }

  /** The card's Install button (also Retry after a failure). */
  fun install() {
    val info = offered ?: return
    if (job?.isActive == true) return
    job =
        scope.launch {
          try {
            var lastShown = -1L
            state = UpdateState.Downloading(info, 0, info.sizeBytes)
            val apk =
                client.download(info, downloadDir) { done, total ->
                  // ~200 updates for the whole download, not one per 64 KB chunk.
                  val step = (total / 200).coerceAtLeast(1)
                  if (done / step != lastShown || done == total) {
                    lastShown = done / step
                    scope.launch(Dispatchers.Main) { state = UpdateState.Downloading(info, done, total) }
                  }
                }
            installer.problemWith(apk)?.let { throw UpdateException(it) }
            pendingApk = apk
            // Always straight to a PackageInstaller session, even without "install unknown apps":
            // the system's own confirm flow (STATUS_PENDING_USER_ACTION) asks for it inline. 0.2.1
            // opened that Settings screen itself, and on Horizon OS closing it left focus on
            // vrshell's placeholder: Trove never resumed and looked frozen.
            Log.i(TAG, "installing ${info.label}; install unknown apps allowed=${installer.canInstall()}")
            startInstall(info, apk)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            state = UpdateState.Failed(info, describe(e, "Update failed"))
          }
        }
  }

  /** Activity resumed: a throttled check. Nothing in the install flow waits for this. */
  fun onResume() = checkIfDue()

  /** "Later": hide the card for this version (until the next app start or a manual check). */
  fun dismiss() {
    val info = offered ?: return
    when (state) {
      is UpdateState.Available -> dismissedCode = info.versionCode
      is UpdateState.Failed -> state = UpdateState.Available(info).also { dismissedCode = info.versionCode }
      else -> Unit
    }
  }

  private suspend fun startInstall(info: UpdateInfo, apk: File) {
    state = UpdateState.Installing(info)
    try {
      withContext(Dispatchers.IO) { installer.install(apk) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "install session failed", e)
      state = UpdateState.Failed(info, describe(e, "Install failed"))
    }
  }

  private fun onInstallEvent(event: InstallEvent) {
    val info = offered ?: return
    state =
        when (event) {
          InstallEvent.WaitingForUser -> UpdateState.Installing(info, waitingForUser = true)
          InstallEvent.Succeeded -> {
            pendingApk?.delete()
            pendingApk = null
            UpdateState.Installed(info)
          }
          is InstallEvent.Failed -> UpdateState.Failed(info, event.message)
        }
  }

  private fun describe(e: Exception, prefix: String): String =
      when {
        e is UpdateException -> e.message ?: prefix
        e is ApiException && e.status == 401 -> "$prefix: not logged in."
        e is ApiException -> "$prefix: ${e.message}"
        e is IOException -> "$prefix: can't reach the server (${e.message})."
        else -> "$prefix: ${e.message ?: e.toString()}"
      }

  companion object {
    const val TAG = "TroveUpdate"
    const val PREF_LAST_CHECK = "update_last_check_ms"
    const val PREF_BUTTON_HINTS = "button_hints"
  }
}
