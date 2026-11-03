package app.trove.quest.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * What the partner console shows about the headset (README M5 "App status the phone shows").
 * The console renders the map as-is ("battery: 81 · charging: false · ..."), so the keys are short.
 *
 * Not reported: whether the headset is worn. Horizon OS has no public proximity / mount API for
 * apps; taking it off pauses the activity after a moment, which shows as `inOurApp: false`.
 */
data class HeadsetStatus(
    /** Activity resumed and the XR session focused (no Meta menu, not in another app). */
    val inOurApp: Boolean,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val tempC: Float? = null,
    val app: String? = null,
    /** Storm mode ("on, 9 tiles"); null while it is off. The console shows it as `storm: on, 9 tiles`. */
    val storm: String? = null,
) {
  fun toMap(): Map<String, Any> = buildMap {
    put("inOurApp", inOurApp)
    battery?.let { put("battery", it) }
    charging?.let { put("charging", it) }
    // One decimal: the console shows the number as sent.
    tempC?.let { put("tempC", Math.round(it * 10) / 10.0) }
    app?.let { put("app", it) }
    storm?.let { put("storm", it) }
  }
}

/** Battery %, charging and temperature from the sticky ACTION_BATTERY_CHANGED broadcast. */
class BatteryReader(private val context: Context, private val clock: () -> Long) {
  data class Reading(val percent: Int?, val charging: Boolean?, val tempC: Float?)

  private var last: Reading = Reading(null, null, null)
  private var readAt = Long.MIN_VALUE / 2

  /** Reads at most every [MIN_INTERVAL_MS] (it changes slowly; the heartbeat asks every second). */
  fun read(): Reading {
    val now = clock()
    if (now - readAt < MIN_INTERVAL_MS) return last
    readAt = now
    // No receiver: returns the last sticky intent without registering anything.
    val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return last
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) // tenths of a degree C
    last =
        Reading(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging =
                if (status < 0) null
                else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            tempC = if (temp != Int.MIN_VALUE) temp / 10f else null,
        )
    return last
  }

  companion object {
    const val MIN_INTERVAL_MS = 10_000L
  }
}
