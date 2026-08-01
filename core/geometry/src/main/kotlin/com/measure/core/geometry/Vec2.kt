package com.measure.core.geometry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A point or vector on the floor plane, in metres.
 *
 * Room geometry is 2D throughout. Corners are projected onto a single dominant floor
 * plane during capture (docs/ACCURACY.md, M2), so the third dimension carries no
 * information and keeping it would only invite inconsistency.
 */
data class Vec2(val x: Double, val y: Double) {

    val length: Double get() = hypot(x, y)
    val lengthSquared: Double get() = x * x + y * y

    /** Direction in radians, measured anticlockwise from the +x axis. */
    val bearing: Double get() = atan2(y, x)

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Double) = Vec2(x * scalar, y * scalar)
    operator fun div(scalar: Double) = Vec2(x / scalar, y / scalar)
    operator fun unaryMinus() = Vec2(-x, -y)

    infix fun dot(other: Vec2): Double = x * other.x + y * other.y

    /** The z component of the 3D cross product; positive when [other] is anticlockwise. */
    infix fun cross(other: Vec2): Double = x * other.y - y * other.x

    fun distanceTo(other: Vec2): Double = hypot(other.x - x, other.y - y)

    fun normalised(): Vec2 {
        val magnitude = length
        return if (magnitude < EPSILON) ZERO else Vec2(x / magnitude, y / magnitude)
    }

    fun rotated(radians: Double): Vec2 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** Rotated a quarter turn anticlockwise. Cheaper and exact, unlike rotated(PI/2). */
    fun perpendicular(): Vec2 = Vec2(-y, x)

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        const val EPSILON = 1e-12

        /** A unit vector pointing along [bearing] radians. */
        fun fromBearing(bearing: Double) = Vec2(cos(bearing), sin(bearing))
    }
}
