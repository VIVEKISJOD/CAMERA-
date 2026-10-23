package app.fovea.camera.state

/**
 * Pure geometry for the golden spiral guide (no Android classes, so it can be unit tested).
 * The frame is cut at 61.8% again and again (left, top, right, bottom piece in turn).
 * Each piece gets one quarter-ellipse arc. The arcs join into one continuous curve.
 */
object GoldenSpiral {
    /** One quarter arc: ellipse centre (cx, cy), radii (rx, ry), starts at startDeg and sweeps +90 degrees. */
    data class Arc(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val startDeg: Float)

    private const val B = 0.618034f

    fun arcs(w: Float, h: Float, count: Int = 10): List<Arc> {
        val out = ArrayList<Arc>(count)
        var x = 0f
        var y = 0f
        var rw = w
        var rh = h
        val start = if (w >= h) 0 else 1
        for (i in 0 until count) {
            when ((start + i) % 4) {
                0 -> { // left piece
                    val pw = rw * B
                    out.add(Arc(x + pw, y + rh, pw, rh, 180f))
                    x += pw
                    rw -= pw
                }
                1 -> { // top piece
                    val ph = rh * B
                    out.add(Arc(x, y + ph, rw, ph, 270f))
                    y += ph
                    rh -= ph
                }
                2 -> { // right piece
                    val pw = rw * B
                    out.add(Arc(x + rw - pw, y, pw, rh, 0f))
                    rw -= pw
                }
                else -> { // bottom piece
                    val ph = rh * B
                    out.add(Arc(x + rw, y + rh - ph, rw, ph, 90f))
                    rh -= ph
                }
            }
        }
        return out
    }

    /** Point on an arc at angleDeg (screen coordinates, angle grows clockwise). */
    fun pointAt(a: Arc, angleDeg: Float): Pair<Float, Float> {
        val t = Math.toRadians(angleDeg.toDouble())
        return Pair(a.cx + a.rx * Math.cos(t).toFloat(), a.cy + a.ry * Math.sin(t).toFloat())
    }
}
