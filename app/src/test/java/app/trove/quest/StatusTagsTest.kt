package app.trove.quest

import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncState
import app.trove.quest.ui.StatusTag
import app.trove.quest.ui.StatusTags
import app.trove.quest.ui.TagLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTagsTest {
  private val connected = SyncState(link = SyncLink.CONNECTED)
  private val stopped = SyncState(link = SyncLink.CONNECTED, stopped = true, byPartner = true, reason = "STOP ALL")

  private fun controls(
      sync: SyncState = SyncState(),
      error: String? = null,
      hint: String? = null,
      highlighted: Boolean = false,
      audible: Boolean = true,
      behindSphere: Boolean = false,
      quality: String = "",
  ) = StatusTags.controls(sync, error, hint, highlighted, audible, behindSphere, quality).map { it.text }

  @Test
  fun nothingToSayIsEmpty() {
    assertEquals(emptyList<String>(), controls())
  }

  @Test
  fun allFineSyncIsQuietOnControlsButShownOnTheWall() {
    assertEquals(emptyList<String>(), controls(sync = connected))
    assertEquals(StatusTag("Link ok", TagLevel.OK), StatusTags.sync(connected))
    assertEquals(StatusTag("Link ok · toy unarmed", TagLevel.NEUTRAL), StatusTags.sync(connected.copy(armed = false)))
    assertEquals(StatusTag("Link ok · toy on", TagLevel.ACCENT), StatusTags.sync(connected.copy(armed = true, device = "playing")))
    assertNull(StatusTags.sync(SyncState()))
  }

  @Test
  fun partnerStopComesFirstAndIsAnAlert() {
    val tags = StatusTags.controls(stopped, null, null, true, true, false, "720p")
    assertEquals(StatusTag("Stopped by your partner — wait for them to resume", TagLevel.ALERT), tags.first())
    assertEquals(listOf("Stopped by your partner — wait for them to resume", "Sound", "720p"), tags.map { it.text })
  }

  @Test
  fun hintReplacesEverythingExceptAlertAndError() {
    assertEquals(listOf("Stick: size"), controls(hint = "Stick: size", highlighted = true, quality = "720p", sync = SyncState(link = SyncLink.RECONNECTING)))
    assertEquals(
        listOf("Stopped by your partner — wait for them to resume", "Stick: size"),
        StatusTags.controls(stopped, null, "Stick: size", true, true, false, "720p").map { it.text })
    assertEquals(listOf("boom", "Stick: size"), controls(error = "boom", hint = "Stick: size"))
  }

  @Test
  fun errorBeatsSyncWarnAndSound() {
    val tags = StatusTags.controls(SyncState(link = SyncLink.RECONNECTING), "Playback error", null, true, true, false, "")
    assertEquals(listOf("Playback error", "Sync: reconnecting", "Sound"), tags.map { it.text })
    assertEquals(TagLevel.ALERT, tags[0].level)
    assertEquals(TagLevel.WARN, tags[1].level)
  }

  @Test
  fun soundOrMutedAndNeverBothAndMutedNotBehindASphere() {
    assertEquals(listOf("Sound"), controls(highlighted = true, audible = true))
    assertEquals(listOf("Muted"), controls(audible = false))
    assertEquals(emptyList<String>(), controls(audible = false, behindSphere = true))
  }

  @Test
  fun neverMoreThanThreeAndLowestPriorityDropsFirst() {
    val tags =
        StatusTags.controls(SyncState(link = SyncLink.RECONNECTING), "err", null, true, true, false, "720p")
    assertEquals(StatusTags.MAX_ON_CONTROLS, tags.size)
    assertEquals(listOf("err", "Sync: reconnecting", "Sound"), tags.map { it.text })
  }

  @Test
  fun blankErrorIsIgnored() {
    assertEquals(emptyList<String>(), controls(error = "  "))
  }
}
