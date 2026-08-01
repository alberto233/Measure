package com.measure.core.geometry

import com.measure.core.units.Area
import com.measure.core.units.Length
import kotlin.math.abs

/**
 * A closed polygon of floor corners, in order around the room.
 *
 * The closing edge from the last vertex back to the first is implicit. That matters
 * for the constraint solver: because closure is structural rather than a constraint,
 * the solver never has to be told to keep the room closed.
 */
data class Polygon(val vertices: List<Vec2>) {

    init {
        require(vertices.size >= 3) { "a polygon needs at least 3 vertices, got ${vertices.size}" }
    }

    val size: Int get() = vertices.size

    /** Edges in order, including the implicit closing edge. */
    val edges: List<Edge>
        get() = vertices.indices.map { i ->
            Edge(fromIndex = i, toIndex = (i + 1) % size, from = vertices[i], to = vertices[wrap(i + 1)])
        }

    /**
     * Twice the signed area, by the shoelace formula. Positive when the vertices run
     * anticlockwise.
     */
    val signedArea: Double
        get() {
            var sum = 0.0
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[wrap(i + 1)]
                sum += a cross b
            }
            return sum / 2.0
        }

    val area: Area get() = Area(abs(signedArea))

    val isClockwise: Boolean get() = signedArea < 0

    val perimeter: Length
        get() = Length(edges.sumOf { it.length })

    val centroid: Vec2
        get() {
            val doubleArea = signedArea * 2.0
            if (abs(doubleArea) < Vec2.EPSILON) {
                // Degenerate: fall back to the vertex average rather than dividing by zero.
                return vertices.reduce(Vec2::plus) / size.toDouble()
            }
            var cx = 0.0
            var cy = 0.0
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[wrap(i + 1)]
                val w = a cross b
                cx += (a.x + b.x) * w
                cy += (a.y + b.y) * w
            }
            return Vec2(cx / (3.0 * doubleArea), cy / (3.0 * doubleArea))
        }

    /** Vertices reordered so they run anticlockwise, which the exporters assume. */
    fun asAnticlockwise(): Polygon = if (isClockwise) Polygon(vertices.reversed()) else this

    fun translated(offset: Vec2): Polygon = Polygon(vertices.map { it + offset })

    private fun wrap(index: Int) = index % size

    data class Edge(
        val fromIndex: Int,
        val toIndex: Int,
        val from: Vec2,
        val to: Vec2,
    ) {
        val vector: Vec2 get() = to - from
        val length: Double get() = vector.length
        val bearing: Double get() = vector.bearing
    }
}
