package app.trove.quest.sync

/**
 * Where the wearer is looking, for the partner console's map (README "What they see"). Angles are
 * the head's own look direction in the same frame as each [SyncScreen]'s bearing (yaw 0 = +z, + turns
 * right, pitch + = up), so the console draws one relative to the other.
 */
data class HeadView(
    val yawDeg: Float,
    val pitchDeg: Float,
    /** [SyncScreen.id] of the screen the head faces, or null. */
    val facing: String?,
    /** [app.trove.quest.posture.Posture.id]. */
    val posture: String,
) {
  /** Changes when the console's picture does (10 degree steps), so the reporter sends at once. */
  fun key(): String = "${(yawDeg / 10f).toInt()}:${(pitchDeg / 10f).toInt()}:$facing:$posture"
}
