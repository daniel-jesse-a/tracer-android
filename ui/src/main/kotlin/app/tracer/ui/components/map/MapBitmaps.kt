package app.tracer.ui.components.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import app.tracer.ui.theme.TracerExtraColors

/** Small map bitmaps drawn with android.graphics; shared by both map backends. */
internal object MapBitmaps {

    /** Pin fill by severity: info white · warn yellow · drop/critical pink. */
    fun pinColor(severity: String, c: TracerExtraColors): Color = when (severity) {
        "critical" -> c.bad
        "warning" -> c.fair
        "waypoint" -> c.info // user waypoint (Replay)
        else -> Color.White
    }

    /** ≥16 dp dot with a 2.5 dp ground-colour halo; [selected] adds an outer ring in [ringColor]. */
    fun pin(density: Float, fill: Int, halo: Int, selected: Boolean, ringColor: Int): Bitmap {
        val dotR = 8f * density
        val haloW = 2.5f * density
        val ringW = if (selected) 3f * density else 0f
        val gap = if (selected) 2f * density else 0f
        val r = dotR + haloW + gap + ringW
        val size = (2 * r + 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (selected) {
            p.style = Paint.Style.STROKE; p.strokeWidth = ringW; p.color = ringColor
            cv.drawCircle(cx, cx, r - ringW / 2, p)
        }
        p.style = Paint.Style.FILL; p.color = halo
        cv.drawCircle(cx, cx, dotR + haloW, p)
        p.color = fill
        cv.drawCircle(cx, cx, dotR, p)
        return bmp
    }

    /** Current position: blue dot, white rim, translucent halo. */
    fun position(density: Float, color: Int): Bitmap {
        val r = 14f * density
        val size = (2 * r + 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color; p.alpha = 64
        cv.drawCircle(cx, cx, r, p)
        p.color = android.graphics.Color.WHITE
        cv.drawCircle(cx, cx, 7.5f * density, p)
        p.color = color
        cv.drawCircle(cx, cx, 6f * density, p)
        return bmp
    }

    /** Outlined triangle with a Mono label to its right ("PCI 214"); anchor = bottom-left area. */
    fun tower(density: Float, stroke: Int, textColor: Int, halo: Int, label: String): Bitmap {
        val tri = 18f * density
        val text = textPaint(density, textColor)
        val tw = if (label.isEmpty()) 0f else text.measureText(label) + 6f * density
        val w = (tri + tw + 4 * density).toInt()
        val h = (tri + 4 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val path = Path().apply {
            moveTo(2 * density + tri / 2, 2 * density)
            lineTo(2 * density + tri, 2 * density + tri)
            lineTo(2 * density, 2 * density + tri)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        p.color = halo; p.strokeWidth = 5f * density
        cv.drawPath(path, p)
        p.color = stroke; p.strokeWidth = 2f * density
        cv.drawPath(path, p)
        if (label.isNotEmpty()) drawHaloText(cv, label, tri + 8 * density, h / 2f + text.textSize / 3, text, halo, density)
        return bmp
    }

    /** Text with a halo (for ring labels such as "TA 12 · 937 m"). */
    fun label(density: Float, textColor: Int, halo: Int, label: String): Bitmap {
        val text = textPaint(density, textColor)
        val w = (text.measureText(label) + 8 * density).toInt().coerceAtLeast(1)
        val h = (text.textSize + 8 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawHaloText(Canvas(bmp), label, 4 * density, h / 2f + text.textSize / 3, text, halo, density)
        return bmp
    }

    /** Numbered cluster circle. */
    fun cluster(density: Float, count: Int, fill: Int, stroke: Int, textColor: Int): Bitmap {
        val r = 13f * density
        val size = (2 * r + 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = fill
        cv.drawCircle(cx, cx, r - density, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * density; p.color = stroke
        cv.drawCircle(cx, cx, r - density, p)
        val t = textPaint(density, textColor).apply { textAlign = Paint.Align.CENTER }
        cv.drawText(count.toString(), cx, cx + t.textSize / 3, t)
        return bmp
    }

    private fun textPaint(density: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = 11f * density
        typeface = Typeface.MONOSPACE
    }

    private fun drawHaloText(cv: Canvas, s: String, x: Float, y: Float, text: Paint, halo: Int, density: Float) {
        val h = Paint(text).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = halo }
        cv.drawText(s, x, y, h)
        cv.drawText(s, x, y, text)
    }
}
