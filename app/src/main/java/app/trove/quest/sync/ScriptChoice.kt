package app.trove.quest.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * Script variants (Trove server README "Script variants"): when a scene has several funscripts (Stash
 * Interactive Tools lists them), the wearer picks which one drives the toy. The server keeps the choice
 * (per user and scene) and loads it onto the device; the app remembers nothing, it only shows what the
 * server says and what was just tapped until the server answers.
 */

/** One of a scene's scripts: [id] is what a choice sends, [default] = the scene's own script. */
data class ScriptOption(val id: String, val label: String, val default: Boolean = false)

/**
 * Status `script`: the scripts of the scene the toy follows (else the focused one), the one in effect
 * ([chosen]), [loading] while a new choice is not on the device yet, and why the last choice failed.
 */
data class ScriptState(
    val sceneId: String,
    val options: List<ScriptOption>,
    val chosen: String?,
    val loading: Boolean = false,
    val error: String? = null,
)

object ScriptRules {
  /** The row is there only when there is a choice to make. */
  fun hasChoice(options: List<ScriptOption>?): Boolean = (options?.size ?: 0) >= 2

  const val LOADING = "Loading script…"
  const val NO_LINK = "No link to the server"

  /** A tap the server has not answered within this long is forgotten (the server's state shows again). */
  const val PENDING_MS = 10_000L

  /** The chip's text (the server's label; Stash Interactive Tools calls the scene's own script "Default"). */
  fun chipLabel(o: ScriptOption): String = o.label.ifBlank { o.id }
}

/**
 * The picker's state, shared by the VacuGlide panel and the scene details. [pick] sends the choice; until the
 * server's status shows it (or its ack refuses it) the tapped chip is shown selected with "Loading script…".
 * Pure Kotlin + Compose state; the clock is passed in.
 */
class ScriptPicker(
    private val sync: SyncIndicator,
    private val send: (sceneId: String, id: String) -> Boolean,
    private val clock: () -> Long,
) {
  data class Pending(val sceneId: String, val id: String, val at: Long, val acked: Boolean = false)

  var pending by mutableStateOf<Pending?>(null)
    private set

  /** A refusal or "no link", shown until the next pick. */
  var notice by mutableStateOf<String?>(null)
    private set

  /** Counts the server's answers to picks: the scene details read their list again when it moves. */
  var answers by mutableIntStateOf(0)
    private set

  /** The scripts of the scene the toy follows, as the server last said. */
  val current: ScriptState?
    get() = sync.state.script

  fun pick(sceneId: String, id: String): Boolean {
    val now = clock()
    if (!send(sceneId, id)) {
      notice = ScriptRules.NO_LINK
      return false
    }
    notice = null
    pending = Pending(sceneId, id, now)
    return true
  }

  /** The id shown selected for [sceneId]: the tap on its way, else the server's choice ([serverChosen] for a scene the status is not about). */
  fun chosen(sceneId: String, serverChosen: String? = null): String? {
    live()?.takeIf { it.sceneId == sceneId }?.let { return it.id }
    return current?.takeIf { it.sceneId == sceneId }?.chosen ?: serverChosen
  }

  /** "Loading script…" for [sceneId]: a tap not answered yet, or the server still loading it onto the device. */
  fun loading(sceneId: String, serverLoading: Boolean = false): Boolean {
    if (live()?.sceneId == sceneId) return true
    val s = current
    return if (s?.sceneId == sceneId) s.loading else serverLoading
  }

  /** What the row says under the chips, or null. */
  fun line(sceneId: String, serverLoading: Boolean = false): String? =
      notice ?: current?.takeIf { it.sceneId == sceneId }?.error ?: if (loading(sceneId, serverLoading)) ScriptRules.LOADING else null

  fun onAck(ack: ServerMessage.VgAck) {
    if (ack.op != "script") return
    answers++
    val p = pending ?: return
    if (!ack.ok) {
      notice = ack.error ?: "The server refused that script"
      pending = null
    } else {
      pending = p.copy(acked = true)
      settle()
    }
  }

  /** The scene details read the server's list: an answered tap for that scene is settled once the server is done loading. */
  fun confirmed(sceneId: String, loading: Boolean) {
    val p = pending ?: return
    if (p.sceneId == sceneId && p.acked && !loading) pending = null
  }

  /** A status arrived: a tap the server now shows (chosen, done loading) is settled; an acked tap for another scene too. */
  fun onStatus() = settle()

  private fun settle() {
    val p = pending ?: return
    val s = current
    if (s?.sceneId == p.sceneId) {
      if (p.acked && !s.loading) pending = null
    }
    // An acked tap for a scene the status is not about stays until the scene details' list shows it (confirmed).
  }

  /** The tap on its way, unless the server never answered it (read-only: safe while composing). */
  private fun live(): Pending? = pending?.takeIf { clock() - it.at <= ScriptRules.PENDING_MS }
}
