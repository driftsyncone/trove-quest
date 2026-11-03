package app.trove.quest.storm

/**
 * Compositor layer budget (README "Streams and the decoder and layer budget"). The compositor takes 16
 * layers per frame; one more and xrEndFrame fails with XR_ERROR_LAYER_LIMIT_EXCEEDED and the whole view
 * goes black. Every video panel (Storm tile, screen) is a layer, so is any UI panel that is not drawn
 * into the eye buffer ([app.trove.quest.input.PanelRender]). Today only the library wall is: the
 * scrub bar, Storm panels, legend, ghost, focus bars and screen controls are eye-buffer panels.
 * Two layers stay free for the system (menus, the keyboard, the dashboard).
 */
object LayerBudget {
  const val MAX_LAYERS = 16
  const val SAFETY_MARGIN = 2
  const val USABLE = MAX_LAYERS - SAFETY_MARGIN

  /** The library wall is the one UI panel on a layer. */
  const val WALL_LAYERS = 1

  /** Layers in use apart from Storm tiles: one per open screen, and the wall while the library shows. */
  fun nonTileLayers(screens: Int, libraryShown: Boolean, otherUiLayers: Int = 0): Int =
      screens.coerceAtLeast(0) + (if (libraryShown) WALL_LAYERS else 0) + otherUiLayers.coerceAtLeast(0)

  /** How many Storm tiles fit next to [otherLayers]. */
  fun tileRoom(otherLayers: Int): Int = (USABLE - otherLayers).coerceAtLeast(0)

  fun total(tiles: Int, otherLayers: Int): Int = tiles + otherLayers

  fun overBudget(total: Int): Boolean = total > USABLE
}
