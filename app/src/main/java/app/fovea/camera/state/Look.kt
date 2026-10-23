package app.fovea.camera.state

import kotlin.math.roundToInt

/**
 * Built-in color looks. These are color grades applied to the saved photo (not the camera sensor).
 * saturation/contrast are multipliers, brightness is in 0..255 steps, warmth/tint are -1..1,
 * fade lifts the blacks (0..1), vignette darkens the corners (0..1).
 */
enum class Look(
    val label: String,
    val short: String,
    val blurb: String,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f
) {
    OFF("No look", "OFF", "Photo is saved exactly as the camera made it"),
    VIVID_POP("Vivid Pop", "VIVID", "Punchy, saturated colors", saturation = 1.35f, contrast = 1.12f, brightness = 4f, warmth = 0.15f, vignette = 0.12f),
    WARM_GOLD("Warm Gold", "WARM", "Golden, sunny tones", saturation = 1.15f, contrast = 1.08f, brightness = 6f, warmth = 0.6f, tint = 0.1f, vignette = 0.15f),
    COOL_TEAL("Cool Teal", "TEAL", "Cooler shadows, clean blues", saturation = 1.1f, contrast = 1.1f, warmth = -0.5f, tint = -0.2f, vignette = 0.1f),
    RICH_CONTRAST("Rich Contrast", "RICH", "Deep blacks, strong punch", saturation = 1.2f, contrast = 1.3f, brightness = -6f, vignette = 0.2f),
    SOFT_FADE("Soft Fade", "FADE", "Light, matte, gentle colors", saturation = 0.9f, contrast = 0.9f, brightness = 8f, warmth = 0.1f, fade = 0.4f),
    NATURAL_PLUS("Natural+", "NATURAL", "Slight boost, true-to-life", saturation = 1.12f, contrast = 1.06f, warmth = 0.05f),
    // Added in Stage 3. APPEND ONLY: Stage 6 preset codes will refer to looks by position.
    GOLDEN_HOUR("Golden Hour", "GOLDEN", "Warm sunset glow", saturation = 1.25f, contrast = 1.1f, brightness = 8f, warmth = 0.8f, tint = 0.05f, fade = 0.05f, vignette = 0.18f),
    CINEMATIC("Cinematic", "CINE", "Moody, filmic contrast", saturation = 1.05f, contrast = 1.22f, brightness = -4f, warmth = 0.2f, tint = -0.15f, fade = 0.15f, vignette = 0.28f),
    NOIR("Noir", "NOIR", "Black and white, deep contrast", saturation = 0f, contrast = 1.3f, brightness = -4f, vignette = 0.3f),
    MONO_SOFT("Soft Mono", "MONO", "Gentle black and white", saturation = 0f, contrast = 0.95f, brightness = 6f, fade = 0.2f),
    PASTEL("Pastel", "PASTEL", "Light, airy and soft", saturation = 0.8f, contrast = 0.85f, brightness = 12f, warmth = 0.1f, fade = 0.3f),
    ELECTRIC("Electric", "NEON", "Bold, cool neon-like colors", saturation = 1.6f, contrast = 1.2f, warmth = -0.25f, tint = 0.3f, vignette = 0.15f),
    TROPICAL("Tropical", "TROPIC", "Lush greens and bright color", saturation = 1.45f, contrast = 1.15f, brightness = 3f, warmth = 0.1f, tint = -0.3f),
    SUNSET_BLAZE("Sunset Blaze", "BLAZE", "Hot oranges and reds", saturation = 1.4f, contrast = 1.2f, warmth = 0.85f, vignette = 0.2f),
    BRIGHT_CLEAN("Bright & Clean", "CLEAN", "Fresh, bright and clear", saturation = 1.15f, contrast = 1.05f, brightness = 14f, warmth = -0.05f),
    MOODY_DARK("Moody Dark", "MOODY", "Dark, rich and dramatic", saturation = 0.95f, contrast = 1.2f, brightness = -14f, warmth = -0.2f, fade = 0.1f, vignette = 0.35f),
    RETRO_FILM("Retro Film", "RETRO", "Faded vintage film color", saturation = 0.85f, contrast = 1.05f, warmth = 0.3f, tint = 0.1f, fade = 0.3f, vignette = 0.25f),
    SKIN_GLOW("Skin Glow", "GLOW", "Soft, warm and flattering", saturation = 1.08f, contrast = 0.95f, brightness = 8f, warmth = 0.25f, fade = 0.1f),
    ULTRA_VIVID("Ultra Vivid", "ULTRA", "Maximum color pop", saturation = 1.6f, contrast = 1.15f, brightness = 2f, warmth = 0.1f, vignette = 0.1f)
}

/** Pure color math, so it can be unit tested without Android. Produces an Android-style 4x5 matrix. */
object LookMath {
    private fun identity() = Array(5) { i -> FloatArray(5) { j -> if (i == j) 1f else 0f } }

    private fun mul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val out = Array(5) { FloatArray(5) }
        for (i in 0 until 5) for (j in 0 until 5) {
            var s = 0f
            for (k in 0 until 5) s += a[i][k] * b[k][j]
            out[i][j] = s
        }
        return out
    }

    fun matrix(look: Look): FloatArray {
        var m = identity()

        val s = look.saturation
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        val sat = identity()
        sat[0] = floatArrayOf(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0f, 0f)
        sat[1] = floatArrayOf(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0f, 0f)
        sat[2] = floatArrayOf(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0f, 0f)
        m = mul(sat, m)

        val c = look.contrast
        val con = identity()
        for (i in 0..2) { con[i][i] = c; con[i][4] = 128f * (1 - c) }
        m = mul(con, m)

        val bf = identity()
        for (i in 0..2) { bf[i][i] = 1 - look.fade; bf[i][4] = look.brightness + look.fade * 40f }
        m = mul(bf, m)

        val wt = identity()
        wt[0][0] = 1 + 0.12f * look.warmth
        wt[2][2] = 1 - 0.12f * look.warmth
        wt[1][1] = 1 - 0.08f * look.tint
        m = mul(wt, m)

        return FloatArray(20) { idx -> m[idx / 5][idx % 5] }
    }

    /** Applies the look to one pixel (0..255 per channel). */
    fun apply(r: Int, g: Int, b: Int, look: Look): IntArray {
        val m = matrix(look)
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).roundToInt().coerceIn(0, 255)
        return intArrayOf(ch(0), ch(1), ch(2))
    }
}
