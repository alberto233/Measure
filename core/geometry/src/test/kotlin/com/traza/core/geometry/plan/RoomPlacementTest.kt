package com.traza.core.geometry.plan

import com.traza.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RoomPlacementTest {

    private fun rectangle(x: Double, y: Double, width: Double, depth: Double) = listOf(
        Vec2(x, y),
        Vec2(x + width, y),
        Vec2(x + width, y + depth),
        Vec2(x, y + depth),
    )

    @Test
    fun `first room keeps its own coordinates`() {
        val offset = RoomPlacement.offsetFor(emptyList(), rectangle(3.0, -2.0, 4.0, 3.0))
        assertEquals(Vec2.ZERO, offset)
    }

    @Test
    fun `a room placed beside another does not touch it`() {
        val existing = rectangle(0.0, 0.0, 4.0, 3.0)
        // Captured in a session whose origin happened to sit on top of the first room.
        val incoming = rectangle(-1.0, -1.0, 5.0, 2.0)

        val offset = RoomPlacement.offsetFor(existing, incoming)
        val placed = incoming.map { it + offset }

        val gap = placed.minOf { it.x } - existing.maxOf { it.x }
        assertEquals(RoomPlacement.GAP_METRES, gap, 1e-9)
        assertTrue(gap > 0.0, "a placed room must never overlap what is already drawn")
    }

    @Test
    fun `placement is a translation, so the room keeps its shape`() {
        val incoming = listOf(Vec2(0.0, 0.0), Vec2(3.2, 0.0), Vec2(3.2, 2.1), Vec2(1.6, 4.0), Vec2(0.0, 2.1))
        val offset = RoomPlacement.offsetFor(rectangle(0.0, 0.0, 4.0, 3.0), incoming)
        val placed = incoming.map { it + offset }

        // Every wall length survives. This is the whole point: the app is entitled to
        // decide where a room sits and never to alter what was measured inside it.
        incoming.indices.forEach { index ->
            val next = (index + 1) % incoming.size
            assertEquals(
                incoming[index].distanceTo(incoming[next]),
                placed[index].distanceTo(placed[next]),
                1e-9,
            )
        }
    }

    @Test
    fun `rooms line up along their near edge`() {
        val existing = rectangle(0.0, 5.0, 4.0, 3.0)
        val incoming = rectangle(40.0, -80.0, 2.0, 2.0)

        val placed = incoming.map { it + RoomPlacement.offsetFor(existing, incoming) }

        assertEquals(existing.minOf { it.y }, placed.minOf { it.y }, 1e-9)
    }

    @Test
    fun `a third room clears both of the first two`() {
        val first = rectangle(0.0, 0.0, 4.0, 3.0)
        val second = rectangle(5.0, 0.0, 3.0, 3.0)
        val third = rectangle(0.0, 0.0, 2.0, 2.0)

        // Everything already on the plan is passed in together, not just the last room —
        // otherwise the third would land on top of the first.
        val placed = third.map { it + RoomPlacement.offsetFor(first + second, third) }

        assertTrue(placed.minOf { it.x } > second.maxOf { it.x })
        assertTrue(placed.minOf { it.x } > first.maxOf { it.x })
    }

    // --- turning ------------------------------------------------------------------------

    @Test
    fun `a room turns on the spot`() {
        val outline = rectangle(10.0, 4.0, 6.0, 2.0)
        val pivot = RoomPlacement.centre(outline)
        val turned = outline.map { RoomPlacement.rotateAbout(it, Math.toRadians(90.0), pivot) }

        // A quarter turn swaps the extents and leaves the centre exactly where it was, so
        // the room appears to spin rather than to wander off across the plan.
        assertEquals(pivot.x, RoomPlacement.centre(turned).x, 1e-9)
        assertEquals(pivot.y, RoomPlacement.centre(turned).y, 1e-9)
        assertEquals(2.0, turned.maxOf { it.x } - turned.minOf { it.x }, 1e-9)
        assertEquals(6.0, turned.maxOf { it.y } - turned.minOf { it.y }, 1e-9)
    }

    @Test
    fun `turning never changes a wall length`() {
        val outline = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(2.0, 4.5), Vec2(0.0, 3.0))
        val pivot = RoomPlacement.centre(outline)
        val turned = outline.map { RoomPlacement.rotateAbout(it, 0.7, pivot) }

        outline.indices.forEach { index ->
            val next = (index + 1) % outline.size
            assertEquals(
                outline[index].distanceTo(outline[next]),
                turned[index].distanceTo(turned[next]),
                1e-9,
            )
        }
    }

    @Test
    fun `squaring lines a crooked room up with the plan`() {
        val crooked = rectangle(0.0, 0.0, 5.0, 3.0)
            .map { it.rotated(Math.toRadians(23.0)) }

        val angle = RoomPlacement.squaringAngle(crooked, Vec2(1.0, 0.0))
        val squared = crooked.map { RoomPlacement.rotateAbout(it, angle, RoomPlacement.centre(crooked)) }

        // Every wall now runs along an axis, to within rounding.
        squared.indices.forEach { index ->
            val edge = squared[(index + 1) % squared.size] - squared[index]
            assertTrue(
                abs(edge.x) < 1e-6 || abs(edge.y) < 1e-6,
                "wall $index is still crooked: $edge",
            )
        }
    }

    @Test
    fun `squaring takes the shortest way round`() {
        // A grid has four-fold symmetry, so a room 80 degrees off is 10 degrees off the
        // other way. Turning it 80 would be a correct answer and the wrong one — the user
        // is left picking the quarter turn, not undoing a needless three-quarter spin.
        val outline = rectangle(0.0, 0.0, 4.0, 2.0).map { it.rotated(Math.toRadians(80.0)) }
        val angle = RoomPlacement.squaringAngle(outline, Vec2(1.0, 0.0))

        assertTrue(abs(Math.toDegrees(angle)) <= 45.0, "turned ${Math.toDegrees(angle)}°")
        assertEquals(10.0, Math.toDegrees(angle), 1e-6)
    }

    @Test
    fun `squaring an already square room does nothing`() {
        val angle = RoomPlacement.squaringAngle(rectangle(0.0, 0.0, 4.0, 3.0), Vec2(1.0, 0.0))
        assertEquals(0.0, angle, 1e-9)
    }

    @Test
    fun `squaring follows the plan's grid rather than the world axes`() {
        // The reference is whatever the rest of the plan is built on, which is the point:
        // a room should agree with its neighbours, not with the coordinate system.
        val reference = Vec2(1.0, 0.0).rotated(Math.toRadians(30.0))
        val outline = rectangle(0.0, 0.0, 4.0, 2.0)

        val angle = RoomPlacement.squaringAngle(outline, reference)
        assertEquals(30.0, Math.toDegrees(angle), 1e-6)
    }

    @Test
    fun `nothing to place is a no-op`() {
        assertEquals(Vec2.ZERO, RoomPlacement.offsetFor(rectangle(0.0, 0.0, 4.0, 3.0), emptyList()))
    }
}
