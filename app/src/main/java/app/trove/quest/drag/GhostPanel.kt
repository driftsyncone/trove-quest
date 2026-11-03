package app.trove.quest.drag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.ImageLoader
import coil3.compose.AsyncImage
import app.trove.quest.api.TroveApi
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StashTheme

/** The card being dragged: its thumbnail, title and what releasing here does. */
@Composable
fun GhostPanel(drag: CardDrag, api: TroveApi, images: ImageLoader) {
  val card = drag.active
  StashTheme {
    if (card != null) {
      Column(
          Modifier.fillMaxSize().alpha(0.92f).clip(RoundedCornerShape(Radius.card)).background(Palette.panel).padding(Space.sm),
      ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(Radius.control)).background(Color.Black)) {
          AsyncImage(
              model = api.thumbUrl(card),
              imageLoader = images,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
          )
        }
        Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            DragRules.ghostHint(drag.overWall, drag.dropReplaces),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.accent,
        )
      }
    }
  }
}
