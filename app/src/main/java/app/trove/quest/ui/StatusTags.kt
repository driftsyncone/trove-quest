package app.trove.quest.ui

import app.trove.quest.sync.SyncBadge
import app.trove.quest.sync.SyncState

/** How loud a read-only status pill is (docs/ux-audit.md, "Status tag rules"). */
enum class TagLevel {
  NEUTRAL,
  ACCENT,
  OK,
  WARN,
  ALERT,
}

data class StatusTag(val text: String, val level: TagLevel)

/**
 * Which status pills to show and in which order. Pure, so the precedence is unit tested.
 *
 * Order: alert (partner STOP) > error > hint > sync > sound / muted > session timer > quality. A hint (the stick help
 * while a panel is held) replaces everything except an alert and an error. At most [MAX_ON_CONTROLS]
 * pills show on a screen's controls, so the title always keeps room.
 */
object StatusTags {
  const val MAX_ON_CONTROLS = 3

  /** The sync pill for [state]; null = nothing to say. [quiet] hides the all-fine state (controls). */
  fun sync(state: SyncState, quiet: Boolean = false): StatusTag? =
      SyncBadge.of(state, quiet)?.let {
        StatusTag(
            it.text,
            when (it.level) {
              SyncBadge.Level.NEUTRAL -> TagLevel.NEUTRAL
              SyncBadge.Level.ACCENT -> TagLevel.ACCENT
              SyncBadge.Level.OK -> TagLevel.OK
              SyncBadge.Level.WARN -> TagLevel.WARN
              SyncBadge.Level.ALERT -> TagLevel.ALERT
            })
      }

  fun controls(
      sync: SyncState,
      error: String?,
      hint: String?,
      highlighted: Boolean,
      audible: Boolean,
      behindSphere: Boolean,
      quality: String,
      /** Session timer tag text ("Timer 28:41", [app.trove.quest.sync.SessionTimer.tagText]); null = none. */
      timer: String? = null,
      /** The last five minutes: the timer tag is amber. */
      timerWarn: Boolean = false,
  ): List<StatusTag> {
    val syncTag = sync(sync, quiet = true)
    val out = mutableListOf<StatusTag>()
    if (syncTag?.level == TagLevel.ALERT) out += syncTag
    error?.takeIf { it.isNotBlank() }?.let { out += StatusTag(it, TagLevel.ALERT) }
    if (hint != null) {
      out += StatusTag(hint, TagLevel.ACCENT)
      return out.take(MAX_ON_CONTROLS)
    }
    if (syncTag != null && syncTag.level != TagLevel.ALERT) out += syncTag
    if (highlighted) out += StatusTag("Sound", TagLevel.ACCENT)
    else if (!audible && !behindSphere) out += StatusTag("Muted", TagLevel.NEUTRAL)
    // Next to the sound tag: the session timer's time left.
    timer?.let { out += StatusTag(it, if (timerWarn) TagLevel.WARN else TagLevel.NEUTRAL) }
    if (quality.isNotEmpty()) out += StatusTag(quality, TagLevel.NEUTRAL)
    return out.take(MAX_ON_CONTROLS)
  }
}
