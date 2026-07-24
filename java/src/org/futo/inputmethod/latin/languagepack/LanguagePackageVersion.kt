package org.futo.inputmethod.latin.languagepack

/**
 * Small SemVer 2.0 implementation used by the package registry.
 *
 * Build metadata is retained for display and identity, but ignored during precedence comparisons.
 */
data class LanguagePackageSemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
    val buildMetadata: List<String> = emptyList(),
) : Comparable<LanguagePackageSemanticVersion> {
    override fun compareTo(other: LanguagePackageSemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1

        val commonLength = minOf(preRelease.size, other.preRelease.size)
        for (index in 0 until commonLength) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()

            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }

        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = buildString {
        append(major)
        append('.')
        append(minor)
        append('.')
        append(patch)
        if (preRelease.isNotEmpty()) {
            append('-')
            append(preRelease.joinToString("."))
        }
        if (buildMetadata.isNotEmpty()) {
            append('+')
            append(buildMetadata.joinToString("."))
        }
    }

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
        )

        fun parse(value: String): LanguagePackageSemanticVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val preRelease = match.groupValues[4]
                .takeIf { it.isNotEmpty() }
                ?.split('.')
                .orEmpty()
            if (preRelease.any { it.length > 1 && it.first() == '0' && it.all(Char::isDigit) }) {
                return null
            }

            return LanguagePackageSemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                preRelease = preRelease,
                buildMetadata = match.groupValues[5]
                    .takeIf { it.isNotEmpty() }
                    ?.split('.')
                    .orEmpty(),
            )
        }
    }
}

class LanguagePackageVersionRange private constructor(
    private val predicates: List<(LanguagePackageSemanticVersion) -> Boolean>,
) {
    fun contains(version: LanguagePackageSemanticVersion): Boolean = predicates.all { it(version) }

    companion object {
        fun parse(value: String): LanguagePackageVersionRange? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed == "*") return LanguagePackageVersionRange(emptyList())
            if (trimmed.contains("||") || trimmed.contains(',')) return null

            val predicates = mutableListOf<(LanguagePackageSemanticVersion) -> Boolean>()
            val tokens = trimmed.split(Regex("\\s+")).filter(String::isNotBlank)
            for (token in tokens) {
                when {
                    token.startsWith('^') -> {
                        val lower = LanguagePackageSemanticVersion.parse(token.drop(1)) ?: return null
                        val upper = when {
                            lower.major > 0 && lower.major < Int.MAX_VALUE -> {
                                LanguagePackageSemanticVersion(lower.major + 1, 0, 0)
                            }
                            lower.major == 0 && lower.minor > 0 && lower.minor < Int.MAX_VALUE -> {
                                LanguagePackageSemanticVersion(0, lower.minor + 1, 0)
                            }
                            lower.major == 0 && lower.minor == 0 && lower.patch < Int.MAX_VALUE -> {
                                LanguagePackageSemanticVersion(0, 0, lower.patch + 1)
                            }
                            else -> return null
                        }
                        predicates += { candidate: LanguagePackageSemanticVersion -> candidate >= lower }
                        predicates += { candidate: LanguagePackageSemanticVersion -> candidate < upper }
                    }

                    token.startsWith('~') -> {
                        val lower = LanguagePackageSemanticVersion.parse(token.drop(1)) ?: return null
                        if (lower.minor == Int.MAX_VALUE) return null
                        val upper = LanguagePackageSemanticVersion(lower.major, lower.minor + 1, 0)
                        predicates += { candidate: LanguagePackageSemanticVersion -> candidate >= lower }
                        predicates += { candidate: LanguagePackageSemanticVersion -> candidate < upper }
                    }

                    else -> {
                        val operator = when {
                            token.startsWith(">=") -> ">="
                            token.startsWith("<=") -> "<="
                            token.startsWith("==") -> "=="
                            token.startsWith('>') -> ">"
                            token.startsWith('<') -> "<"
                            token.startsWith('=') -> "="
                            else -> "="
                        }
                        val rawVersion = if (operator == "=" && !token.startsWith('=')) {
                            token
                        } else {
                            token.drop(operator.length)
                        }
                        val version = LanguagePackageSemanticVersion.parse(rawVersion) ?: return null
                        val predicate: (LanguagePackageSemanticVersion) -> Boolean = when (operator) {
                            ">=" -> { candidate -> candidate >= version }
                            "<=" -> { candidate -> candidate <= version }
                            ">" -> { candidate -> candidate > version }
                            "<" -> { candidate -> candidate < version }
                            "=", "==" -> { candidate -> candidate.compareTo(version) == 0 }
                            else -> return null
                        }
                        predicates += predicate
                    }
                }
            }

            return LanguagePackageVersionRange(predicates)
        }
    }
}
