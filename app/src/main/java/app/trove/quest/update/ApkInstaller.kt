package app.trove.quest.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/** What the system said about an install session (see [InstallResultReceiver]). */
sealed interface InstallEvent {
  /** The system's confirm dialog is up ("Do you want to update this app?"). */
  data object WaitingForUser : InstallEvent

  data object Succeeded : InstallEvent

  data class Failed(val message: String) : InstallEvent
}

/**
 * Installs a downloaded APK over ourselves with a PackageInstaller session. Android only accepts
 * it if the APK has our package name, a higher versionCode and the same signing key.
 */
class ApkInstaller(private val context: Context) {
  /**
   * "Install unknown apps" allowed for Trove. Only logged: without it the system installer's
   * confirm flow asks for it itself (we never open that Settings screen ourselves, see UpdateModel).
   */
  fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

  /**
   * Checks the APK before handing it to the system, for clearer errors than the installer's:
   * null if fine, else what is wrong. Unknown signer info counts as fine (the system still checks).
   */
  fun problemWith(apk: File): String? {
    val pm = context.packageManager
    val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file is not a valid APK."
    if (archive.packageName != context.packageName) return "The download is a different app (${archive.packageName})."
    val mine = pm.getPackageInfo(context.packageName, flags)
    val theirs = archive.signingInfo?.apkContentsSigners?.toSet() ?: return null
    val ours = mine.signingInfo?.apkContentsSigners?.toSet() ?: return null
    if (theirs != ours) {
      return "This update is signed with a different key than the installed app, so Android won't install " +
          "it over this one. Uninstall Trove and install the new APK once with adb (README \"Signing\")."
    }
    return null
  }

  /** Starts the install; the result arrives at [InstallResultReceiver]. Blocking IO: call off main. */
  fun install(apk: File) {
    val installer = context.packageManager.packageInstaller
    val params =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
          setAppPackageName(context.packageName)
          setSize(apk.length())
          // Once this app is the installer of record (after its first self-update), later updates
          // may install without the confirm dialog. The system decides; it falls back to asking.
          setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
    val sessionId = installer.createSession(params)
    try {
      installer.openSession(sessionId).use { session ->
        session.openWrite("base.apk", 0, apk.length()).use { out ->
          apk.inputStream().use { it.copyTo(out, 1 shl 16) }
          session.fsync(out)
        }
        val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
        // Mutable: the installer adds the status extras. Explicit intent, so this is allowed on 14.
        val pending =
            PendingIntent.getBroadcast(
                context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        session.commit(pending.intentSender)
      }
    } catch (e: Exception) {
      runCatching { installer.abandonSession(sessionId) }
      throw e
    }
  }

  companion object {
    const val ACTION_RESULT = "app.trove.quest.update.INSTALL_RESULT"
  }
}

/** Receives PackageInstaller results; shows the system confirm dialog when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
    Log.i(TAG, "install status=$status message=$message")
    val event =
        when (status) {
          PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm == null) {
              InstallEvent.Failed("The system did not show the install dialog.")
            } else {
              try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                InstallEvent.WaitingForUser
              } catch (e: Exception) {
                Log.w(TAG, "could not open the install dialog", e)
                InstallEvent.Failed("Could not open the install dialog: ${e.message}")
              }
            }
          }
          PackageInstaller.STATUS_SUCCESS -> InstallEvent.Succeeded
          PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvent.Failed("Install cancelled.")
          PackageInstaller.STATUS_FAILURE_CONFLICT,
          PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
              InstallEvent.Failed(
                  "Android refused the update (${message ?: "conflict"}). Usually a different signing key: " +
                      "see README \"Signing\".")
          PackageInstaller.STATUS_FAILURE_STORAGE -> InstallEvent.Failed("Not enough storage for the update.")
          else -> InstallEvent.Failed("Install failed: ${message ?: "status $status"}")
        }
    listener?.invoke(event)
  }

  companion object {
    const val TAG = "TroveUpdate"

    /** Set by [UpdateModel]; called on the main thread. */
    @Volatile var listener: ((InstallEvent) -> Unit)? = null
  }
}
