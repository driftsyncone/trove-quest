package app.trove.quest.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR modules for [text], one matrix cell per module, no quiet zone (the composable adds one). */
fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter()
        .encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )

private fun qrBitmap(text: String): ImageBitmap {
  val m = qrMatrix(text)
  val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) BLACK else WHITE }
  return android.graphics.Bitmap.createBitmap(pixels, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
      .asImageBitmap()
}

/** Black on white with a white quiet zone; scaled without smoothing so modules stay sharp. */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
  val bitmap = remember(text) { qrBitmap(text) }
  Image(
      bitmap = bitmap,
      contentDescription = "QR code for $text",
      filterQuality = FilterQuality.None,
      modifier = modifier.background(Color.White).padding(size / 14).size(size - size / 7),
  )
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
