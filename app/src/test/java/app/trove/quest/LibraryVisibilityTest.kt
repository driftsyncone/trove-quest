package app.trove.quest

import app.trove.quest.player.LibraryVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryVisibilityTest {
  private val changes = mutableListOf<Boolean>()
  private val lib = LibraryVisibility { changes += it }

  @Test
  fun openingASceneHidesTheLibraryAndClosingBringsItBack() {
    assertTrue(lib.visible)
    lib.sceneOpened()
    assertFalse(lib.visible)
    lib.allClosed()
    assertTrue(lib.visible)
    assertEquals(listOf(false, true), changes)
  }

  @Test
  fun libraryButtonTogglesAndPickingAnotherSceneHidesAgain() {
    lib.sceneOpened()
    lib.toggle() // Library
    assertTrue(lib.visible)
    lib.sceneOpened() // picked another scene on the wall
    assertFalse(lib.visible)
    lib.toggle()
    lib.toggle() // Library, then Hide library
    assertFalse(lib.visible)
    assertEquals(listOf(false, true, false, true, false), changes)
  }

  @Test
  fun noChangeCallbackWhenNothingChanges() {
    lib.allClosed()
    lib.sceneOpened()
    lib.sceneOpened()
    assertEquals(listOf(false), changes)
  }
}
