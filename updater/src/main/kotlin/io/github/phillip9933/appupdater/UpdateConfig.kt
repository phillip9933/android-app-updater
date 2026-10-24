package io.github.phillip9933.appupdater

import java.math.BigInteger

/** App-specific identity for a public GitHub Releases feed. */
data class UpdateConfig(
    val owner: String,
    val repository: String,
    val tagPrefix: String,
    val assetPrefix: String,
    val legacyVersions: Set<String> = emptySet(),
    val legacyAssetPrefix: String = assetPrefix,
    val stateName: String = "android_updates",
) {
    init {
        require(GITHUB_NAME.matches(owner)) { "owner must be a simple GitHub name" }
        require(REPOSITORY_NAME.matches(repository) && repository != "." && repository != "..") {
            "repository must be a simple GitHub name"
        }
        require(tagPrefix.isNotEmpty() && SAFE_LITERAL.matches(tagPrefix)) {
            "tagPrefix must be a safe literal prefix"
        }
        require(assetPrefix.isNotEmpty() && SAFE_LITERAL.matches(assetPrefix)) {
            "assetPrefix must be a safe literal prefix"
        }
        require(legacyAssetPrefix.isNotEmpty() && SAFE_LITERAL.matches(legacyAssetPrefix)) {
            "legacyAssetPrefix must be a safe literal prefix"
        }
        require(STATE_NAME.matches(stateName)) { "stateName must be a safe name" }
        require(legacyVersions.all { parseSemVer(it) != null }) {
            "legacyVersions must contain valid semantic versions"
        }
    }

    /** Returns a version only for this app's configured tags or explicitly allowed history. */
    fun versionFromTag(tag: String): String? {
        val version =
            when {
                tag.startsWith(tagPrefix) -> tag.removePrefix(tagPrefix)
                else -> tag.removePrefix("v").takeIf { tag in setOf(it, "v$it") && it in legacyVersions }
            } ?: return null
        return version.takeIf { parseSemVer(it) != null && (tag.startsWith(tagPrefix) || it in legacyVersions) }
    }

    fun assetName(tag: String): String? {
        val version = versionFromTag(tag) ?: return null
        val prefix = if (tag.startsWith(tagPrefix)) assetPrefix else legacyAssetPrefix
        return "$prefix$version.apk"
    }

    internal companion object {
        private val GITHUB_NAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val REPOSITORY_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
        private val SAFE_LITERAL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val STATE_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
        private val VERSION =
            Regex(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?",
            )

        // Early exits keep malformed semantic versions out of comparison code.
        @Suppress("ReturnCount")
        internal fun parseSemVer(text: String): SemanticVersion? {
            if (text.length > 128) return null
            val match = VERSION.matchEntire(text) ?: return null
            val numbers = match.groupValues.slice(1..3)
            if (numbers.any { it.length > 18 }) return null
            val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.')
            if (prerelease?.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') } == true) {
                return null
            }
            return SemanticVersion(
                numbers[0].toBigInteger(),
                numbers[1].toBigInteger(),
                numbers[2].toBigInteger(),
                prerelease,
            )
        }

        // Ordered exits preserve SemVer precedence rules clearly.
        @Suppress("ReturnCount")
        internal fun compare(
            a: SemanticVersion,
            b: SemanticVersion,
        ): Int {
            compareValues(a.major, b.major).takeIf { it != 0 }?.let { return it }
            compareValues(a.minor, b.minor).takeIf { it != 0 }?.let { return it }
            compareValues(a.patch, b.patch).takeIf { it != 0 }?.let { return it }
            if (a.prerelease == null) return if (b.prerelease == null) 0 else 1
            if (b.prerelease == null) return -1
            for (index in 0 until minOf(a.prerelease.size, b.prerelease.size)) {
                val left = a.prerelease[index]
                val right = b.prerelease[index]
                val leftNumeric = left.toBigIntegerOrNull()
                val rightNumeric = right.toBigIntegerOrNull()
                val result =
                    when {
                        leftNumeric != null && rightNumeric != null -> compareValues(leftNumeric, rightNumeric)
                        leftNumeric != null -> -1
                        rightNumeric != null -> 1
                        else -> left.compareTo(right)
                    }
                if (result != 0) return result
            }
            return compareValues(a.prerelease.size, b.prerelease.size)
        }
    }
}

internal data class SemanticVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>?,
)
