package app.fovea.camera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.fovea.camera.state.GuideType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Vector composition guides. Pure overlay: never part of a captured image. */
@Composable
fun GuideOverlay(
    type: GuideType,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.55f),
    strokeDp: Float = 1f
) {
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        drawGuide(type, size.width, size.height, color, strokeDp.dp.toPx())
    }
}

private fun DrawScope.drawGuide(type: GuideType, w: Float, h: Float, color: Color, sw: Float) {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw)

    val phi = 1.618034f
    val a = 1f / (phi * phi) // 0.382
    val b = 1f / phi         // 0.618
    when (type) {
        GuideType.NONE -> Unit
        GuideType.THIRDS -> {
            for (f in listOf(1f / 3f, 2f / 3f)) { line(w * f, 0f, w * f, h); line(0f, h * f, w, h * f) }
        }
        GuideType.GOLDEN_GRID -> {
            for (f in listOf(a, b)) { line(w * f, 0f, w * f, h); line(0f, h * f, w, h * f) }
        }
        GuideType.SPIRAL -> {
            val growth = (ln(phi.toDouble()) / (PI / 2)).toFloat()
            val cx = w * b
            val cy = h * 0.5f
            val r0 = minOf(w, h) * 0.38f
            val path = Path()
            val steps = 160
            for (i in 0..steps) {
                val theta = i * (4f * PI.toFloat()) / steps
                val r = r0 * exp(-growth * theta)
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = sw))
        }
        GuideType.DIAGONAL -> { line(0f, 0f, w, h); line(w, 0f, 0f, h) }
        GuideType.TRIANGLE -> {
            line(0f, h, w, 0f)
            // Feet of the perpendiculars from the two other corners onto the diagonal (0,h)-(w,0).
            val dx = w; val dy = -h
            val len2 = dx * dx + dy * dy
            fun foot(px: Float, py: Float): Offset {
                val t = ((px - 0f) * dx + (py - h) * dy) / len2
                return Offset(0f + t * dx, h + t * dy)
            }
            val f1 = foot(0f, 0f); val f2 = foot(w, h)
            line(0f, 0f, f1.x, f1.y); line(w, h, f2.x, f2.y)
        }
        GuideType.CENTER -> {
            val gap = minOf(w, h) * 0.04f
            line(w / 2 - gap * 3, h / 2, w / 2 + gap * 3, h / 2)
            line(w / 2, h / 2 - gap * 3, w / 2, h / 2 + gap * 3)
        }
        GuideType.SYMMETRY -> line(w / 2, 0f, w / 2, h)
        GuideType.HORIZON -> {
            line(0f, h / 2, w, h / 2)
            for (i in 1..4) { val x = w * i / 5f; line(x, h / 2 - 8.dp.toPx(), x, h / 2 + 8.dp.toPx()) }
        }
        GuideType.SAFE -> {
            drawRect(color, Offset(w * 0.05f, h * 0.05f), androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.9f), style = Stroke(sw))
            drawRect(color.copy(alpha = color.alpha * 0.6f), Offset(w * 0.1f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.8f), style = Stroke(sw))
        }
    }
}
