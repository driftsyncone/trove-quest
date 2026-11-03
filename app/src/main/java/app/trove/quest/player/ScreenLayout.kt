package app.trove.quest.player

import app.trove.quest.api.Projection

/** Panel geometry for a projection, kept free of Spatial SDK types so it can be unit tested. */
enum class Shape {
  QUAD,
  EQUIRECT_180,
  EQUIRECT_360,
  /** Our own dome mesh with the video as its texture ([FisheyeMesh]). */
  FISHEYE,
}

/** Mirrors com.meta.spatial.runtime.StereoMode (None / LeftRight / UpDown). */
enum class Stereo {
  NONE,
  LEFT_RIGHT,
  UP_DOWN,
}

/** [fovDeg]: the lens's full field of view, fisheye only (0 otherwise). */
data class ScreenLayout(val shape: Shape, val stereo: Stereo, val fovDeg: Float = 0f) {
  val isSphere: Boolean
    get() = shape != Shape.QUAD

  /**
   * Width / height of what one eye sees. A full-SBS 7680x2160 file is two 3840x2160 frames, so 16:9.
   * Falls back to 16:9 when the server did not know the size.
   */
  fun eyeAspect(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 9f
    val w = if (stereo == Stereo.LEFT_RIGHT) width / 2f else width.toFloat()
    val h = if (stereo == Stereo.UP_DOWN) height / 2f else height.toFloat()
    return w / h
  }

  companion object {
    /** Server projection -> panel. Fisheye has no SDK shape: it is a custom mesh (milestone 4). */
    fun of(p: Projection): ScreenLayout =
        when (p) {
          Projection.FLAT -> ScreenLayout(Shape.QUAD, Stereo.NONE)
          Projection.SBS -> ScreenLayout(Shape.QUAD, Stereo.LEFT_RIGHT)
          Projection.OU -> ScreenLayout(Shape.QUAD, Stereo.UP_DOWN)
          Projection.SBS180 -> ScreenLayout(Shape.EQUIRECT_180, Stereo.LEFT_RIGHT)
          Projection.OU180 -> ScreenLayout(Shape.EQUIRECT_180, Stereo.UP_DOWN)
          Projection.MONO180 -> ScreenLayout(Shape.EQUIRECT_180, Stereo.NONE)
          Projection.MONO360 -> ScreenLayout(Shape.EQUIRECT_360, Stereo.NONE)
          Projection.OU360 -> ScreenLayout(Shape.EQUIRECT_360, Stereo.UP_DOWN)
          Projection.SBS360 -> ScreenLayout(Shape.EQUIRECT_360, Stereo.LEFT_RIGHT)
          Projection.FISHEYE190 -> ScreenLayout(Shape.FISHEYE, Stereo.LEFT_RIGHT, 190f)
          Projection.FISHEYE200 -> ScreenLayout(Shape.FISHEYE, Stereo.LEFT_RIGHT, 200f)
          Projection.FISHEYE220 -> ScreenLayout(Shape.FISHEYE, Stereo.LEFT_RIGHT, 220f)
        }
  }
}
