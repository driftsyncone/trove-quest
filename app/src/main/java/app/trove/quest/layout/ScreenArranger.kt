package app.trove.quest.layout

import android.content.SharedPreferences
import app.trove.quest.Placement
import app.trove.quest.player.MultiScreen
import app.trove.quest.player.PlayerSession
import app.trove.quest.hold.HoldSystem
import app.trove.quest.posture.PostureController
import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.Vec
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import kotlin.math.abs
import kotlin.math.exp

/** Runs [ScreenArranger.frame] every frame; it does nothing (one flag check) while the layout is at rest. */
class LayoutSystem : SystemBase() {
  var tick: ((Float) -> Unit)? = null
  private var lastNs = System.nanoTime()

  override fun execute() {
    val now = System.nanoTime()
    val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
    lastNs = now
    tick?.invoke(dt)
  }
}

/**
 * Applies [SphereLayout] to the open flat screens (README "Multi-screen", "No overlap"): after any
 * move, resize, open or close it eases overlapping screens apart along the shell round the head
 * (about 300 ms, only the minimal movement, never a held screen; while one is held it pushes the
 * others out of the way in real time), and it owns the Face me setting, the free spot for a new
 * screen and "Tidy". Spheres and Storm are not part of it.
 */
class ScreenArranger(
    private val activity: AppSystemActivity,
    prefs: SharedPreferences,
    private val sessions: () -> List<PlayerSession>,
    private val focused: () -> PlayerSession?,
    private val posture: PostureController,
    private val saveLayout: () -> Unit,
) {
  val faceMe = FaceMe(prefs) { if (it) reface() }

  /** Something may need to move: relaxation runs until the layout is at rest. */
  private var active = false
  private var wasHeld = false

  /** Screens moved since the layout was last saved. */
  private var moved = false

  /** Where "Tidy" is taking screens: eased towards, then dropped once there. */
  private class Goal(val dir: Vec, val distM: Float, val pitchDeg: Float, val rollDeg: Float)

  private val goals = HashMap<PlayerSession, Goal>()

  private var attached = false

  /** Hooks the per-frame system in (the activity registers a [LayoutSystem]); safe to call any time. */
  fun attach(system: LayoutSystem) {
    system.tick = ::frame
    attached = true
  }

  /** The layout changed (a screen opened, closed, resized, let go): relax it. */
  fun kick() {
    active = true
  }

  private fun quads(): List<PlayerSession> = sessions().filter { !it.isSphere && !it.behindSphere && !it.closed && it.currentPose() != null }

  private fun headPos(): Vector3 = Placement.head(activity.scene).first

  private fun shellOf(s: PlayerSession, head: Vector3, dir: Vec? = null, dist: Float? = null): Shell? {
    val p = s.currentPose()?.t ?: return null
    val d = Vec(p.x - head.x, p.y - head.y, p.z - head.z)
    val len = d.length
    if (len < 0.2f) return null
    return Shell(dir ?: (d * (1f / len)), dist ?: len, s.widthM, s.heightM, s.rollDeg)
  }

  // ---- placing a new screen ----

  /**
   * Direction (unit) and distance for a new screen: the free spot nearest to where you look, or to
   * [dropAt] when a card was dropped there. Null when no screen is open yet (the caller keeps its place).
   */
  fun freeSpot(widthM: Float, aspect: Float, dropAt: Vector3? = null, distM: Float = MultiScreen.EXTRA_DISTANCE_M): Pair<Vector3, Float>? {
    val head = headPos()
    val others = quads().mapNotNull { shellOf(it, head) }
    val target: Vec
    var dist = distM
    if (dropAt != null) {
      val d = Vec(dropAt.x - head.x, dropAt.y - head.y, dropAt.z - head.z)
      dist = d.length.coerceIn(0.8f, 6f)
      target = d
    } else {
      val f = activity.scene.getViewerPose().forward()
      target = Vec(f.x, f.y.coerceIn(-0.5f, 0.5f), f.z)
    }
    val dir = SphereLayout.nearestFree(others, widthM, widthM / aspect, dist, target)
    return Vector3(head.x + dir.x * dist, head.y + dir.y * dist, head.z + dir.z * dist) to dist
  }

  // ---- Face me ----

  /** Face me just went on: every flat screen turns to face the head where it is. */
  private fun reface() {
    for (s in quads()) {
      if (s.isHeld) continue
      val p = s.currentPose() ?: continue
      s.placeAt(p, s.pitchDeg, s.rollDeg)
    }
    saveLayout()
  }

  // ---- Tidy ----

  /** Packs the flat screens into a neat grid round the current view, for the current posture; eases there. */
  fun tidy() {
    val list = quads().filter { !it.isHeld }
    if (list.isEmpty()) return
    val (head, bodyYaw) = posture.headAndBodyYaw()
    val centre = list.indexOfFirst { it === focused() }.coerceAtLeast(0)
    val dists = list.mapIndexed { i, _ -> if (i == centre) MultiScreen.FIRST_DISTANCE_M else MultiScreen.EXTRA_DISTANCE_M }
    val shells = list.mapIndexed { i, s -> Shell(Vec(0f, 0f, 1f), dists[i], s.widthM, s.heightM, s.rollDeg) }
    val hv = Vector3(head.x, head.y, head.z)
    val order =
        list.map { s ->
          val p = s.currentPose()?.t ?: return@map 0f
          PostureLayout.wrap(PostureLayout.yawOf(Vec(p.x - hv.x, 0f, p.z - hv.z)) - bodyYaw)
        }
    // Rolled screens (lying on your side) use their tall edge across: use the unrotated size, the posture turns the grid.
    val slots =
        SphereLayout.tidy(
            shells.map { it.copy(rollDeg = 0f).halfWidthDeg * 2f },
            shells.map { it.copy(rollDeg = 0f).halfHeightDeg * 2f },
            order,
            centre,
        )
    for ((i, s) in list.withIndex()) {
      val pl = PostureLayout.placeOne(posture.current, head, bodyYaw, slots[i].yawDeg, dists[i], slots[i].elevDeg)
      val d = Vec(pl.pos.x - head.x, pl.pos.y - head.y, pl.pos.z - head.z)
      goals[s] = Goal(d * (1f / d.length.coerceAtLeast(0.01f)), dists[i], pl.pitchDeg, pl.rollDeg)
    }
    active = true
  }

  // ---- per frame ----

  /** One frame: relax, ease, and when everything has settled save the layout once. */
  fun frame(dt: Float) {
    // At rest this is all that runs: one pass over at most six screens.
    if (!active && !wasHeld && sessions().none { it.isHeld }) return
    val list = quads()
    goals.keys.retainAll(list.toSet())
    val held = list.any { it.isHeld }
    if (list.isEmpty()) {
      active = false
      wasHeld = false
      return
    }
    val head = headPos()
    // Where each screen wants to be: its Tidy goal, else where it is now (held ones are pinned there).
    val shells = ArrayList<Shell>(list.size)
    val pinned = HashSet<Int>()
    for ((i, s) in list.withIndex()) {
      val g = goals[s]
      val sh = (if (g != null && !s.isHeld) shellOf(s, head, g.dir, g.distM) else shellOf(s, head))
      shells += sh ?: Shell(Vec(0f, 0f, 1f), 99f, 0.1f, 0.1f)
      if (s.isHeld || sh == null) {
        pinned += i
        if (!s.isHeld) goals.remove(s)
      }
    }
    val solved = SphereLayout.relax(shells, pinned)
    val k = 1f - exp(-dt / EASE_TAU_S)
    var moving = false
    for ((i, s) in list.withIndex()) {
      if (i in pinned) continue
      val cur = shellOf(s, head) ?: continue
      val goal = solved[i]
      val g = goals[s]
      val gDist = g?.distM ?: cur.distM
      val gPitch = g?.pitchDeg ?: s.pitchDeg
      val gRoll = g?.rollDeg ?: s.rollDeg
      val angle = angleBetween(cur.dir, goal.dir)
      val settled = angle < SETTLE_DEG && abs(cur.distM - gDist) < 0.01f && abs(gPitch - s.pitchDeg) < 0.5f && abs(PostureLayout.wrap(gRoll - s.rollDeg)) < 0.5f
      if (settled) {
        if (g != null) {
          goals.remove(s)
          place(s, head, goal.dir, gDist, gPitch, gRoll)
          moved = true
        }
        continue
      }
      moving = true
      moved = true
      val nd = Vec(cur.dir.x + (goal.dir.x - cur.dir.x) * k, cur.dir.y + (goal.dir.y - cur.dir.y) * k, cur.dir.z + (goal.dir.z - cur.dir.z) * k)
      val nl = nd.length.coerceAtLeast(1e-4f)
      place(
          s,
          head,
          nd * (1f / nl),
          cur.distM + (gDist - cur.distM) * k,
          s.pitchDeg + (gPitch - s.pitchDeg) * k,
          s.rollDeg + PostureLayout.wrap(gRoll - s.rollDeg) * k,
      )
    }
    if (held) {
      active = true
    } else if (!moving && goals.isEmpty()) {
      active = false
      if (moved || wasHeld) saveLayout()
      moved = false
    }
    wasHeld = held
  }

  private fun place(s: PlayerSession, head: Vector3, dir: Vec, dist: Float, pitchDeg: Float, rollDeg: Float) {
    val pos = Vector3(head.x + dir.x * dist, head.y + dir.y * dist, head.z + dir.z * dist)
    s.placeAt(Pose(pos, HoldSystem.levelYaw(Facing.yawDeg(dir.x, dir.z))), pitchDeg, rollDeg)
  }

  private fun angleBetween(a: Vec, b: Vec): Float {
    val c = (a.dot(b) / (a.length * b.length).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f)
    return Math.toDegrees(kotlin.math.acos(c.toDouble())).toFloat()
  }

  private companion object {
    /** Time constant of the easing: ~95 % of the way in 3 x this = 300 ms. */
    const val EASE_TAU_S = 0.1f

    /** Closer than this to the goal counts as there (degrees). */
    const val SETTLE_DEG = 0.15f
  }
}
