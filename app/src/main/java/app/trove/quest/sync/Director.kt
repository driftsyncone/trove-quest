package app.trove.quest.sync

/**
 * What the console / flat web app asks the headset to do (Trove server README "Director commands").
 * Decoded from the server's `command` messages by [SyncProtocol]; turned into a [DirectorPlan] by
 * [DirectorRules]; run on the main thread by `player/DirectorExecutor`.
 */
sealed interface DirectorAction {
  /** [newScreen] adds a screen; otherwise [screen] (null = the focused one) is replaced. */
  data class Open(val sceneId: String, val newScreen: Boolean, val screen: String?, val startMs: Long) : DirectorAction

  data class Swap(val screen: String, val sceneId: String) : DirectorAction

  data class Close(val screen: String) : DirectorAction

  data object CloseAll : DirectorAction

  data class Focus(val screen: String) : DirectorAction

  data class Random(val newScreen: Boolean, val screen: String?) : DirectorAction

  /** [query] only with [on]; ids are Stash ids, the same lists the wall's facet chips send. */
  data class Storm(val on: Boolean, val query: StormQuery?) : DirectorAction
}

data class StormQuery(
    val q: String = "",
    val tags: List<String> = emptyList(),
    val performers: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val filter: String? = null,
)

/** A numbered command from the server: the answer ([SyncProtocol.encodeAck]) carries [seq]. */
data class DirectorCommand(val seq: Long, val action: DirectorAction)

/** What to do on the headset, already checked against the open screens and the safety state. */
sealed interface DirectorPlan {
  /**
   * [paused]: a real stop is on (the partner's STOP ALL or the wearer's escape; not the startup
   * "not started yet" latch): the scene is opened but not started. Black Out needs no flag: the session manager already hides new screens then.
   */
  data class OpenScene(val sceneId: String, val newScreen: Boolean, val replaceScreen: String?, val startMs: Long, val paused: Boolean) : DirectorPlan

  data class RandomPick(val newScreen: Boolean, val replaceScreen: String?, val paused: Boolean) : DirectorPlan

  data class CloseScreen(val screen: String) : DirectorPlan

  data object CloseAll : DirectorPlan

  data class FocusScreen(val screen: String) : DirectorPlan

  data class StormStart(val query: StormQuery, val paused: Boolean) : DirectorPlan

  data object StormStop : DirectorPlan

  /** Cannot be done: the ack says [error]. */
  data class Reject(val error: String) : DirectorPlan
}

/** The headset state the rules look at. */
data class DirectorContext(
    /** Sync ids of the open screens (`PlayerSession.syncId`). */
    val screens: List<String>,
    /** The server's latch or the wearer's own escape is on. */
    val stopped: Boolean,
)

/** Pure mapping from a command to a plan (JVM-tested). */
object DirectorRules {
  fun plan(action: DirectorAction, ctx: DirectorContext): DirectorPlan {
    fun known(id: String) = id in ctx.screens
    return when (action) {
      is DirectorAction.Open -> {
        val target = action.screen
        when {
          action.newScreen -> DirectorPlan.OpenScene(action.sceneId, true, null, action.startMs, ctx.stopped)
          target != null && !known(target) -> noScreen
          // Replacing with nothing open is just opening.
          else -> DirectorPlan.OpenScene(action.sceneId, ctx.screens.isEmpty(), target, action.startMs, ctx.stopped)
        }
      }
      is DirectorAction.Swap ->
          if (!known(action.screen)) noScreen
          else DirectorPlan.OpenScene(action.sceneId, false, action.screen, 0L, ctx.stopped)
      is DirectorAction.Close -> if (known(action.screen)) DirectorPlan.CloseScreen(action.screen) else noScreen
      is DirectorAction.Focus -> if (known(action.screen)) DirectorPlan.FocusScreen(action.screen) else noScreen
      DirectorAction.CloseAll -> DirectorPlan.CloseAll
      is DirectorAction.Random ->
          when {
            action.newScreen -> DirectorPlan.RandomPick(true, null, ctx.stopped)
            action.screen != null && !known(action.screen) -> noScreen
            else -> DirectorPlan.RandomPick(ctx.screens.isEmpty(), action.screen, ctx.stopped)
          }
      is DirectorAction.Storm ->
          if (action.on) DirectorPlan.StormStart(action.query ?: StormQuery(), ctx.stopped) else DirectorPlan.StormStop
    }
  }

  private val noScreen = DirectorPlan.Reject("No such screen")
}
