package app.trove.quest.input

import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.UIPanelRenderOptions

/**
 * Every panel registration passes this: the SDK's default (`PanelConfigOptions.DEFAULT_CLICK_BUTTONS`)
 * also clicks on A and X, which are play / pause in the control scheme (docs/control-scheme.md).
 * Panels click on the triggers only.
 */
object PanelInput {
  val triggersOnly = PanelInputOptions(clickButtons = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR)
}

/**
 * Small UI panels draw into the eye buffer as a mesh (SDK `PanelRenderMode.Mesh`), not as a compositor
 * layer. The compositor takes 16 layers at most and every video panel is one; a UI panel on a layer
 * pushed Storm with nine tiles to 17 and the frame was dropped (XR_ERROR_LAYER_LIMIT_EXCEEDED).
 * Only the library wall keeps a layer (sharper text), counted in [app.trove.quest.storm.LayerBudget].
 */
object PanelRender {
  val eyeBuffer = UIPanelRenderOptions(renderMode = PanelRenderMode.Mesh())
}
