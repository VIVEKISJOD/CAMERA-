package app.fovea.camera.state

enum class CodeCheck { EMPTY, TOO_SHORT, TOO_LONG, BAD_CHARACTERS, WELL_FORMED }

/**
 * Stage 1: format check only (length and alphabet). It does NOT decode anything.
 * The real binary encoding, versioning and validation are built in Stage 9.
 */
object PresetCodeFormat {
    const val LENGTH = 8

    fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()

    fun check(raw: String): CodeCheck {
        val c = normalize(raw)
        return when {
            c.isEmpty() -> CodeCheck.EMPTY
            c.any { it !in 'A'..'Z' && it !in '0'..'9' } -> CodeCheck.BAD_CHARACTERS
            c.length < LENGTH -> CodeCheck.TOO_SHORT
            c.length > LENGTH -> CodeCheck.TOO_LONG
            else -> CodeCheck.WELL_FORMED
        }
    }
}
