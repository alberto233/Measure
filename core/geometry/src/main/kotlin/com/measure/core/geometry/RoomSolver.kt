package com.measure.core.geometry

import com.measure.core.units.Area
import com.measure.core.units.Length

/** A corner as it came off the AR capture, with the uncertainty of that observation. */
data class CapturedCorner(
    val position: Vec2,
    /** Dispersion of the multi-frame sample that produced this corner, in metres. */
    val sigma: Double = DEFAULT_SIGMA,
) {
    companion object {
        /** Roughly what a steady hand achieves at 2 m in good light. */
        const val DEFAULT_SIGMA = 0.02
    }
}

data class RoomCapture(
    val corners: List<CapturedCorner>,
    /**
     * The second reading of the starting corner, taken after walking the room. Null if
     * the user did not close the loop, in which case there is no measured drift to
     * distribute and the plan is only as good as the raw observations.
     */
    val closingObservation: Vec2? = null,
)

data class RoomSolverOptions(
    val snapEnabled: Boolean = true,
    val snapTolerance: Double = AngleSnapper.DEFAULT_TOLERANCE,
    val allowDiagonals: Boolean = false,
    val directionWeight: Double = 100.0,
)

data class RoomSolution(
    val polygon: Polygon,
    val closure: ClosureResult,
    val snap: SnapResult?,
    val solver: SolverResult,
) {
    val area: Area get() = polygon.area
    val perimeter: Length get() = polygon.perimeter

    /** True when the capture closed tightly enough to trust without asking the user. */
    val isReliable: Boolean get() = closure.isAcceptable && solver.converged
}

/**
 * The full correction pipeline, in the order the maths requires:
 * distribute drift, infer the rectilinear frame, then solve everything together.
 *
 * Running these as three sequential passes would be wrong — the solve is what
 * reconciles closure against right angles, rather than letting the last one applied win.
 */
object RoomSolver {

    fun solve(
        capture: RoomCapture,
        options: RoomSolverOptions = RoomSolverOptions(),
        lockedLengths: List<LengthConstraint> = emptyList(),
    ): RoomSolution {
        require(capture.corners.size >= 3) {
            "a room needs at least 3 corners, got ${capture.corners.size}"
        }

        val closure = LoopClosure.adjust(
            measured = capture.corners.map { it.position },
            closingObservation = capture.closingObservation,
        )

        val adjusted = Polygon(closure.adjusted)

        val snap = if (options.snapEnabled) {
            AngleSnapper(
                tolerance = options.snapTolerance,
                allowDiagonals = options.allowDiagonals,
            ).snap(adjusted)
        } else {
            null
        }

        val directions = snap?.snaps
            ?.filter { it.isSnapped }
            ?.map { edgeSnap ->
                DirectionConstraint(
                    fromIndex = edgeSnap.edgeIndex,
                    toIndex = (edgeSnap.edgeIndex + 1) % adjusted.size,
                    bearing = edgeSnap.snappedBearing,
                    weight = options.directionWeight,
                )
            }
            .orEmpty()

        val solverResult = ConstraintSolver.solve(
            SolverInput(
                measured = closure.adjusted,
                sigmas = capture.corners.map { it.sigma },
                directions = directions,
                lengths = lockedLengths,
            )
        )

        return RoomSolution(
            polygon = solverResult.polygon,
            closure = closure,
            snap = snap,
            solver = solverResult,
        )
    }
}
