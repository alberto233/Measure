package com.measure.ar

import com.google.ar.core.Frame
import com.measure.core.geometry.capture.RangeGate
import com.measure.core.geometry.capture.WallFace
import com.measure.core.geometry.capture.WallLineFitter
import com.measure.core.geometry.capture.WallSample

/**
 * Finds a wall where ARCore has not fitted a plane, by looking at a spray of depth points.
 *
 * The reason this exists is the first thing field testing said about wall-face capture:
 * it does not work on a plain painted wall. ARCore's plane detection tracks visual
 * features, and a blank white wall has none — which is exactly the room where the corners
 * are also hidden behind furniture, so the mechanism fails hardest where the feature is
 * needed most.
 *
 * Depth does not need features in the same way, so the wall is still *there* in the depth
 * map even when no plane is fitted to it. Rather than acquiring and unprojecting the depth
 * image — which means camera intrinsics, image formats and a great deal that can be
 * silently wrong — this fires a grid of ordinary hit tests across the middle of the
 * screen. Each one returns whatever ARCore's best estimate at that pixel is, depth
 * included, in world coordinates, using the same code path already proven on the device.
 *
 * A single hit is a poor estimate; two dozen of them fitted to a line are a good one, and
 * the fit reports how many agreed, which is what says whether to believe it at all.
 */
internal class DepthWallFitter {

    private var framesUntilNextFit = 0
    private var cached: AimedWall? = null
    private var cacheAge = 0

    /**
     * The wall in front of the camera, or null.
     *
     * Throttled and cached, because two dozen hit tests every frame is real work and the
     * user is being asked to hold the phone still anyway. The cache is short: a stale wall
     * line that lingers after the phone has turned away is worse than none.
     */
    fun fit(frame: Frame, viewportWidth: Int, viewportHeight: Int): AimedWall? {
        if (viewportWidth == 0 || viewportHeight == 0) return null

        if (framesUntilNextFit > 0) {
            framesUntilNextFit--
            cacheAge++
            return cached.takeIf { cacheAge <= MAXIMUM_CACHE_FRAMES }
        }
        framesUntilNextFit = FRAMES_BETWEEN_FITS
        cacheAge = 0
        cached = compute(frame, viewportWidth, viewportHeight)
        return cached
    }

    fun reset() {
        cached = null
        framesUntilNextFit = 0
    }

    private fun compute(frame: Frame, viewportWidth: Int, viewportHeight: Int): AimedWall? {
        val samples = ArrayList<WallSample>(COLUMNS * ROWS)
        var rangeTotal = 0.0

        for (column in 0 until COLUMNS) {
            for (row in 0 until ROWS) {
                // Spread wide horizontally and narrow vertically: the horizontal spread is
                // the baseline the line fit is built on, while a tall sample would reach
                // the floor at the bottom and the ceiling at the top.
                val x = viewportWidth * (LEFT + (RIGHT - LEFT) * column / (COLUMNS - 1f))
                val y = viewportHeight * (TOP + (BOTTOM - TOP) * row / (ROWS - 1f))

                val hit = try {
                    frame.hitTest(x, y).firstOrNull { it.distance > 0f }
                } catch (error: Throwable) {
                    null
                } ?: continue

                val range = hit.distance.toDouble()
                if (range < RangeGate.MINIMUM_METRES || range > MAXIMUM_RANGE_METRES) continue

                val pose = hit.hitPose
                samples += WallSample(
                    plan = com.measure.core.geometry.Vec3(
                        pose.tx().toDouble(),
                        pose.ty().toDouble(),
                        pose.tz().toDouble(),
                    ).toFloorPlane(),
                    height = pose.ty().toDouble(),
                )
                rangeTotal += range
            }
        }

        if (samples.size < WallLineFitter.MINIMUM_SAMPLES) return null

        val line = WallLineFitter.fit(samples) ?: return null
        val range = rangeTotal / samples.size
        // A synthetic id per fit. Nothing persistent produced this wall, so identity has
        // to come from the geometry instead — which is why "already taken" is decided by
        // comparing wall lines rather than by matching ids.
        val face = WallFace.fromFittedLine(nextId--, line, range) ?: return null

        return AimedWall(face = face, plane = null, range = range)
    }

    private var nextId = -1L

    private companion object {
        /** Wide horizontally for a long baseline, shallow vertically to stay on the wall. */
        const val LEFT = 0.14f
        const val RIGHT = 0.86f
        const val TOP = 0.34f
        const val BOTTOM = 0.62f

        const val COLUMNS = 7
        const val ROWS = 4

        /** Depth beyond this is too coarse to fit a wall worth taking. */
        const val MAXIMUM_RANGE_METRES = 8.0

        /** Roughly six fits a second, which is faster than a hand can re-aim. */
        const val FRAMES_BETWEEN_FITS = 4

        /** Hold a fit only as long as it took to make; past that it is describing the past. */
        const val MAXIMUM_CACHE_FRAMES = 4
    }
}
