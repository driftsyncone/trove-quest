package app.trove.quest.api

/**
 * How a video is laid out. The ids are the server's (server/projection.ts, web/src/projections.ts);
 * keep the two lists in sync.
 */
enum class Projection(val id: String, val label: String, val short: String) {
  FLAT("flat", "Flat 2D screen", "2D"),
  SBS("sbs", "3D screen, side by side", "3D SBS"),
  OU("ou", "3D screen, over/under", "3D OU"),
  SBS180("sbs180", "VR 180°, side by side", "180 SBS"),
  OU180("ou180", "VR 180°, over/under", "180 OU"),
  MONO180("mono180", "VR 180°, mono", "180"),
  MONO360("mono360", "VR 360°, mono", "360"),
  OU360("ou360", "VR 360°, over/under", "360 OU"),
  SBS360("sbs360", "VR 360°, side by side", "360 SBS"),
  FISHEYE190("fisheye190", "Fisheye 190°, side by side", "Fish 190"),
  FISHEYE200("fisheye200", "Fisheye 200°, side by side", "Fish 200"),
  FISHEYE220("fisheye220", "Fisheye 220°, side by side", "Fish 220");

  val isFisheye: Boolean
    get() = id.startsWith("fisheye")

  companion object {
    /** Unknown ids (a newer server) fall back to flat rather than failing the whole wall. */
    fun fromId(id: String?): Projection = entries.firstOrNull { it.id == id } ?: FLAT
  }
}
