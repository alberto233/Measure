package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec2
import com.measure.core.geometry.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * A wall, seen from above: an infinite line on the floor plan with a known uncertainty.
 *
 * This is the second way to capture a corner (docs/ACCURACY.md M10). Corner mode asks the
 * user to tap where two walls meet, which is fast when you can see the junction and
 * impossible when you cannot — and in an occupied room you usually cannot, because there
 * is a bookcase, a radiator, a bed or a pile of laundry in front of it. Wall-face mode
 * never looks at the corner at all: it takes the two walls, which are large, well lit and
 * mostly unobstructed above waist height, and intersects them.
 *
 * The wall is reduced to a line because that is all a floor plan can hold. Height is
 * irrelevant here, and keeping it would only invite the two representations to disagree.
 *
 * [sigma] is the uncertainty *across* the wall — how far the fitted face might be from
 * the real one along [normal]. Uncertainty along the wall does not exist: the line is
 * infinite, and where the corner falls on it is decided by the other wall.
 */
data class WallFace(
    val id: Long,
    /** Any point known to be on the wall's line, in plan coordinates. */
    val origin: Vec2,
    /** Unit vector along the wall. */
    val direction: Vec2,
    /** Unit vector across the wall. Which way it points carries no meaning here. */
    val normal: Vec2,
    /** How much of the wall has actually been seen, in metres. */
    val extent: Double,
    /** Standard deviation of the wall's position along [normal], in metres. */
    val sigma: Double,
    /** What produced it — a tracked plane, or a line fitted to depth. */
    val source: HitSource = HitSource.WALL_FACE,
) {
    /** Signed distance from the plan's origin to this wall, along [normal]. */
    val offset: Double get() = normal dot origin

    fun distanceTo(point: Vec2): Double = abs((point - origin) dot normal)

    /** Whether two observations are plausibly the same physical wall. */
    fun isSameWallAs(other: WallFace, tolerance: Double = SAME_WALL_TOLERANCE_METRES): Boolean =
        abs(normal dot other.normal) >= PARALLEL_COSINE &&
            distanceTo(other.origin) <= tolerance

    companion object {
        /**
         * How far from vertical a plane may lean and still be treated as a wall.
         *
         * 0.25 is about 14 degrees. Generous, because ARCore's vertical planes on a
         * textured wall are not perfectly plumb, and because a genuinely sloped surface —
         * a staircase soffit, a dormer cheek — is nothing like this close to vertical.
         */
        const val MAXIMUM_TILT = 0.25

        /**
         * A face shorter than this is not a wall.
         *
         * Half a metre rules out a cupboard door, the side of a wardrobe and the back of
         * a sofa, all of which ARCore fits vertical planes to perfectly happily. It is
         * the semantic error from docs/ACCURACY.md §1, and no amount of maths downstream
         * recovers from intersecting the wrong surface.
         */
        const val MINIMUM_EXTENT_METRES = 0.5

        /** Two normals this aligned are the same wall seen twice, not two walls. */
        const val PARALLEL_COSINE = 0.94

        /** And this close together as well. A wall and its opposite are not the same wall. */
        const val SAME_WALL_TOLERANCE_METRES = 0.35

        /**
         * From a vertical plane's world-space centre and normal.
         *
         * Null when the plane is not a wall: too tilted, too small, or — after projecting
         * the normal onto the floor — too close to horizontal to have a direction at all.
         *
         * [range] is how far away the plane was observed from, and feeds the error model
         * exactly as it does for a point hit.
         */
        fun fromVerticalPlane(
            id: Long,
            centre: Vec3,
            normal: Vec3,
            extent: Double,
            range: Double,
        ): WallFace? {
            if (abs(normal.y) > MAXIMUM_TILT) return null
            if (extent < MINIMUM_EXTENT_METRES) return null

            val flattened = normal.toFloorPlane()
            if (flattened.length < Vec2.EPSILON) return null
            val unit = flattened.normalised()

            return WallFace(
                id = id,
                origin = centre.toFloorPlane(),
                direction = unit.perpendicular(),
                normal = unit,
                extent = extent,
                sigma = HitSource.WALL_FACE.sigmaAt(range),
                source = HitSource.WALL_FACE,
            )
        }

        /**
         * From a line fitted to depth samples — see [WallLineFitter].
         *
         * The reported uncertainty is never better than the scatter of the points that
         * produced it. A tight fit to eight points that all happen to be wrong is still
         * wrong, so the error model sets a floor and the observed residual can only push
         * it up.
         */
        fun fromFittedLine(id: Long, line: WallLine, range: Double): WallFace? {
            if (line.extent < MINIMUM_EXTENT_METRES) return null
            if (line.verticalSpread < WallLineFitter.MINIMUM_VERTICAL_SPREAD_METRES) return null

            return WallFace(
                id = id,
                origin = line.origin,
                direction = line.direction,
                normal = line.normal,
                extent = line.extent,
                sigma = maxOf(HitSource.WALL_DEPTH.sigmaAt(range), line.residual),
                source = HitSource.WALL_DEPTH,
            )
        }
    }
}

/**
 * A corner derived from two walls rather than observed directly.
 *
 * [sigma] is not either wall's sigma. Two lines crossing at a shallow angle pin their
 * intersection very poorly along one axis even when both lines are known precisely, and
 * that is a real and large effect: at 20 degrees the corner is three times less certain
 * than the walls that produced it. Reporting the walls' own accuracy here would be a
 * flat lie in exactly the case where the user most needs the warning.
 */
data class WallCorner(
    val position: Vec2,
    val sigma: Double,
    /** Angle between the two wall lines, in radians, in `(0, PI/2]`. A square corner is PI/2. */
    val angle: Double,
) {
    /** Whether the walls met squarely enough that the corner is well determined. */
    val isWellConditioned: Boolean get() = angle >= WallIntersection.COMFORTABLE_ANGLE
}

/** Why two walls could not produce a corner. */
enum class WallPairProblem {
    /** The same wall taken twice. */
    SAME_WALL,

    /** Two different walls, but too near parallel for their crossing to be located. */
    TOO_SHALLOW,
}

object WallIntersection {

    /**
     * Below this the intersection is not worth having.
     *
     * sin(20 degrees) is about 0.34, which inflates the corner's uncertainty by roughly
     * three times. Past that it climbs without limit, and a corner reported with a 15 cm
     * tolerance is not a corner.
     */
    const val MINIMUM_SINE = 0.34

    /** Above this the crossing is square enough that conditioning is not a concern. */
    const val COMFORTABLE_ANGLE = 1.0472 // 60 degrees

    /**
     * Where two walls meet.
     *
     * Solves the two line equations `normal · x = offset` directly. There is no fitting
     * and no iteration: two lines in a plane either cross at exactly one point or they do
     * not cross usefully at all, and the determinant that decides which is the same
     * quantity that says how trustworthy the answer is.
     */
    fun corner(a: WallFace, b: WallFace): Result<WallCorner> {
        val determinant = a.normal cross b.normal
        if (abs(determinant) < MINIMUM_SINE) {
            return Result.failure(
                if (a.isSameWallAs(b)) WallPairFailure(WallPairProblem.SAME_WALL)
                else WallPairFailure(WallPairProblem.TOO_SHALLOW),
            )
        }

        val da = a.offset
        val db = b.offset
        val position = Vec2(
            x = (da * b.normal.y - db * a.normal.y) / determinant,
            y = (a.normal.x * db - b.normal.x * da) / determinant,
        )

        // Both walls' errors act perpendicular to themselves, so they land on the corner
        // divided by the sine of the angle between them.
        val sigma = hypot(a.sigma, b.sigma) / abs(determinant)

        // The angle between the *lines*, so a reflex corner in an L-shaped room reads as
        // the right angle it is. Which side of a wall its normal points is an artefact of
        // how ARCore happened to see it and carries no information we can use.
        val angle = acos(abs(a.normal dot b.normal).coerceAtMost(1.0))

        return Result.success(WallCorner(position, sigma, angle))
    }
}

/** Carries [WallPairProblem] out of a [Result] without inventing an exception hierarchy. */
class WallPairFailure(val problem: WallPairProblem) : Exception(problem.name)

/** Why a wall could not be taken. Each is something the user can act on. */
enum class WallRejection(val message: String) {
    NO_WALL("Point at a wall and hold still until it lights up"),
    TRACKING("Move more slowly — tracking is not good enough"),
    SAME_WALL("That is the wall you just took — turn to the next one"),
}

/** The result of asking for the wall under the reticle. */
sealed interface WallCaptureOutcome {
    data class Accepted(val face: WallFace) : WallCaptureOutcome
    data class Rejected(val reason: WallRejection) : WallCaptureOutcome
}

/**
 * The corners of a room, from the walls in the order they were taken.
 *
 * Each consecutive pair of walls gives one corner, so a user walking round tapping each
 * wall once gets the whole room without ever aiming at a junction — and every wall after
 * the first does double duty, ending one corner and beginning the next. Closing adds the
 * pair that wraps: the last wall against the first.
 */
data class WallChainResult(
    val corners: List<WallCorner>,
    /** Index of the first wall of each pair that could not be resolved, with why. */
    val unresolved: Map<Int, WallPairProblem>,
) {
    val isComplete: Boolean get() = unresolved.isEmpty()

    /** The worst corner's uncertainty, which is the one the user should be told about. */
    val worstSigma: Double? get() = corners.maxOfOrNull { it.sigma }
}

object WallChain {

    /** A closed room needs three walls; an open chain needs two to make one corner. */
    const val MINIMUM_WALLS_FOR_ROOM = 3

    fun corners(walls: List<WallFace>, closed: Boolean): WallChainResult {
        if (walls.size < 2) return WallChainResult(emptyList(), emptyMap())
        if (closed && walls.size < MINIMUM_WALLS_FOR_ROOM) {
            return WallChainResult(emptyList(), emptyMap())
        }

        val pairs = walls.indices.mapNotNull { index ->
            val next = index + 1
            when {
                next < walls.size -> index to walls[next]
                closed -> index to walls[0]
                else -> null
            }
        }

        val corners = ArrayList<WallCorner>(pairs.size)
        val unresolved = LinkedHashMap<Int, WallPairProblem>()

        pairs.forEach { (index, next) ->
            WallIntersection.corner(walls[index], next)
                .onSuccess { corners += it }
                .onFailure { unresolved[index] = (it as WallPairFailure).problem }
        }

        return WallChainResult(corners, unresolved)
    }
}
