package app.trove.quest.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val colors =
    darkColorScheme(
        primary = Palette.accent,
        onPrimary = Palette.onAccent,
        secondary = Palette.accent,
        onSecondary = Palette.onAccent,
        secondaryContainer = Palette.raised,
        onSecondaryContainer = Palette.text,
        background = Palette.panel,
        onBackground = Palette.text,
        surface = Palette.surface,
        onSurface = Palette.text,
        surfaceVariant = Palette.raised,
        onSurfaceVariant = Palette.muted,
        outline = Palette.outline,
        outlineVariant = Palette.outline,
        error = Palette.errorText,
    )

private fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size, lineHeight = size * 1.3f, fontWeight = weight)

/** Material's type scale mapped onto [Type]: stock widgets (buttons, fields) pick it up. */
private val typography =
    Typography(
        titleLarge = style(Type.title, FontWeight.SemiBold),
        titleMedium = style(Type.heading, FontWeight.SemiBold),
        titleSmall = style(Type.body, FontWeight.SemiBold),
        bodyLarge = style(Type.body),
        bodyMedium = style(Type.bodySmall),
        bodySmall = style(Type.caption),
        labelLarge = style(Type.label, FontWeight.Medium),
        labelMedium = style(Type.labelSmall, FontWeight.Medium),
        labelSmall = style(Type.caption, FontWeight.Medium),
    )

/**
 * Our panels draw their own background instead of a Material Surface, so nothing sets the content
 * color and plain Text would fall back to black (dark text on the dark panel). Provide onSurface.
 */
@Composable
fun StashTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = colors, typography = typography) {
    CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
  }
}
