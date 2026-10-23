package com.group16.dinoblog.model

object DisplayNames {
    const val FALLBACK = "DinoBlog User"
    private const val MAX_CODE_POINTS = 32

    fun normalize(input: String?): String? {
        if (input == null) return null
        val cleaned = buildString {
            input.codePoints().forEach { codePoint ->
                if (Character.isISOControl(codePoint)) append(' ')
                else appendCodePoint(codePoint)
            }
        }.trim().replace(Regex("\\s+"), " ")
        return cleaned.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_CODE_POINTS }
    }
}
