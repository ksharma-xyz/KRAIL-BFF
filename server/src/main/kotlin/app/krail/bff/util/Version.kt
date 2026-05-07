package app.krail.bff.util

/**
 * Lightweight semver-ish parser. Accepts "MAJOR.MINOR.PATCH" with optional "-suffix" / "+meta"
 * (suffix and meta are ignored for ordering).
 *
 * Returns null for inputs that don't have three numeric components.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(input: String): Version? {
            val core = input.substringBefore('-').substringBefore('+').trim()
            val parts = core.split('.')
            if (parts.size != 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null
            if (major < 0 || minor < 0 || patch < 0) return null
            return Version(major, minor, patch)
        }
    }
}
