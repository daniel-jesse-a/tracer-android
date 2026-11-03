package app.tracer.pairing.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders [text] as a QR code (ECC M, 1-module quiet zone). Draws nothing if it cannot be encoded. */
@Composable
fun QrCodeImage(text: String, modifier: Modifier = Modifier, sizePx: Int = 768) {
    val bitmap = remember(text, sizePx) { QrBitmaps.encode(text, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing QR code",
            modifier = modifier,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

/** ZXing → [Bitmap] encoder behind [QrCodeImage]. */
object QrBitmaps {
    fun encode(text: String, sizePx: Int): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return try {
            val hints: Map<EncodeHintType, Any> = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null // WriterException (payload too long) or IllegalArgumentException
        }
    }
}
