package com.traza.core.geometry.plan

import com.traza.core.geometry.Polygon
import com.traza.core.geometry.Segments
import com.traza.core.geometry.Vec2
import kotlin.math.hypot

/** A room reduced to what snapping needs, so `:core:geometry` need not know the database. */
data class SnapRoom(
    val id: Long,
    val label: String,
    val outline: List<Vec2>,
    /** Per-corner uncertainty from the capture, in metres. */
    val cornerSigmas: List<Double>,
    /** Corners the rectilinear solve moved. Their position is partly modelled. */
    val snappedCorners: Set<Int> = emptySet(),
) {
    val polygon: Polygon? get() = if (outline.size >= 3) Polygon(outline) else null

    fun sigmaAt(index: Int): Double =
        cornerSigmas.getOrElse(index) { PlanSnapper.UNKNOWN_CORNER_SIGMA }
}

/** What a point latched onto. Ordered strongest first. */
enum class SnapKind { CORNER, WALL, FREE }

/**
 * An end of a measurement drawn on the plan.
 *
 * Stored as *what it latched onto* rather than as a pair of coordinates, for the same
 * reason [com.traza.core.geometry.Opening] stores an offset along its wall: the corners
 * move. A room is re-solved whenever a wall is locked or a corner dragged, and a
 * measurement pinned to absolute coordinates would silently stop pointing at the thing it
 * was measuring while continuing to display a number. An anchored end moves with the wall
 * it was taken from.
 */
sealed interface PlanAnchor {
    /** A named corner of a room. */
    data class Corner(val roomId: Long, val index: Int) : PlanAnchor

    /** A point [t] of the way along a wall, from its starting corner. */
    data class Wall(val roomId: Long, val index: Int, val t: Double) : PlanAnchor

    /** Nowhere in particular — the user put it there. */
    data class Free(val position: Vec2) : PlanAnchor
}

/**
 * An anchor resolved against the plan as it is now.
 *
 * [sigma] is the honest part. A corner carries the uncertainty its capture produced; a
 * point placed by finger on a wall carries that plus how accurately a finger can be
 * placed; a free point carries only the finger. [isModelled] is the other half: a corner
 * the rectilinear solve moved is partly the model's opinion rather than an observation,
 * and a measurement to it inherits that.
 */
data class ResolvedPoint(
    val anchor: PlanAnchor,
    val position: Vec2,
    val kind: SnapKind,
    /** What to tell the user it latched onto. */
    val description: String,
    val sigma: Double,
    val isModelled: Boolean,
)

/**
 * Turns a tap on the plan into a point that knows what it is attached to.
 *
 * Corners win over walls because every corner lies on two walls, so the other order would
 * make corners unreachable — the same reasoning the editor's selection hit test uses.
 * Everything is in metres and the caller converts its touch radius, so the snap feels the
 * same at every zoom level.
 */
object PlanSnapper {

    /** Used when a room arrives with fewer sigmas than corners. */
    const val UNKNOWN_CORNER_SIGMA = 0.02

    /**
     * How accurately a point can be put somewhere by finger, in metres.
     *
     * Not zero, and this matters. A tap on a plan is a real measurement of nothing: the
     * user is asserting a position by eye, and a derived distance between two such points
     * is no better than that. Reporting it as if it inherited the plan's own accuracy
     * would be the most flattering possible lie.
     */
    const val PLACEMENT_SIGMA = 0.05

    /** Along a wall the same finger applies, but across it the wall's own accuracy holds. */
    const val ALONG_WALL_SIGMA = 0.03

    fun snap(rooms: List<SnapRoom>, point: Vec2, reach: Double): ResolvedPoint {
        rooms.forEach { room ->
            val polygon = room.polygon ?: return@forEach
            Segments.nearestVertex(polygon, point, reach)?.let { index ->
                return resolve(rooms, PlanAnchor.Corner(room.id, index))
                    ?: return@let
            }
        }

        rooms.forEach { room ->
            val polygon = room.polygon ?: return@forEach
            Segments.nearestEdge(polygon, point, reach)?.let { index ->
                val from = room.outline[index]
                val to = room.outline[(index + 1) % room.outline.size]
                val span = to - from
                val length = span.length
                if (length < Vec2.EPSILON) return@let
                val t = (((point - from) dot span) / (length * length)).coerceIn(0.0, 1.0)
                return resolve(rooms, PlanAnchor.Wall(room.id, index, t)) ?: return@let
            }
        }

        return resolve(rooms, PlanAnchor.Free(point))!!
    }

    /**
     * Where an anchor is now, given the current plan.
     *
     * Null when the room it was attached to has gone, which the caller treats as the
     * measurement having gone with it — a distance to a room that no longer exists is not
     * a distance to anything.
     */
    fun resolve(rooms: List<SnapRoom>, anchor: PlanAnchor): ResolvedPoint? = when (anchor) {
        is PlanAnchor.Free -> ResolvedPoint(
            anchor = anchor,
            position = anchor.position,
            kind = SnapKind.FREE,
            description = "free point",
            sigma = PLACEMENT_SIGMA,
            isModelled = false,
        )

        is PlanAnchor.Corner -> {
            val room = rooms.firstOrNull { it.id == anchor.roomId }
            val position = room?.outline?.getOrNull(anchor.index)
            if (room == null || position == null) {
                null
            } else {
                ResolvedPoint(
                    anchor = anchor,
                    position = position,
                    kind = SnapKind.CORNER,
                    description = "${room.label} · corner ${anchor.index + 1}",
                    sigma = room.sigmaAt(anchor.index),
                    isModelled = anchor.index in room.snappedCorners,
                )
            }
        }

        is PlanAnchor.Wall -> {
            val room = rooms.firstOrNull { it.id == anchor.roomId }
            val outline = room?.outline
            if (room == null || outline == null || outline.size < 3 || anchor.index !in outline.indices) {
                null
            } else {
                val next = (anchor.index + 1) % outline.size
                val from = outline[anchor.index]
                val to = outline[next]
                ResolvedPoint(
                    anchor = anchor,
                    position = from + (to - from) * anchor.t.coerceIn(0.0, 1.0),
                    kind = SnapKind.WALL,
                    description = "${room.label} · wall ${anchor.index + 1}",
                    // Across the wall it is as good as the wall; along it, only as good as
                    // the finger that placed it.
                    sigma = hypot(
                        (room.sigmaAt(anchor.index) + room.sigmaAt(next)) / 2.0,
                        ALONG_WALL_SIGMA,
                    ),
                    isModelled = anchor.index in room.snappedCorners || next in room.snappedCorners,
                )
            }
        }
    }
}

/**
 * A distance read off the plan rather than measured in the room.
 *
 * The distinction is the whole point of keeping this as its own type. This number is a
 * *consequence* of the plan — it inherits everything the capture got wrong, plus whatever
 * the rectilinear solve invented, plus wherever the user put their finger. It is genuinely
 * useful for "will the bed fit", and it must never be presented with the authority of a
 * measurement taken against the room (docs/PRODUCT_PLAN.md §5).
 */
data class PlanMeasurement(val from: ResolvedPoint, val to: ResolvedPoint) {

    val length: Double get() = from.position.distanceTo(to.position)

    /**
     * Combined uncertainty of the two ends.
     *
     * Added in quadrature and deliberately *not* reduced for correlation, even though two
     * corners of one room share most of their error and a difference between them would
     * partly cancel. The AR capture path does model that (see `PointUncertainty`) because
     * there the correlation is known — same source, same frame, known separation. Here the
     * ends may be in different rooms, solved separately, and claiming a cancellation that
     * may not exist would under-report exactly when the answer matters.
     */
    val sigma: Double get() = hypot(from.sigma, to.sigma)

    /** True when either end sits on geometry the rectilinear solve moved. */
    val isModelled: Boolean get() = from.isModelled || to.isModelled

    /** Two ends this close together are one tap that got counted twice. */
    val isDegenerate: Boolean get() = length < MINIMUM_LENGTH_METRES

    companion object {
        const val MINIMUM_LENGTH_METRES = 0.05
    }
}
