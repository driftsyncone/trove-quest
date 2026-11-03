package app.trove.quest.player

import app.trove.quest.api.Card
import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.Scene
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.TroveApi
import app.trove.quest.sync.DirectorCommand
import app.trove.quest.sync.DirectorContext
import app.trove.quest.sync.DirectorPlan
import app.trove.quest.sync.DirectorRules
import app.trove.quest.sync.StormQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the Director commands (partner console / "Send to headset") on the main thread through
 * [PlayerManager]; [DirectorRules] decides what to do, this does it and answers through [ack]
 * (true = done, false + message = failed) once it is known, which for a scene is after it was fetched.
 *
 * Safety is not decided here: [stopped] (the server's latch or the wearer's own escape) only makes
 * new scenes open paused, and Black Out hides whatever opens (PlayerManager does that for every new
 * screen). Nothing here starts the toy or lifts a latch.
 */
class DirectorExecutor(
    private val players: PlayerManager,
    private val api: TroveApi,
    private val scope: CoroutineScope,
    private val stopped: () -> Boolean,
) {
  fun run(cmd: DirectorCommand, ack: (ok: Boolean, error: String?) -> Unit) {
    val plan = DirectorRules.plan(cmd.action, DirectorContext(players.sessions.map { it.syncId }, stopped()))
    when (plan) {
      is DirectorPlan.Reject -> ack(false, plan.error)
      is DirectorPlan.FocusScreen -> {
        val s = screen(plan.screen) ?: return ack(false, GONE)
        players.requestFocus(s)
        ack(true, null)
      }
      is DirectorPlan.CloseScreen -> {
        val s = screen(plan.screen) ?: return ack(false, GONE)
        s.close()
        ack(true, null)
      }
      DirectorPlan.CloseAll -> {
        players.closeAll()
        ack(true, null)
      }
      DirectorPlan.StormStop -> {
        players.storm.close()
        ack(true, null)
      }
      is DirectorPlan.OpenScene ->
          async(ack) {
            val card = api.scene(plan.sceneId).toCard()
            open(card, plan.newScreen, plan.replaceScreen, plan.startMs, plan.paused)
          }
      is DirectorPlan.RandomPick ->
          async(ack) {
            val avoid = plan.replaceScreen?.let { screen(it)?.card?.id }
            val card = players.pickRandom(avoid) ?: error("Nothing to pick in this view")
            open(card, plan.newScreen, plan.replaceScreen, 0L, plan.paused)
          }
      is DirectorPlan.StormStart -> startStorm(plan.query, plan.paused, ack)
    }
  }

  private fun screen(id: String) = players.sessions.firstOrNull { it.syncId == id }

  /** Replacing a named screen: it takes the focus first, so the replacement lands in its place. */
  private fun open(card: Card, newScreen: Boolean, replace: String?, startMs: Long, paused: Boolean) {
    if (!newScreen && replace != null) {
      // It may have been closed while the scene loaded.
      val s = screen(replace) ?: error(GONE)
      players.requestFocus(s)
    }
    players.open(card, newScreen, startMs, startPaused = paused)
  }

  private fun async(ack: (Boolean, String?) -> Unit, block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
        ack(true, null)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        ack(false, e.message ?: "failed")
      }
    }
  }

  private fun startStorm(q: StormQuery, paused: Boolean, ack: (Boolean, String?) -> Unit) {
    val storm = players.storm
    if (storm.loading) return ack(false, "Storm is already loading")
    storm.start(q.toSceneQuery())
    async(ack) {
      var waited = 0
      while (storm.loading && waited < STORM_WAIT_MS) {
        delay(100)
        waited += 100
      }
      storm.error?.let { error(it) }
      if (!storm.active) error("Storm did not start")
      // Tiles start on their own; while STOPPED they are held right away.
      if (paused) storm.holdAll(true)
    }
  }

  private fun Scene.toCard() =
      Card(
          id = id, title = title, date = date, duration = duration, width = width, height = height, codec = codec,
          interactive = interactive, studio = studio, performers = performers, thumbVersion = thumbVersion, projection = projection)

  private fun StormQuery.toSceneQuery(): SceneQuery {
    fun facets(type: FacetType, ids: List<String>) = ids.map { Facet(type, it, it) }
    return SceneQuery(
        filterId = filter,
        q = q,
        facets = facets(FacetType.TAG, tags) + facets(FacetType.PERFORMER, performers) + facets(FacetType.STUDIO, studios),
    )
  }

  private companion object {
    const val GONE = "No such screen"
    const val STORM_WAIT_MS = 30_000
  }
}
