package com.measure.ar.render

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlin.math.abs

/**
 * Shades detected planes so the user can see what the app has understood.
 *
 * This is the single most reassuring thing on the screen. Until a surface lights up, the
 * user has no idea whether the app is working, and a measuring app that looks broken for
 * the first ten seconds gets uninstalled during those ten seconds.
 *
 * Planes are drawn as a translucent fill with a brighter outline, coloured by orientation
 * so a floor and a wall are distinguishable at a glance. ARCore's polygons are convex
 * hulls, which is what makes a triangle fan a correct fill and not merely a cheap one.
 */
internal class PlaneRenderer {

    private var program = 0
    private var positionAttribute = 0
    private var mvpUniform = 0
    private var colourUniform = 0

    private var vertices = GlUtil.floatBuffer(INITIAL_VERTEX_CAPACITY)

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_PlaneXZ")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        colourUniform = GLES20.glGetUniformLocation(program, "u_Colour")
    }

    /**
     * @param emphasisHeight the floor's height. Planes at that level are drawn solidly
     *   because they are the ones being measured against; everything else is faded right
     *   back, because it is context rather than a target.
     */
    fun draw(
        planes: Collection<Plane>,
        viewProjection: FloatArray,
        emphasisHeight: Double? = null,
        /** Walls already taken for the room being captured. */
        takenWallIds: Set<Long> = emptySet(),
        /** The wall under the reticle, which a tap would take. */
        aimedWallId: Long? = null,
    ) {
        // Every tracked plane used to be drawn with a bright outline. In a cluttered room
        // ARCore finds dozens, and the result was a screen full of crossing teal lines
        // that hid the camera image and made the app look broken. Restraint here is not
        // cosmetic: the user has to be able to see the room to aim at it.
        val candidates = planes
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        val visible = candidates
            .filter { it.extentX * it.extentZ >= MINIMUM_DRAWN_AREA }
            .sortedByDescending { it.extentX * it.extentZ }
            .take(MAXIMUM_DRAWN)
            // A taken or aimed wall is drawn whatever its size and whatever else is
            // larger. During wall-face capture these are the only planes that mean
            // anything, and one dropping out of the largest-eight list would read as the
            // app having forgotten it.
            .plus(candidates.filter { it.hashCode().toLong() in takenWallIds || it.hashCode().toLong() == aimedWallId })
            .distinct()
        if (visible.isEmpty()) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Translucent geometry drawn in arbitrary order must not occlude what follows.
        GLES20.glDepthMask(false)
        GLES20.glEnableVertexAttribArray(positionAttribute)

        visible.forEach { plane ->
            drawPlane(plane, viewProjection, emphasisHeight, takenWallIds, aimedWallId)
        }

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkErrors("planes")
    }

    private fun drawPlane(
        plane: Plane,
        viewProjection: FloatArray,
        emphasisHeight: Double?,
        takenWallIds: Set<Long>,
        aimedWallId: Long?,
    ) {
        val polygon = plane.polygon ?: return
        polygon.rewind()
        val vertexCount = polygon.limit() / 2
        if (vertexCount < 3) return

        if (vertices.capacity() < vertexCount * 2) {
            vertices = GlUtil.floatBuffer(vertexCount * 2)
        }
        vertices.clear()
        vertices.put(polygon)
        vertices.flip()

        plane.centerPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices)

        val id = plane.hashCode().toLong()
        val aimed = id == aimedWallId
        val taken = id in takenWallIds
        val isFloor = emphasisHeight != null &&
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
            abs(plane.centerPose.ty() - emphasisHeight) <= EMPHASIS_TOLERANCE

        val colour = when {
            taken -> TAKEN
            aimed -> AIMED
            else -> colourFor(plane)
        }
        val emphasised = isFloor || taken || aimed

        GLES20.glUniform4f(
            colourUniform,
            colour.r,
            colour.g,
            colour.b,
            when {
                taken || aimed -> FILL_ALPHA_WALL
                isFloor -> FILL_ALPHA
                else -> FILL_ALPHA_MUTED
            },
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)

        // Only the surface being measured against gets an outline. Outlining everything
        // is what produced the crossing-lines mess.
        if (emphasised) {
            GLES20.glUniform4f(colourUniform, colour.r, colour.g, colour.b, OUTLINE_ALPHA)
            GLES20.glLineWidth(if (taken || aimed) WALL_OUTLINE_WIDTH_PX else OUTLINE_WIDTH_PX)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)
        }
    }

    private fun colourFor(plane: Plane): GlColour = when (plane.type) {
        Plane.Type.HORIZONTAL_UPWARD_FACING -> FLOOR
        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> CEILING
        else -> WALL
    }

    private companion object {
        /** A modest room is a handful of planes of a few dozen vertices each. */
        const val INITIAL_VERTEX_CAPACITY = 256

        const val FILL_ALPHA = 0.16f

        /** Context planes: visible enough to show the app is working, faint enough to see past. */
        const val FILL_ALPHA_MUTED = 0.06f

        const val OUTLINE_ALPHA = 0.55f
        const val OUTLINE_WIDTH_PX = 3f

        /** Planes at the floor's level within this are drawn as the floor. */
        const val EMPHASIS_TOLERANCE = 0.12

        /** Smaller than this and a plane is a fragment that only adds noise. */
        const val MINIMUM_DRAWN_AREA = 0.25

        /** A cluttered room yields dozens; the largest few are all that inform anything. */
        const val MAXIMUM_DRAWN = 8

        /** A wall being pointed at, and one already taken. Both shout, differently. */
        const val FILL_ALPHA_WALL = 0.26f
        const val WALL_OUTLINE_WIDTH_PX = 5f

        val FLOOR = GlColour.of(0x2ED3B7)
        val WALL = GlColour.of(0xFFB020)
        val CEILING = GlColour.of(0xA78BFA)

        /** Amber, matching the reticle: this is what a tap would act on. */
        val AIMED = GlColour.of(0xFFD166)

        /** Teal, matching everything else the app treats as committed. */
        val TAKEN = GlColour.of(0x2ED3B7)

        const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec2 a_PlaneXZ;
            void main() {
                // ARCore gives plane polygons in the plane's own frame, where the plane
                // is the y = 0 surface and the vertices are (x, z).
                gl_Position = u_ModelViewProjection * vec4(a_PlaneXZ.x, 0.0, a_PlaneXZ.y, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Colour;
            void main() {
                gl_FragColor = u_Colour;
            }
        """
    }
}
