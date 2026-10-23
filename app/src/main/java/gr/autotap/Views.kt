package gr.autotap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Στρογγυλός στόχος με σταυρόνημα και αριθμό (όπως στις φωτογραφίες). */
@SuppressLint("ViewConstructor")
class TargetView(ctx: Context, var label: String, private val isEnd: Boolean) : View(ctx) {
    var active = false
        set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 40, 55); textAlign = Paint.Align.CENTER; textSize = 18 * d
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = width / 2f - 8 * d
        val main = when {
            active -> Color.rgb(255, 80, 60)
            isEnd -> Color.rgb(255, 160, 40)
            else -> Color.rgb(70, 170, 220)
        }
        fill.color = Color.argb(150, 255, 255, 255)
        c.drawCircle(cx, cy, r, fill)
        ring.color = main
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, r - 4 * d, ring.apply { strokeWidth = 1.5f * d })
        ring.strokeWidth = 3 * d
        // σταυρόνημα
        val t = 7 * d
        c.drawLine(cx, cy - r - t, cx, cy - r + t, ring)
        c.drawLine(cx, cy + r - t, cx, cy + r + t, ring)
        c.drawLine(cx - r - t, cy, cx - r + t, cy, ring)
        c.drawLine(cx + r - t, cy, cx + r + t, cy, ring)
        c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }
}

/** Πλήρης οθόνη, μη αγγίξιμη: ζωγραφίζει τις γραμμές των συρσιμάτων. */
class LinesView(ctx: Context, private val steps: () -> List<Step>) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 160, 40); strokeWidth = 4 * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14 * d, 8 * d), 0f)
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 160, 40) }
    private val loc = IntArray(2)

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        for (s in steps()) {
            if (s.type != Step.Type.SWIPE) continue
            val x1 = s.x - loc[0]; val y1 = s.y - loc[1]
            val x2 = s.x2 - loc[0]; val y2 = s.y2 - loc[1]
            c.drawLine(x1, y1, x2, y2, p)
            // βέλος
            val ang = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val a = 16 * d
            val path = Path().apply {
                moveTo(x2, y2)
                lineTo((x2 - a * Math.cos(ang - 0.45)).toFloat(), (y2 - a * Math.sin(ang - 0.45)).toFloat())
                lineTo((x2 - a * Math.cos(ang + 0.45)).toFloat(), (y2 - a * Math.sin(ang + 0.45)).toFloat())
                close()
            }
            c.drawPath(path, head)
        }
    }
}
