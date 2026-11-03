package app.trove.quest.drag

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.api.Card
import app.trove.quest.player.PlayerManager
import com.meta.spatial.core.Pose

/**
 * Drag a scene card from the wall into the room. The wall's cards report the press and the first
 * movement here ([press], [moved]); from then on [CardDragSystem] owns the drag (the panel only sees
 * the ray while it is on the panel), moves the ghost, and calls [drop] or [cancel].
 */
class CardDrag(private val players: PlayerManager) {
  /** Card under a held trigger, not (yet) dragged. Read by the system; null once the trigger is up. */
  var armed: Card? = null
    private set

  /** The panel saw the pointer travel past the threshold: the system starts the drag next frame. */
  var startRequested = false
    private set

  /** The card being dragged, for the ghost's content. */
  var active by mutableStateOf<Card?>(null)
    private set

  /** Ray over the wall right now: releasing cancels. */
  var overWall by mutableStateOf(true)

  /** What a drop would do at 6/6 or behind a sphere, for the ghost caption. */
  val dropReplaces: Boolean
    get() = players.dropReplaces

  /** Trigger went down on [card]. */
  fun press(card: Card) {
    armed = card
    startRequested = false
  }

  /** The pointer moved past the threshold with the trigger held. */
  fun moved(card: Card) {
    if (armed?.id == card.id) startRequested = true
  }

  /** Tap, long-press or release: not a drag (any more). */
  fun disarm() {
    armed = null
    startRequested = false
  }

  /** B / Y (the router's CANCEL_DRAG): the system cancels the armed or running drag on its next frame. */
  var cancelRequested = false

  /** A card is held down (armed) or being dragged: B cancels it, and the control scheme is in its Dragging context. */
  val busy: Boolean
    get() = armed != null || active != null

  /** Called by the system when the drag starts. */
  fun started(card: Card) {
    disarm()
    overWall = true
    active = card
  }

  /** Released off the wall: the scene opens as a new screen at [pose] (or replaces one, see [dropReplaces]). */
  fun drop(pose: Pose) {
    val card = active ?: return
    active = null
    players.open(card, newScreen = true, at = pose)
  }

  fun cancel() {
    active = null
  }
}
