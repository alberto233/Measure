package com.measure.core.geometry

/**
 * Point-to-segment geometry, for deciding what a finger landed on.
 *
 * Pure and here rather than in the editor because "which wall did they tap" is a
 * geometry question with an exact answer, and the alternative — approximating it against
 * bounding boxes in the UI layer — gets subtly wrong exactly where walls meet, which is
 * where taps cluster.
 */
object Segments {

    /**
     * The point on segment `a`–`b` closest to [point].
     *
     * Clamped to the segment, so a tap beyond a wall's end returns that end rather than a
     * point on the infinite line. A degenerate segment returns its own position.
     */
    fun nearestPointOn(a: Vec2, b: Vec2, point: Vec2): Vec2 {
        val along = b - a
        val lengthSquared = along.lengthSquared
        if (lengthSquared < Vec2.EPSILON) return a

        val t = (((point - a) dot along) / lengthSquared).coerceIn(0.0, 1.0)
        return a + along * t
    }

    fun distanceToSegment(a: Vec2, b: Vec2, point: Vec2): Double =
        nearestPointOn(a, b, point).distanceTo(point)

    /**
     * Index of the polygon edge nearest [point], or null if none is within [maxDistance].
     *
     * Edge *i* runs from vertex *i* to vertex *i+1*, wrapping — the same convention the
     * stored walls use, so an index means the same thing everywhere.
     */
    fun nearestEdge(polygon: Polygon, point: Vec2, maxDistance: Double): Int? {
        var best = -1
        var bestDistance = maxDistance
        polygon.vertices.indices.forEach { index ->
            val from = polygon.vertices[index]
            val to = polygon.vertices[(index + 1) % polygon.size]
            val distance = distanceToSegment(from, to, point)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best.takeIf { it >= 0 }
    }

    /** Index of the vertex nearest [point], or null if none is within [maxDistance]. */
    fun nearestVertex(polygon: Polygon, point: Vec2, maxDistance: Double): Int? =
        polygon.vertices
            .withIndex()
            .filter { it.value.distanceTo(point) <= maxDistance }
            .minByOrNull { it.value.distanceTo(point) }
            ?.index

    /**
     * Whether [point] lies inside [polygon], by ray casting.
     *
     * Used to work out which room was tapped when a plan holds several. The half-open
     * comparison on the y test is what stops a ray that passes exactly through a vertex
     * from counting it twice.
     */
    fun contains(polygon: Polygon, point: Vec2): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.vertices.indices) {
            val a = polygon.vertices[i]
            val b = polygon.vertices[j]
            if ((a.y > point.y) != (b.y > point.y)) {
                val crossingX = a.x + (point.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (point.x < crossingX) inside = !inside
            }
            j = i
        }
        return inside
    }
}
