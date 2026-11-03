package app.trove.quest.api

import java.io.IOException
import kotlinx.coroutines.delay

/** What the login screen shows for device pairing. */
sealed interface PairingState {
  /** Asking the server for a code. */
  data object Starting : PairingState

  /** Showing [start].code and a QR of [start].url; polling. */
  data class Waiting(val start: PairStart, val expiresAtMs: Long) : PairingState

  /** Couldn't reach the server or it refused; retrying on its own. */
  data class Trouble(val message: String) : PairingState

  /** The phone tapped Deny. Needs a new code by hand. */
  data object Denied : PairingState

  /** Waited [DevicePairing.maxCodes] codes without an answer; stopped polling. Needs a new code by hand. */
  data object Stopped : PairingState

  /** The session cookie is set; [user] is logged in. */
  data class Approved(val user: User) : PairingState
}

/**
 * TV-style device pairing against the Trove server (server/pairing.ts):
 * start → show code + QR → poll every `interval` s until a logged-in phone approves on /pair.
 * Expired codes are replaced with fresh ones, up to [maxCodes] (5 min each), so an idle headset
 * doesn't poll forever.
 */
class DevicePairing(
    private val api: TroveApi,
    private val maxCodes: Int = 6,
    private val retryDelayMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
  /** Runs until Approved, Denied or Stopped (returned, and also passed to [onState]). Cancel to stop. */
  suspend fun run(onState: (PairingState) -> Unit): PairingState {
    var codes = 0
    while (codes < maxCodes) {
      onState(PairingState.Starting)
      val start =
          try {
            api.pairStart()
          } catch (e: IOException) {
            onState(PairingState.Trouble(describe(e)))
            delay(if (e is ApiException && e.status == 429) 60_000 else retryDelayMs)
            continue
          }
      codes++
      val expiresAt = clock() + start.expiresIn * 1000L
      onState(PairingState.Waiting(start, expiresAt))
      val result = pollUntilDone(start, expiresAt, onState)
      if (result != null) {
        onState(result)
        return result
      }
    }
    onState(PairingState.Stopped)
    return PairingState.Stopped
  }

  /** null = this code is used up (expired or refused), start a new one. */
  private suspend fun pollUntilDone(
      start: PairStart,
      expiresAt: Long,
      onState: (PairingState) -> Unit,
  ): PairingState? {
    val intervalMs = start.interval.coerceIn(1, 30) * 1000L
    var troubled = false
    while (true) {
      delay(intervalMs)
      val poll =
          try {
            api.pairPoll(start.pairId, start.secret)
          } catch (e: IOException) {
            // 403 = the server doesn't know our secret any more: get a new code.
            if (e is ApiException && e.status == 403) return null
            if (clock() > expiresAt) return null
            onState(PairingState.Trouble(describe(e)))
            troubled = true
            if (e is ApiException && e.status == 429) delay(30_000)
            continue
          }
      if (troubled) {
        troubled = false
        onState(PairingState.Waiting(start, expiresAt))
      }
      when (poll.status) {
        "pending" -> if (clock() > expiresAt + intervalMs * 2) return null
        "approved" -> return PairingState.Approved(poll.user ?: api.me())
        "denied" -> return PairingState.Denied
        else -> return null // "expired" or anything new
      }
    }
  }

  private fun describe(e: IOException): String =
      if (e is ApiException) e.message ?: "HTTP ${e.status}" else "Can't reach ${api.baseUrl}: ${e.message}"
}
