package app.trove.quest.player

import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelShapeType
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.toolkit.MediaPanelShapeOptions

/**
 * A video panel drawn on our own fisheye dome ([FisheyeMesh]) instead of an SDK shape.
 *
 * The SDK has no fisheye panel shape, but every panel shape is just a `sceneMeshCreator`
 * (PanelShapeConfig: (config, texture) -> SceneMesh) plus, optionally, a compositor layer. Media panels
 * normally go to a layer (MediaPanelRenderOptions sets a LayerConfig; the mesh only punches a hole).
 * Here the layer is dropped, so PanelSceneObject backs the video Surface with an ImageReader feeding a
 * SceneTexture (PanelSurface, useTexture = layerConfig == null) and our mesh samples that texture,
 * like the SDK's own equirect mesh creators do (SceneMaterial with the panel shader, unlit, stereo
 * mode from the config: the material picks each eye's half of the side-by-side frame).
 *
 * Cost compared with a layer: the video is sampled once more into the eye buffer (no compositor
 * re-projection of the video itself). The compositor's own fisheye layer (Scene.createFisheyeLayer,
 * XR_METAX1_composition_layer_fisheye, an experimental extension) is not used for that reason.
 *
 * Must be applied after MediaPanelRenderOptions, which MediaPanelSettings does (render, then shape).
 */
class FisheyeShapeOptions(private val fovDeg: Float, private val radius: Float) : MediaPanelShapeOptions {
  override fun applyTo(options: PanelConfigOptions) {
    options.includeGlass = false
    options.panelShapeType = PanelShapeType.EQUIRECT180
    options.radiusForCylinderOrSphere = radius
    options.layerConfig = null
    options.enableLayer = false
    options.forceSceneTexture = false
    // PanelShapeConfig.(SceneTexture) -> SceneMesh: `this` is the panel's config.
    options.sceneMeshCreator = { texture ->
      val config = this
      val material =
          SceneMaterial(texture, config.alphaMode, config.panelShader).apply {
            setUnlit(true)
            setStereoMode(config.stereoMode)
            // Seen from inside; double sided so a winding mix-up can't hide it.
            setSidedness(MaterialSidedness.DOUBLE_SIDED)
          }
      val mesh = FisheyeMesh.build(fovDeg, radius)
      SceneMesh.meshWithMaterials(
          mesh.positions,
          mesh.normals,
          mesh.uvs,
          IntArray(mesh.vertexCount) { -1 }, // vertex colours: white, no tint
          mesh.indices,
          intArrayOf(0, mesh.indices.size), // material ranges: (first index, index count) per material
          arrayOf(material),
          false, // as the SDK's own panel handle meshes
      )
    }
  }
}
