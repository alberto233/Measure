package com.measure.core.geometry

import com.measure.core.units.Area
import com.measure.core.units.Length
import com.measure.core.units.Volume
import com.measure.core.units.times

enum class OpeningKind(val label: String, val sitsOnFloor: Boolean) {
    DOOR("Door", true),
    WINDOW("Window", false),

    /** An opening with no door in it — an archway, or a knocked-through wall. */
    PASSAGE("Opening", true),
}

/**
 * A door or window in a wall.
 *
 * Positioned along the wall rather than in room coordinates: a wall is a one-dimensional
 * thing to a person standing in front of it, and "800 mm from the left-hand corner" is
 * both how it gets measured and what survives the corners moving when the room is
 * re-solved. Storing a corner-relative position would mean every solve silently shifted
 * every door.
 */
data class Opening(
    val kind: OpeningKind,
    /** Distance from the wall's starting corner to the opening's near edge, in metres. */
    val offset: Double,
    val width: Double,
    val height: Double,
    /** Height of the sill above the floor. Zero for a door or a passage. */
    val sillHeight: Double = 0.0,
) {
    val area: Area get() = Area(width * height)

    /** Height of the opening's top edge above the floor. */
    val topHeight: Double get() = sillHeight + height

    /** Whether this opening actually fits in the wall it claims to be in. */
    fun fitsIn(wallLength: Double, ceilingHeight: Double): Boolean =
        width > 0.0 &&
            height > 0.0 &&
            offset >= 0.0 &&
            sillHeight >= 0.0 &&
            offset + width <= wallLength + TOLERANCE &&
            topHeight <= ceilingHeight + TOLERANCE

    companion object {
        /** A centimetre of slack, because a door frame measured by hand is not exact. */
        private const val TOLERANCE = 0.01

        /**
         * A typical opening of this kind, centred on the wall.
         *
         * The point is not to guess the user's door but to give them something already
         * roughly right to adjust, since a standard internal door really is about
         * 830 x 2040 mm and typing four numbers from scratch for every doorway is the
         * kind of tedium that stops people recording openings at all.
         *
         * Everything is clamped to fit the wall it is going into, so a narrow wall gets a
         * narrow opening rather than an invalid one.
         */
        fun standard(kind: OpeningKind, wallLength: Double, ceilingHeight: Double): Opening {
            val width = defaultWidth(kind).coerceAtMost(wallLength * MAXIMUM_WALL_SHARE)
            val sill = if (kind.sitsOnFloor) 0.0 else DEFAULT_SILL
            val available = (ceilingHeight - sill - MINIMUM_HEAD).coerceAtLeast(MINIMUM_SIZE)
            val height = defaultHeight(kind).coerceAtMost(available)

            return Opening(
                kind = kind,
                offset = ((wallLength - width) / 2.0).coerceAtLeast(0.0),
                width = width.coerceAtLeast(MINIMUM_SIZE),
                height = height.coerceAtLeast(MINIMUM_SIZE),
                sillHeight = sill,
            )
        }

        private fun defaultWidth(kind: OpeningKind) = when (kind) {
            OpeningKind.DOOR -> 0.83
            OpeningKind.WINDOW -> 1.2
            OpeningKind.PASSAGE -> 0.9
        }

        private fun defaultHeight(kind: OpeningKind) = when (kind) {
            OpeningKind.DOOR -> 2.04
            OpeningKind.WINDOW -> 1.2
            OpeningKind.PASSAGE -> 2.04
        }

        /** A standard sill height for a window. */
        private const val DEFAULT_SILL = 0.9

        /** Leave a little wall above the opening rather than running it into the ceiling. */
        private const val MINIMUM_HEAD = 0.05

        /** No opening may take up more of a wall than this, so it always visibly fits. */
        private const val MAXIMUM_WALL_SHARE = 0.9

        private const val MINIMUM_SIZE = 0.1
    }
}

/**
 * Everything derivable from a solved room plus its height — docs/PRODUCT_PLAN.md §3.
 *
 * These are the numbers people actually buy things with: [netWallArea] is how much paint,
 * [floorArea] is how much flooring, [volume] is how the heating gets sized.
 */
data class RoomSurfaces(
    val floorArea: Area,
    val perimeter: Length,
    val ceilingHeight: Length,
    /** Perimeter times height, before anything is taken out of it. */
    val grossWallArea: Area,
    val openingArea: Area,
    /** What is left to paint. */
    val netWallArea: Area,
    val volume: Volume,
)

object SurfaceCalculator {

    /**
     * Wall area is perimeter times height, minus the openings.
     *
     * That treats walls as zero-thickness and vertical, which is the v1 data model
     * (docs/TECHNICAL_DESIGN.md §3). It is a decorator's estimate rather than a
     * surveyor's: a sloped ceiling, a bay window or a chimney breast will all be wrong,
     * and a room with any of those needs measuring by hand. It is the same estimate every
     * competitor makes, and for the ordinary rectangular room it is right.
     */
    fun compute(
        polygon: Polygon,
        ceilingHeight: Double,
        openings: List<Opening> = emptyList(),
    ): RoomSurfaces {
        val height = ceilingHeight.coerceAtLeast(0.0)
        val perimeter = polygon.perimeter
        val gross = Area(perimeter.metres * height)
        val openingArea = Area(openings.sumOf { it.area.squareMetres })

        return RoomSurfaces(
            floorArea = polygon.area,
            perimeter = perimeter,
            ceilingHeight = Length(height),
            grossWallArea = gross,
            openingArea = openingArea,
            // Clamped: more opening than wall means the openings are wrong, and a
            // negative area on screen would be a puzzle rather than a warning.
            netWallArea = Area((gross.squareMetres - openingArea.squareMetres).coerceAtLeast(0.0)),
            volume = polygon.area * Length(height),
        )
    }
}
