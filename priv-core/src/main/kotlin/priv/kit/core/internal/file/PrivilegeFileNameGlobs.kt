package priv.kit.core.internal.file

internal class PrivilegeFileNameGlobs private constructor(
    private val patterns: List<IntArray>,
) {
    fun matches(name: String): Boolean {
        if (patterns.isEmpty()) return false
        val codePoints = name.toCodePointArray()
        return patterns.any { pattern -> pattern.matches(codePoints) }
    }

    companion object {
        fun compile(patterns: List<String>): PrivilegeFileNameGlobs =
            PrivilegeFileNameGlobs(patterns.map(String::toGlobTokens))

        fun validate(patterns: List<String>) {
            patterns.forEach(String::toGlobTokens)
        }
    }
}

private fun String.toGlobTokens(): IntArray {
    require('/' !in this) { "Directory name glob must not contain '/': $this" }
    require('\u0000' !in this) { "Directory name glob must not contain NUL" }

    val tokens = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        index += Character.charCount(codePoint)
        when (codePoint) {
            GLOB_ESCAPE -> {
                require(index < length) { "Directory name glob has a trailing escape: $this" }
                val escaped = codePointAt(index)
                require(escaped == GLOB_STAR || escaped == GLOB_ANY || escaped == GLOB_ESCAPE) {
                    "Directory name glob can only escape '*', '?', or '\\': $this"
                }
                tokens += escaped
                index += Character.charCount(escaped)
            }

            GLOB_STAR -> if (tokens.lastOrNull() != TOKEN_STAR) tokens += TOKEN_STAR
            GLOB_ANY -> tokens += TOKEN_ANY
            else -> tokens += codePoint
        }
    }
    return tokens.toIntArray()
}

private fun String.toCodePointArray(): IntArray {
    val result = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        result += codePoint
        index += Character.charCount(codePoint)
    }
    return result.toIntArray()
}

private fun IntArray.matches(name: IntArray): Boolean {
    var previous = BooleanArray(name.size + 1)
    previous[0] = true
    for (token in this) {
        val current = BooleanArray(name.size + 1)
        if (token == TOKEN_STAR) current[0] = previous[0]
        for (nameIndex in 1..name.size) {
            current[nameIndex] = when (token) {
                TOKEN_STAR -> current[nameIndex - 1] || previous[nameIndex]
                TOKEN_ANY -> previous[nameIndex - 1]
                else -> previous[nameIndex - 1] && token == name[nameIndex - 1]
            }
        }
        previous = current
    }
    return previous[name.size]
}

private const val GLOB_STAR: Int = '*'.code
private const val GLOB_ANY: Int = '?'.code
private const val GLOB_ESCAPE: Int = '\\'.code
private const val TOKEN_STAR: Int = -1
private const val TOKEN_ANY: Int = -2
