package app.trove.quest

import app.trove.quest.player.FisheyeMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FisheyeMeshTest {
  private val fovs = listOf(190f, 200f, 220f)
  private val eps = 1e-5f

  @Test
  fun centreOfTheImageIsStraightAhead() {
    for (fov in fovs) {
      val (u, v) = FisheyeMesh.uv(0.0, 1.234, fov)
      assertEquals(0.5f, u, eps)
      assertEquals(0.5f, v, eps)
    }
    val (x, y, z) = FisheyeMesh.direction(0.0, 0.0)
    assertEquals(0f, x, eps)
    assertEquals(0f, y, eps)
    assertEquals(1f, z, eps)
  }

  @Test
  fun halfTheFovTouchesTheEdgeOfTheEyeImage() {
    for (fov in fovs) {
      val half = FisheyeMesh.halfFovRad(fov)
      // Image right, top, left, bottom.
      assertUv(1f, 0.5f, FisheyeMesh.uv(half, 0.0, fov))
      assertUv(0.5f, 0f, FisheyeMesh.uv(half, PI / 2, fov))
      assertUv(0f, 0.5f, FisheyeMesh.uv(half, PI, fov))
      assertUv(0.5f, 1f, FisheyeMesh.uv(half, 3 * PI / 2, fov))
    }
  }

  @Test
  fun rightAngleOffAxisIsInsideTheImageCircle() {
    // Equidistant: 90° lands at 90 / (fov / 2) of the radius, so 190° shows 5° past the side.
    val expected = mapOf(190f to 0.5f + 0.5f * 90f / 95f, 200f to 0.5f + 0.5f * 90f / 100f, 220f to 0.5f + 0.5f * 90f / 110f)
    for ((fov, u) in expected) assertEquals(u, FisheyeMesh.uv(PI / 2, 0.0, fov).first, eps)
  }

  @Test
  fun imageRightIsTheViewersRightAndImageUpIsUp() {
    // Viewer looks along +Z with +Y up; OpenXR is right-handed, so their right is -X.
    val (xr, _, _) = FisheyeMesh.direction(PI / 4, 0.0)
    assertTrue(xr < 0f)
    val (_, yu, _) = FisheyeMesh.direction(PI / 4, PI / 2)
    assertTrue(yu > 0f)
    assertTrue(FisheyeMesh.uv(PI / 4, PI / 2, 190f).second < 0.5f) // v = 0 is the top
  }

  @Test
  fun meshReachesTheFovLimitBehindTheViewersSides() {
    val radius = 20f
    for (fov in fovs) {
      val m = FisheyeMesh.build(fov, radius)
      val rings = FisheyeMesh.RINGS
      val segs = FisheyeMesh.SEGMENTS
      assertEquals((rings + 1) * (segs + 1), m.vertexCount)
      assertEquals(m.vertexCount * 2, m.uvs.size)
      assertEquals(rings * segs * 6, m.indices.size)
      assertTrue(m.indices.all { it in 0 until m.vertexCount })

      // Ring 0 is the centre: every vertex straight ahead with the image centre.
      for (s in 0..segs) {
        assertEquals(radius, m.positions[s * 3 + 2], 1e-4f)
        assertUv(0.5f, 0.5f, m.uvs[s * 2] to m.uvs[s * 2 + 1])
      }
      // Last ring: the FOV limit, z = r cos(fov / 2) (behind the sides for > 180°), on the image circle.
      val limitZ = radius * cos(FisheyeMesh.halfFovRad(fov)).toFloat()
      assertTrue(limitZ < 0f)
      for (s in 0..segs) {
        val i = rings * (segs + 1) + s
        assertEquals(limitZ, m.positions[i * 3 + 2], 1e-3f)
        val du = m.uvs[i * 2] - 0.5f
        val dv = m.uvs[i * 2 + 1] - 0.5f
        assertEquals(0.5f, sqrt(du * du + dv * dv), 1e-4f)
      }
      // Every vertex sits on the sphere, normals point at the centre, UVs stay in 0..1.
      for (i in 0 until m.vertexCount) {
        val x = m.positions[i * 3]
        val y = m.positions[i * 3 + 1]
        val z = m.positions[i * 3 + 2]
        assertEquals(radius, sqrt(x * x + y * y + z * z), 1e-3f)
        assertEquals(-x / radius, m.normals[i * 3], 1e-4f)
        assertTrue(m.uvs[i * 2] in -eps..1 + eps && m.uvs[i * 2 + 1] in -eps..1 + eps)
      }
    }
  }

  @Test
  fun seamColumnRepeatsTheFirst() {
    val m = FisheyeMesh.build(200f, 10f)
    val segs = FisheyeMesh.SEGMENTS
    for (r in 0..FisheyeMesh.RINGS) {
      val first = r * (segs + 1)
      val last = first + segs
      for (c in 0..2) assertEquals(m.positions[first * 3 + c], m.positions[last * 3 + c], 1e-4f)
      for (c in 0..1) assertEquals(m.uvs[first * 2 + c], m.uvs[last * 2 + c], 1e-5f)
    }
  }

  private fun assertUv(u: Float, v: Float, actual: Pair<Float, Float>) {
    assertEquals(u, actual.first, eps)
    assertEquals(v, actual.second, eps)
  }
}
