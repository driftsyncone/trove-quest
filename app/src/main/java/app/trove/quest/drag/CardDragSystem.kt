package app.trove.quest.drag

import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/**
 * Runs a card drag from the controller pose, frame by frame (README "Drag a card into the room").
 *
 * 1. **Armed** ([CardDrag.press]): trigger down on a card. Waits for the pointer to travel (the panel
 *    calls [CardDrag.moved]) or for the ray to leave the wall, with the trigger still held.
 * 2. **Dragging**: a ghost panel follows the ray at the wall's distance from the head, facing the
 *    user level. Trigger released with the ray off the wall = drop (new screen at the ghost's pose);
 *    over the wall, or grip / B / Y (the router's cancel), or a lost controller = cancel.
 *
 * The hand is whichever local controller holds a trigger when the press arrives. Hand tracking
 * is not handled separately: a pinch only works if the SDK maps it to the same trigger bits.
 *
 * [wall]: the wall panel's world rectangle, or null while it is hidden. [head]: the head position.
 * [ghost]: the ghost panel entity (hidden between drags).
 */
class CardDragSystem(
    private val drag: CardDrag,
    private val wall: () -> PanelRect?,
    private val head: () -> Vector3,
    private val ghost: () -> Entity?,
) : SystemBase() {
  private var hand: Entity? = null
  private var dragging = false
  private var ghostShown = false
  private var lastPose: Pose? = null

  override fun execute() {
    if (dragging) runDrag() else if (drag.armed != null) runArmed() else drag.cancelRequested = false
  }

  private fun runArmed() {
    val c = hand?.takeIf { triggerHeld(it) } ?: findTriggerHand().also { hand = it }
    if (c == null) { // trigger let go before anything started: a tap / long-press, handled by the panel
      drag.disarm()
      hand = null
      return
    }
    if (cancelPressed()) return drag.disarm().also { hand = null }
    val ray = rayOf(c) ?: return
    val onWall = rayOnWall(ray)
    if (drag.startRequested || DragRules.startsOffWall(true, onWall, gripHeld())) {
      val card = drag.armed ?: return
      drag.started(card)
      dragging = true
      Log.d(TAG, "drag started: ${card.id} onWall=$onWall")
      runDrag()
    }
  }

  private fun runDrag() {
    val c = hand
    val ray = c?.takeIf { it.tryGetComponent<Controller>()?.isActive == true }?.let(::rayOf)
    val onWall = ray?.let(::rayOnWall) ?: false
    val end =
        DragRules.step(
            triggerHeld = c != null && triggerHeld(c),
            cancelPressed = cancelPressed(),
            controllerLost = ray == null,
            rayOnWall = onWall,
        )
    if (ray != null) place(ray)
    drag.overWall = onWall
    when (end) {
      DragEnd.CONTINUE -> Unit
      DragEnd.DROP -> {
        lastPose?.let(drag::drop) ?: drag.cancel()
        finish()
      }
      DragEnd.CANCEL -> {
        drag.cancel()
        finish()
      }
    }
  }

  private fun finish() {
    Log.d(TAG, "drag ended")
    dragging = false
    hand = null
    showGhost(false)
  }

  /** Ghost on the ray at the wall's distance from the head. */
  private fun place(ray: Ray) {
    val h = head()
    val headV = V3(h.x, h.y, h.z)
    val dist = wall()?.let { (it.center - headV).length } ?: FALLBACK_DISTANCE_M
    val pos = DragRules.ghostPosition(headV, ray.origin, ray.dir, dist)
    val f = DragRules.facing(headV, pos)
    val pose = Pose(Vector3(pos.x, pos.y, pos.z), Quaternion.lookRotationAroundY(Vector3(f.x, 0f, f.z)))
    lastPose = pose
    ghost()?.setComponent(Transform(pose))
    showGhost(true)
  }

  private fun showGhost(on: Boolean) {
    if (ghostShown == on) return
    ghostShown = on
    ghost()?.setComponent(Visible(on))
  }

  private class Ray(val origin: V3, val dir: V3)

  private fun rayOf(controller: Entity): Ray? {
    val p = controller.tryGetComponent<Transform>()?.transform ?: return null
    val f = p.forward()
    return Ray(V3(p.t.x, p.t.y, p.t.z), V3(f.x, f.y, f.z).normalized())
  }

  private fun rayOnWall(ray: Ray): Boolean {
    val rect = wall() ?: return false
    return DragRules.rayHitsRect(ray.origin, ray.dir, rect, DragRules.WALL_MARGIN_M) != null
  }

  private fun controllers(): List<Entity> =
      Query.where { has(Controller.id) }.eval().filter {
        it.isLocal() && it.tryGetComponent<Controller>()?.isActive == true
      }.toList()

  private fun triggerHeld(e: Entity): Boolean =
      (e.tryGetComponent<Controller>()?.buttonState ?: 0) and TRIGGERS != 0

  private fun findTriggerHand(): Entity? = controllers().firstOrNull { triggerHeld(it) }

  /** Grip on any hand (a grab). */
  private fun gripHeld(): Boolean = controllers().any { (it.tryGetComponent<Controller>()?.buttonState ?: 0) and GRIPS != 0 }

  /** Grip on any hand, or B / Y (the router sets [CardDrag.cancelRequested]): cancels. */
  private fun cancelPressed(): Boolean {
    val requested = drag.cancelRequested
    drag.cancelRequested = false
    return requested || gripHeld()
  }

  private companion object {
    const val TAG = "TroveDrag"
    const val FALLBACK_DISTANCE_M = 1.6f
    val TRIGGERS = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
    val GRIPS = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR
  }
}
