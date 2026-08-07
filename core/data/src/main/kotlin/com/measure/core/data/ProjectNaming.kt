package com.measure.core.data

/**
 * Default names for things the user has not named.
 *
 * Numbered rather than dated. A date stamp is unambiguous but useless for finding
 * anything — "14 March, 11:42" tells you nothing about which room it was — and it forces
 * a locale-dependent format into the data layer, where locale has no business being.
 * "Plan 3" is short, sorts sensibly, and is easy to replace with a real name.
 *
 * Pure, so the numbering rules are testable without a database.
 */
object ProjectNaming {

    const val PROJECT_PREFIX = "Plan"
    const val ROOM_PREFIX = "Room"

    fun nextProjectName(existing: List<String>): String = next(PROJECT_PREFIX, existing)

    fun nextRoomName(existing: List<String>): String = next(ROOM_PREFIX, existing)

    /**
     * The lowest positive integer not already used with this prefix.
     *
     * Filling gaps rather than always incrementing: someone who deletes "Plan 2" and
     * captures again expects "Plan 2" back, not "Plan 4" with a hole where 2 was.
     */
    private fun next(prefix: String, existing: List<String>): String {
        val used = existing.mapNotNull { numberOf(prefix, it) }.toSet()
        var candidate = 1
        while (candidate in used) candidate++
        return "$prefix $candidate"
    }

    /** The number in "Plan 7", or null if the name is not of that form. */
    private fun numberOf(prefix: String, name: String): Int? {
        val trimmed = name.trim()
        if (!trimmed.startsWith("$prefix ")) return null
        return trimmed.removePrefix("$prefix ").trim().toIntOrNull()?.takeIf { it > 0 }
    }
}
