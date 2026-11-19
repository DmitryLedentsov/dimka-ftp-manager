package com.dimka.ftpmanager.data

/** Gitignore-like glob subset for initial import. Literal dirs, *.ext, and ** / patterns. */
class ImportFilter(content: String) {
    private val patterns = content.lineSequence().map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith('!') }
        .map { raw ->
            val rule = raw.removePrefix("/")
            val directory = rule.endsWith('/')
            val pattern = rule.removeSuffix("/")
            val regexp = buildString {
                append('^')
                for (c in pattern) {
                    when (c) {
                        '*' -> append("[^/]*")
                        '?' -> append("[^/]")
                        else -> append(Regex.escape(c.toString()))
                    }
                }
                append('$')
            }.toRegex(RegexOption.IGNORE_CASE)
            Triple(pattern, directory, regexp)
        }.toList()

    fun excluded(relative: String, directory: Boolean): Boolean {
        val path = relative.trimStart('/').trimEnd('/')
        val segments = path.split('/')
        return patterns.any { (pattern, dirRule, regex) ->
            if (dirRule) {
                segments.dropLast(if (directory) 0 else 1).any { regex.matches(it) } ||
                    (directory && regex.matches(path))
            } else {
                (if (pattern.contains('/')) regex.matches(path) else segments.any { regex.matches(it) })
            }
        }
    }
}
