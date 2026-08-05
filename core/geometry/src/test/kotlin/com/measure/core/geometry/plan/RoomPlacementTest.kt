package com.measure.core.geometry.plan

import com.measure.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    @Test
    fun `nothing to place is a no-op`() {
        assertEquals(Vec2.ZERO, RoomPlacement.offsetFor(rectangle(0.0, 0.0, 4.0, 3.0), emptyList()))
    }
}
