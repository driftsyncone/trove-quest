package app.trove.quest.hold

import android.os.SystemClock
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller

/**
 * The wearer's universal escape: both grips (squeeze) held. Every frame it reads the local
 * controllers and gives [onGrips] whether both squeeze bits are down, in any state; the 2 s rule
 * lives in `BlackoutState.gripTick` and the escape itself in the activity.
 */
class BlackoutGripSystem(
    private val onGrips: (nowMs: Long, both: Boolean) -> Unit,
) : SystemBase() {
  override fun execute() {
    var bits = 0
    for (e in Query.where { has(Controller.id) }.eval()) {
      if (!e.isLocal()) continue
      val c = e.tryGetComponent<Controller>() ?: continue
      if (c.isActive) bits = bits or c.buttonState
    }
    val both = bits and ButtonBits.ButtonSqueezeL != 0 && bits and ButtonBits.ButtonSqueezeR != 0
    onGrips(SystemClock.uptimeMillis(), both)
  }
}
