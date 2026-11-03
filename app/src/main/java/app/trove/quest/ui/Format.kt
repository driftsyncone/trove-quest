package app.trove.quest.ui

// Same labels and rules as web/src/main.ts so the two UIs read alike.

fun fmtDuration(seconds: Double): String {
  val s = seconds.toLong().coerceAtLeast(0)
  val hh = s / 3600
  val mm = (s % 3600) / 60
  val ss = (s % 60).toString().padStart(2, '0')
  return if (hh > 0) "$hh:${mm.toString().padStart(2, '0')}:$ss" else "$mm:$ss"
}

fun fmtSize(bytes: Long): String {
  val gb = bytes / (1024.0 * 1024 * 1024)
  return if (gb >= 1) String.format(java.util.Locale.ROOT, "%.1f GB", gb) else "${Math.round(bytes / (1024.0 * 1024))} MB"
}

/** The scene details' meta line (web renderDetail's `meta`). */
fun detailMeta(s: app.trove.quest.api.Scene): String =
    listOf(
            s.date.orEmpty(),
            fmtDuration(s.duration),
            listOf(
                    fmtRes(s.width, s.height),
                    s.codec.uppercase(),
                    s.file?.fps?.takeIf { it > 0 }?.let { "${Math.round(it)} fps" }.orEmpty(),
                )
                .filter { it.isNotEmpty() }
                .joinToString(" "),
            s.file?.let { fmtSize(it.size) }.orEmpty(),
        )
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

fun fmtRes(w: Int, h: Int): String {
  val p = minOf(w, h)
  val m = maxOf(w, h)
  return when {
    m >= 7000 -> "8K"
    m >= 5000 -> "6K"
    m >= 3800 -> "4K"
    p >= 1400 -> "1440p"
    p >= 1000 -> "1080p"
    p >= 700 -> "720p"
    p > 0 -> "${p}p"
    else -> ""
  }
}

val SORT_LABELS =
    mapOf(
        "date" to "Release date",
        "created_at" to "Date added",
        "updated_at" to "Date updated",
        "title" to "Title",
        "random" to "Random",
        "rating" to "Rating",
        "play_count" to "Play count",
        "o_counter" to "O count",
        "duration" to "Duration",
        "last_played_at" to "Last played",
        "filesize" to "File size",
        "interactive_speed" to "Script speed",
        "performer" to "Performer",
        "studio" to "Studio",
        // library sections (performers / studios / tags)
        "name" to "Name",
        "scenes" to "Scene count",
        "created" to "Recently added",
        "birthdate" to "Birthdate",
        "age" to "Age",
    )
