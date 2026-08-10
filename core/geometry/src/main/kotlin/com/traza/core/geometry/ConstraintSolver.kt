package com.traza.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fits corner positions to all constraints at once, by damped least squares.
 *
 * Snapping and closure adjustment applied one after the other fight each other:
 * snapping breaks closure, and closing breaks the right angles. Solving for every
 * corner simultaneously against every constraint is the way out (docs/ACCURACY.md, M8).
 *
 * Closure itself is never a constraint here. [Polygon] carries an implicit closing
 * edge, so a closed room is closed structurally and the solver cannot open it.
 *
 * The problem is tiny — rooms have well under twenty corners, so under forty unknowns —
 * which is why a dense Levenberg–Marquardt loop with textbook Gaussian elimination is
 * the right amount of machinery. No linear algebra dependency, and it converges in a
 * handful of iterations.
 */
object ConstraintSolver {

    /** Below this, a stated uncertainty is treated as a hard fix rather than a weight. */
    private const val MIN_SIGMA = 1e-4

    fun solve(
        input: SolverInput,
        maxIterations: Int = 60,
        stepTolerance: Double = 1e-10,
    ): SolverResult {
        val n = input.measured.size
        require(n >= 3) { "need at least 3 corners, got $n" }
        require(input.sigmas.size == n) { "expected $n sigmas, got ${input.sigmas.size}" }

        val variables = DoubleArray(n * 2)
        for (i in 0 until n) {
            variables[i * 2] = input.measured[i].x
            variables[i * 2 + 1] = input.measured[i].y
        }

        val initialCost = cost(residuals(variables, input))
        var lambda = 1e-3
        var converged = false
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++

            val r = residuals(variables, input)
            val j = jacobian(variables, input)
            val currentCost = cost(r)

            // Normal equations: (JᵀJ + λ·diag(JᵀJ)) Δ = -Jᵀr
            val jtj = Array(n * 2) { DoubleArray(n * 2) }
            val jtr = DoubleArray(n * 2)
            for (row in r.indices) {
                val jRow = j[row]
                for (a in jRow.indices) {
                    if (jRow[a] == 0.0) continue
                    jtr[a] += jRow[a] * r[row]
                    for (b in jRow.indices) {
                        if (jRow[b] == 0.0) continue
                        jtj[a][b] += jRow[a] * jRow[b]
                    }
                }
            }

            val damped = Array(n * 2) { row -> jtj[row].copyOf() }
            for (d in damped.indices) {
                damped[d][d] += lambda * (if (jtj[d][d] > 0.0) jtj[d][d] else 1.0)
            }
            val rhs = DoubleArray(n * 2) { -jtr[it] }

            val delta = solveLinearSystem(damped, rhs)
            if (delta == null) {
                lambda *= 10.0
                if (lambda > 1e12) break else continue
            }

            val candidate = DoubleArray(variables.size) { variables[it] + delta[it] }
            val candidateCost = cost(residuals(candidate, input))

            if (candidateCost < currentCost) {
                candidate.copyInto(variables)
                lambda = (lambda / 10.0).coerceAtLeast(1e-12)
                if (maxAbs(delta) < stepTolerance) {
                    converged = true
                    break
                }
            } else {
                lambda *= 10.0
                if (lambda > 1e12) {
                    converged = true
                    break
                }
            }
        }

        val vertices = (0 until n).map { Vec2(variables[it * 2], variables[it * 2 + 1]) }
        return SolverResult(
            vertices = vertices,
            iterations = iteration,
            initialCost = initialCost,
            finalCost = cost(residuals(variables, input)),
            converged = converged,
        )
    }

    /**
     * Residual layout: two per corner for the position prior, then one per direction
     * constraint, then one per length constraint.
     */
    private fun residuals(variables: DoubleArray, input: SolverInput): DoubleArray {
        val n = input.measured.size
        val out = DoubleArray(n * 2 + input.directions.size + input.lengths.size)

        for (i in 0 until n) {
            val weight = 1.0 / input.sigmas[i].coerceAtLeast(MIN_SIGMA)
            out[i * 2] = (variables[i * 2] - input.measured[i].x) * weight
            out[i * 2 + 1] = (variables[i * 2 + 1] - input.measured[i].y) * weight
        }

        var row = n * 2
        for (constraint in input.directions) {
            // Perpendicular offset from the target direction. Zero when the wall is
            // parallel to the bearing it was snapped to.
            val normalX = -sin(constraint.bearing)
            val normalY = cos(constraint.bearing)
            val dx = variables[constraint.toIndex * 2] - variables[constraint.fromIndex * 2]
            val dy = variables[constraint.toIndex * 2 + 1] - variables[constraint.fromIndex * 2 + 1]
            out[row++] = (dx * normalX + dy * normalY) * constraint.weight
        }

        for (constraint in input.lengths) {
            val dx = variables[constraint.toIndex * 2] - variables[constraint.fromIndex * 2]
            val dy = variables[constraint.toIndex * 2 + 1] - variables[constraint.fromIndex * 2 + 1]
            val current = kotlin.math.hypot(dx, dy)
            out[row++] = (current - constraint.length) * constraint.weight
        }

        return out
    }

    private fun jacobian(variables: DoubleArray, input: SolverInput): Array<DoubleArray> {
        val n = input.measured.size
        val rows = n * 2 + input.directions.size + input.lengths.size
        val j = Array(rows) { DoubleArray(n * 2) }

        for (i in 0 until n) {
            val weight = 1.0 / input.sigmas[i].coerceAtLeast(MIN_SIGMA)
            j[i * 2][i * 2] = weight
            j[i * 2 + 1][i * 2 + 1] = weight
        }

        var row = n * 2
        for (constraint in input.directions) {
            val normalX = -sin(constraint.bearing) * constraint.weight
            val normalY = cos(constraint.bearing) * constraint.weight
            j[row][constraint.toIndex * 2] = normalX
            j[row][constraint.toIndex * 2 + 1] = normalY
            j[row][constraint.fromIndex * 2] = -normalX
            j[row][constraint.fromIndex * 2 + 1] = -normalY
            row++
        }

        for (constraint in input.lengths) {
            val dx = variables[constraint.toIndex * 2] - variables[constraint.fromIndex * 2]
            val dy = variables[constraint.toIndex * 2 + 1] - variables[constraint.fromIndex * 2 + 1]
            val current = kotlin.math.hypot(dx, dy)
            if (current < Vec2.EPSILON) {
                row++
                continue
            }
            val ux = dx / current * constraint.weight
            val uy = dy / current * constraint.weight
            j[row][constraint.toIndex * 2] = ux
            j[row][constraint.toIndex * 2 + 1] = uy
            j[row][constraint.fromIndex * 2] = -ux
            j[row][constraint.fromIndex * 2 + 1] = -uy
            row++
        }

        return j
    }

    private fun cost(residuals: DoubleArray): Double {
        var sum = 0.0
        for (value in residuals) sum += value * value
        return 0.5 * sum
    }

    private fun maxAbs(values: DoubleArray): Double {
        var max = 0.0
        for (value in values) if (abs(value) > max) max = abs(value)
        return max
    }

    /** Gaussian elimination with partial pivoting. Returns null if the system is singular. */
    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size

        for (col in 0 until n) {
            var pivotRow = col
            for (r in col + 1 until n) {
                if (abs(a[r][col]) > abs(a[pivotRow][col])) pivotRow = r
            }
            if (abs(a[pivotRow][col]) < 1e-14) return null

            if (pivotRow != col) {
                val tempRow = a[pivotRow]; a[pivotRow] = a[col]; a[col] = tempRow
                val tempValue = b[pivotRow]; b[pivotRow] = b[col]; b[col] = tempValue
            }

            val pivot = a[col][col]
            for (r in col + 1 until n) {
                val factor = a[r][col] / pivot
                if (factor == 0.0) continue
                for (c in col until n) a[r][c] -= factor * a[col][c]
                b[r] -= factor * b[col]
            }
        }

        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = b[row]
            for (c in row + 1 until n) sum -= a[row][c] * x[c]
            x[row] = sum / a[row][row]
        }
        return x
    }
}

/**
 * @param bearing the direction this wall should lie along, in radians.
 * @param weight how hard to pull. High for a wall the snapper is confident about.
 */
data class DirectionConstraint(
    val fromIndex: Int,
    val toIndex: Int,
    val bearing: Double,
    val weight: Double = 100.0,
)

/**
 * A wall whose length the user has stated. This is what makes the plan editor
 * powerful: tape measure one wall, type the true figure, and the whole plan tightens
 * around that single piece of certainty.
 */
data class LengthConstraint(
    val fromIndex: Int,
    val toIndex: Int,
    val length: Double,
    val weight: Double = 1000.0,
)

data class SolverInput(
    val measured: List<Vec2>,
    /** Per-corner position uncertainty in metres, from multi-frame sampling. */
    val sigmas: List<Double>,
    val directions: List<DirectionConstraint> = emptyList(),
    val lengths: List<LengthConstraint> = emptyList(),
)

data class SolverResult(
    val vertices: List<Vec2>,
    val iterations: Int,
    val initialCost: Double,
    val finalCost: Double,
    val converged: Boolean,
) {
    val polygon: Polygon get() = Polygon(vertices)
}
