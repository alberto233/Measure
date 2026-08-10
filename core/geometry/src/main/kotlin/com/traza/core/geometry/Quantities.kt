package com.traza.core.geometry

import com.traza.core.units.Area
import com.traza.core.units.Capacity
import com.traza.core.units.Length
import com.traza.core.units.Volume
import kotlin.math.ceil

/**
 * What a plan is worth to somebody buying materials — docs/PRODUCT_PLAN.md §3, use cases 3
 * and 4.
 *
 * "How much flooring" and "how much paint" are two of the six reasons this app exists, and
 * until now the numbers behind them were reachable only by tapping a room and reading its
 * panel — one room at a time, with the addition left to the user. A takeoff does the
 * addition, and does it over the whole plan, which is the form the question is actually
 * asked in: nobody buys flooring for one room of three.
 *
 * Everything here is arithmetic on [RoomSurfaces]. It is kept out of the view model because
 * it is the part worth testing, and out of `:core:data` because it needs no rooms from a
 * database — only their names and their geometry.
 */

/** One room's line in a takeoff. */
data class RoomQuantity(
    val name: String,
    val floorArea: Area,
    val perimeter: Length,
    /**
     * Null when the room has no ceiling height.
     *
     * Not substituted with a typical 2.4 m, for the reason `SavedRoom.surfaces` gives: a
     * guessed paint estimate is indistinguishable on screen from a measured one. A room in
     * this state is counted for its floor and excluded from its walls, and the takeoff
     * names it so the exclusion is visible rather than merely true.
     */
    val surfaces: RoomSurfaces?,
) {
    val hasHeight: Boolean get() = surfaces != null
}

/**
 * The whole plan, added up.
 *
 * Floor quantities cover every room, because a floor area needs no height. Wall quantities
 * cover only the rooms that have one, and [roomsWithoutHeight] says which were left out —
 * a total that silently omits a third of the flat is worse than no total, because it looks
 * exactly like a complete one.
 */
data class Takeoff(val rooms: List<RoomQuantity>) {

    val isEmpty: Boolean get() = rooms.isEmpty()

    val floorArea: Area = Area(rooms.sumOf { it.floorArea.squareMetres })

    val perimeter: Length = Length(rooms.sumOf { it.perimeter.metres })

    private val measured: List<RoomSurfaces> = rooms.mapNotNull { it.surfaces }

    /** Wall area left after doors and windows are taken out, over the rooms that have a height. */
    val netWallArea: Area = Area(measured.sumOf { it.netWallArea.squareMetres })

    /** What the doors and windows removed, so the deduction can be seen rather than trusted. */
    val openingArea: Area = Area(measured.sumOf { it.openingArea.squareMetres })

    val volume: Volume = Volume(measured.sumOf { it.volume.cubicMetres })

    /**
     * Ceiling area, which is floor area.
     *
     * Counted for every room including those without a height: a ceiling's area does not
     * depend on how far away it is. That is why painting the ceilings can be offered even
     * for a plan whose wall total is incomplete.
     */
    val ceilingArea: Area get() = floorArea

    val roomsWithoutHeight: List<String> = rooms.filterNot { it.hasHeight }.map { it.name }

    /** True when at least one room's walls are missing from [netWallArea]. */
    val wallsAreIncomplete: Boolean get() = roomsWithoutHeight.isNotEmpty()
}

/**
 * Flooring: the floor area plus an allowance for what gets cut off and thrown away.
 *
 * The allowance is the user's choice rather than a constant, because it is a fact about how
 * the floor is being laid rather than about the room. A straight lay in a rectangular room
 * wastes very little; a diagonal or herringbone lay in a room with an alcove wastes a great
 * deal, and the difference between the two is more than the difference between two rooms.
 */
object Flooring {

    /** Straight lay, ordinary rooms, and a pattern or an awkward shape. */
    val WASTE_OPTIONS = listOf(5, 10, 15)

    val DEFAULT_WASTE = 10

    fun required(floorArea: Area, wastePercent: Int): Area =
        Area(floorArea.squareMetres * (1.0 + wastePercent.coerceAtLeast(0) / 100.0))
}

/**
 * Paint: area, coats, and how far a litre goes.
 *
 * The coverage rate is the one number here that comes from the tin rather than from the
 * plan, so it is a parameter with a conventional default rather than a constant. Matt
 * emulsion is usually quoted between 10 and 14 m² per litre per coat; the lower end is used
 * because the quoted figure assumes a sealed, even, previously painted wall, and a wall
 * that has been filled or is changing colour drinks more.
 */
object Painting {

    /** Square metres a litre covers, for one coat. */
    const val TYPICAL_COVERAGE = 10.0

    val COAT_OPTIONS = listOf(1, 2, 3)

    val DEFAULT_COATS = 2

    /**
     * Rounded up to the nearest tenth of a litre.
     *
     * The failure modes are not symmetric. Half a litre too much sits in a cupboard; half a
     * litre too short is a second trip and a batch that does not quite match the first, on a
     * wall that is now half painted. Rounding up is the cheap side of a decision the user
     * would make the same way, and it happens here rather than in the formatter so that
     * displaying a number never changes it.
     */
    fun required(
        area: Area,
        coats: Int,
        coveragePerLitre: Double = TYPICAL_COVERAGE,
    ): Capacity {
        if (coveragePerLitre <= 0.0 || coats <= 0 || area.squareMetres <= 0.0) return Capacity.ZERO
        val litres = area.squareMetres * coats / coveragePerLitre
        return Capacity(ceil(litres * 10.0) / 10.0)
    }
}
