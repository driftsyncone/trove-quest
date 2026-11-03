package app.trove.quest

import app.trove.quest.player.RoomView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomViewTest {
  @Test
  fun savedIdsRoundTripAndUnknownIsOff() {
    for (v in RoomView.entries) assertEquals(v, RoomView.fromId(v.id))
    assertEquals(RoomView.OFF, RoomView.fromId(null))
    assertEquals(RoomView.OFF, RoomView.fromId("dim100"))
  }

  @Test
  fun darkeningLevels() {
    assertEquals(listOf(0f, 0.5f, 0.8f), RoomView.entries.filter { it.passthrough }.map { it.dim })
  }

  @Test
  fun theWallShowsTheRoomOnlyWhenItsToggleIsOn() {
    assertFalse(RoomView.passthroughOn(RoomView.CLEAR, screensOpen = false, sphereActive = false))
    assertTrue(RoomView.passthroughOn(RoomView.OFF, screensOpen = false, sphereActive = false, onWall = true))
    assertFalse(RoomView.passthroughOn(RoomView.CLEAR, screensOpen = false, sphereActive = true, onWall = true))
    // with screens open the wall toggle doesn't matter: View -> Behind decides
    assertFalse(RoomView.passthroughOn(RoomView.OFF, screensOpen = true, sphereActive = false, onWall = true))
  }

  @Test
  fun passthroughOnlyBehindFlatAnd3dScreens() {
    for (v in RoomView.entries) {
      // Spheres (180/360/fisheye) and the wall on its own keep it off.
      assertFalse(RoomView.passthroughOn(v, screensOpen = true, sphereActive = true))
      assertFalse(RoomView.passthroughOn(v, screensOpen = false, sphereActive = false))
    }
    assertFalse(RoomView.passthroughOn(RoomView.OFF, screensOpen = true, sphereActive = false))
    assertTrue(RoomView.passthroughOn(RoomView.CLEAR, screensOpen = true, sphereActive = false))
    assertTrue(RoomView.passthroughOn(RoomView.DIM80, screensOpen = true, sphereActive = false))
  }
}
