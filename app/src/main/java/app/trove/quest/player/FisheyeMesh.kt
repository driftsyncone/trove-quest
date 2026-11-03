package app.trove.quest.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry for side-by-side fisheye video (fisheye190/200/220), kept free of Spatial SDK types so it
 * can be unit tested; [FisheyeShapeOptions] hands it to the SDK as the video panel's mesh.
 *
 * Same model as the WebXR player (Trove server web/src/xr.ts, domeGeometry): an equidistant fisheye, so
 * the distance from the image centre grows linearly with the angle off the lens axis, and the image
 * circle touches the edges of each eye's half of the frame at half the lens FOV. The mesh is a dome of
 * rings round the lens axis: ring 0 is the centre (straight ahead), the last ring is the FOV limit,
 * which for 190°+ lenses lies a little behind the viewer's sides.
 *
 * Frame: the entity's local space, as placed by Placement.aroundHead: +Z is straight ahead, +Y up and,
 * OpenXR being right-handed, the viewer's right is -X. UVs are for one eye (0..1), v = 0 is the top of
 * the image; the panel material's stereo mode picks the eye's half of the side-by-side frame.
 */
class FisheyeMesh(
    /** x, y, z per vertex. */
    val positions: FloatArray,
    /** Unit normals pointing inwards (at the viewer), x, y, z per vertex. */
    val normals: FloatArray,
    /** u, v per vertex. */
    val uvs: FloatArray,
    /** Triangles, three vertex indices each. */
    val indices: IntArray,
) {
  val vertexCount: Int
    get() = positions.size / 3

  companion object {
    /** Rings (centre to FOV limit) and segments round the axis; the web player uses the same 64 x 96. */
    const val RINGS = 64
    const val SEGMENTS = 96

    /**
     * Where a view direction lands in one eye's image: [theta] is the angle off the lens axis, [psi]
     * the angle round it (0 = image right, PI/2 = image up). Returns (u, v), v = 0 at the top.
     * Directions beyond half the FOV land outside the image circle (radius > 0.5).
     */
    fun uv(theta: Double, psi: Double, fovDeg: Float): Pair<Float, Float> {
      val r = 0.5 * theta / halfFovRad(fovDeg)
      return Pair((0.5 + r * cos(psi)).toFloat(), (0.5 - r * sin(psi)).toFloat())
    }

    /** Unit view direction for ([theta], [psi]) as in [uv], in the frame described on the class. */
    fun direction(theta: Double, psi: Double): Triple<Float, Float, Float> =
        Triple((-sin(theta) * cos(psi)).toFloat(), (sin(theta) * sin(psi)).toFloat(), cos(theta).toFloat())

    fun halfFovRad(fovDeg: Float): Double = fovDeg * PI / 360.0

    /** The dome for a [fovDeg] lens at [radius] metres round the viewer. */
    fun build(fovDeg: Float, radius: Float, rings: Int = RINGS, segments: Int = SEGMENTS): FisheyeMesh {
      require(fovDeg > 0f && rings > 0 && segments > 2)
      val n = (rings + 1) * (segments + 1)
      val pos = FloatArray(n * 3)
      val nrm = FloatArray(n * 3)
      val uv = FloatArray(n * 2)
      val half = halfFovRad(fovDeg)
      var i = 0
      for (r in 0..rings) {
        val theta = half * r / rings
        // The last column repeats the first (psi = 2 PI): same position and UV, so no seam.
        for (s in 0..segments) {
          val psi = 2 * PI * s / segments
          val (x, y, z) = direction(theta, psi)
          pos[i * 3] = x * radius
          pos[i * 3 + 1] = y * radius
          pos[i * 3 + 2] = z * radius
          nrm[i * 3] = -x
          nrm[i * 3 + 1] = -y
          nrm[i * 3 + 2] = -z
          val (u, v) = uv(theta, psi, fovDeg)
          uv[i * 2] = u
          uv[i * 2 + 1] = v
          i++
        }
      }
      val idx = IntArray(rings * segments * 6)
      var k = 0
      for (r in 0 until rings) {
        for (s in 0 until segments) {
          val a = r * (segments + 1) + s
          val b = a + segments + 1
          idx[k++] = a
          idx[k++] = b
          idx[k++] = a + 1
          idx[k++] = b
          idx[k++] = b + 1
          idx[k++] = a + 1
        }
      }
      return FisheyeMesh(pos, nrm, uv, idx)
    }
  }
}
