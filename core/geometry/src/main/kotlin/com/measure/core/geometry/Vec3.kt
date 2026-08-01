package com.measure.core.geometry

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A point or vector in the AR world frame, in metres.
 *
 * ARCore's world frame is right-handed with **+Y up**, so the floor plane is spanned by
 * X and Z and "height" is always the Y component. Room geometry stays 2D ([Vec2]) once a
 * capture is complete; this type exists for the capture stage, where points genuinely
 * live in three dimensions — a ceiling height is a Y difference and nothing else.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    val length: Double get() = sqrt(x * x + y * y + z * z)
    val lengthSquared: Double get() = x * x + y * y + z * z

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vec3(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Double) = Vec3(x / scalar, y / scalar, z / scalar)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    infix fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun distanceTo(other: Vec3): Double =
        sqrt(sq(other.x - x) + sq(other.y - y) + sq(other.z - z))

    /** Distance ignoring height. This is what a plan view measures. */
    fun horizontalDistanceTo(other: Vec3): Double = hypot(other.x - x, other.z - z)

    /** Signed height difference, positive when [other] is higher. */
    fun verticalDistanceTo(other: Vec3): Double = other.y - y

    fun normalised(): Vec3 {
        val magnitude = length
        return if (magnitude < EPSILON) ZERO else Vec3(x / magnitude, y / magnitude, z / magnitude)
    }

    /**
     * Drop onto the floor plane for plan work.
     *
     * Z is negated so that a top-down view keeps the same handedness as the 2D geometry
     * code: at identity pose the camera looks down −Z, which becomes +Y on the plan, so
     * "away from where you were standing" reads as "up the page".
     */
    fun toFloorPlane(): Vec2 = Vec2(x, -z)

    private fun sq(value: Double) = value * value

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        const val EPSILON = 1e-12
    }
}
