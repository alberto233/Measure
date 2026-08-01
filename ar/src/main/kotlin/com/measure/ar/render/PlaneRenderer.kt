package com.measure.ar.render

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

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

    fun draw(planes: Collection<Plane>, viewProjection: FloatArray) {
        val visible = planes.filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
        }
        if (visible.isEmpty()) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Translucent geometry drawn in arbitrary order must not occlude what follows.
        GLES20.glDepthMask(false)
        GLES20.glEnableVertexAttribArray(positionAttribute)

        visible.forEach { plane -> drawPlane(plane, viewProjection) }

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkErrors("planes")
    }

    private fun drawPlane(plane: Plane, viewProjection: FloatArray) {
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

        val colour = colourFor(plane)
        GLES20.glUniform4f(colourUniform, colour.r, colour.g, colour.b, FILL_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)

        GLES20.glUniform4f(colourUniform, colour.r, colour.g, colour.b, OUTLINE_ALPHA)
        GLES20.glLineWidth(OUTLINE_WIDTH_PX)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)
    }

    private fun colourFor(plane: Plane): GlColour = when (plane.type) {
        Plane.Type.HORIZONTAL_UPWARD_FACING -> FLOOR
        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> CEILING
        else -> WALL
    }

    private companion object {
        /** A modest room is a handful of planes of a few dozen vertices each. */
        const val INITIAL_VERTEX_CAPACITY = 256

        const val FILL_ALPHA = 0.18f
        const val OUTLINE_ALPHA = 0.65f
        const val OUTLINE_WIDTH_PX = 3f

        val FLOOR = GlColour.of(0x2ED3B7)
        val WALL = GlColour.of(0xFFB020)
        val CEILING = GlColour.of(0xA78BFA)

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
