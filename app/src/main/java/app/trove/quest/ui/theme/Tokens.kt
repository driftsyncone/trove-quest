package app.trove.quest.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The design tokens (docs/ux-audit.md, "Design system"). Panels are ~700 dp per metre and seen at
// 1-3 m, so nothing is smaller than 14 sp or 48 dp to click.

object Space {
  val xs = 4.dp
  val sm = 8.dp
  val md = 12.dp
  val lg = 16.dp
  val xl = 24.dp
}

object Radius {
  val badge = 6.dp
  val pill = 8.dp
  val control = 12.dp // buttons, chips, fields
  val card = 14.dp
  val controlsPanel = 20.dp
  val wallPanel = 28.dp
}

object Size {
  /** Minimum height/width of anything clickable. */
  val target = 48.dp
  /** The one big button (play / pause). */
  val targetLarge = 56.dp
  val pillHeight = 28.dp
  val infoButton = 44.dp
  val borderThin = 1.dp
  val borderFocus = 3.dp
}

object Type {
  val title = 26.sp
  val heading = 20.sp
  val body = 18.sp
  val bodySmall = 16.sp
  val label = 17.sp
  val labelSmall = 15.sp
  val caption = 14.sp // the floor
}

/** Dark, high contrast, one accent. */
object Palette {
  val panel = Color(0xF2101216)
  val surface = Color(0xFF1A1D23)
  val raised = Color(0xFF272C35)
  val outline = Color(0xFF6B7483)
  val text = Color(0xFFF4F6F8)
  val muted = Color(0xFFC4CAD3)
  val scrim = Color(0xCC000000)

  val accent = Color(0xFF8AB4FF)
  val onAccent = Color(0xFF08142B)

  val ok = Color(0xFF7FD8A0)
  val warn = Color(0xFFFFC27A)
  val alert = Color(0xFFE5484D)
  val onAlert = Color.White
  val errorText = Color(0xFFFF9A92)

  // Category colours for facet kind labels (text only).
  val performer = Color(0xFFF2A7C3)
  val studio = Color(0xFF8AB4FF)
  val tag = Color(0xFFA5D6A7)
}

/** The focused screen (multi-screen): bar over the screen, border round its controls. */
val FocusAccent = Palette.accent.copy(alpha = 0.8f)
