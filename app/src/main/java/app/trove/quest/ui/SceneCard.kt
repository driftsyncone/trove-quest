package app.trove.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import app.trove.quest.api.Card
import app.trove.quest.api.TroveApi
import app.trove.quest.drag.CardDrag
import app.trove.quest.drag.DragRules
import app.trove.quest.ui.theme.Badge
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space

/**
 * One scene on the wall: 16:9 thumbnail (resolution / projection and Script top left, duration
 * bottom right, a thin accent bar where you left off, the i button top right), then a two-line title
 * and "studio · date". Tap = play, hold = new screen, drag = into the room.
 */
@Composable
fun SceneCard(
    card: Card,
    api: TroveApi,
    images: ImageLoader,
    resumeFraction: Float?,
    onInfo: () -> Unit,
    drag: CardDrag,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(Radius.card)
  Column(
      modifier =
          Modifier.clip(shape)
              .background(Palette.surface)
              .border(Size.borderThin, Palette.outline.copy(alpha = 0.4f), shape)
              .combinedClickable(onClick = onClick, onLongClick = onLongClick)
              // After the click handler = sees each event first. Watches the press; past the threshold it
              // tells CardDrag (whose system then owns the drag) and consumes the moves, which also
              // cancels the click / long-press and keeps the grid from scrolling under the card.
              .cardDragStart(card, drag),
  ) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
      AsyncImage(
          model = api.thumbUrl(card),
          imageLoader = images,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
      Row(
          modifier = Modifier.align(Alignment.TopStart).padding(Space.sm),
          horizontalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        WallLogic.qualityBadge(card)?.let { Badge(it) }
        if (card.interactive) Badge("Script")
      }
      Badge(fmtDuration(card.duration), Modifier.align(Alignment.BottomEnd).padding(Space.sm))
      if (resumeFraction != null) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Palette.scrim)) {
          Box(Modifier.fillMaxWidth(resumeFraction).height(5.dp).background(Palette.accent))
        }
      }
      // Details (tags, performers, markers). Long-press is taken by "new screen", so a button.
      Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.align(Alignment.TopEnd).size(Size.target).clickable(onClick = onInfo),
      ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.scrim), contentAlignment = Alignment.Center) {
          Text("i", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
    Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
      Text(
          card.title,
          minLines = 2,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          WallLogic.cardMeta(card).ifEmpty { " " },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
          color = Palette.muted,
      )
    }
  }
}

/** Press on a card = [CardDrag.press]; travel past [DragRules.START_THRESHOLD_DP] = [CardDrag.moved]. */
private fun Modifier.cardDragStart(card: Card, drag: CardDrag): Modifier =
    pointerInput(card.id) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        drag.press(card)
        val slop = DragRules.START_THRESHOLD_DP.dp.toPx()
        var started = false
        while (true) {
          val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
          if (!started && (change.position - down.position).getDistance() > slop) {
            started = true
            drag.moved(card)
          }
          if (started) change.consume()
          if (!change.pressed) break
        }
      }
    }
