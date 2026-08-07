package com.measure.ar.render

import android.opengl.GLES20
import com.measure.core.geometry.Vec3

/**
 * Draws measurement lines as camera-facing ribbons.
 *
 * `GL_LINES` would be the obvious approach and is the wrong one: `glLineWidth` above 1.0
 * is optional in OpenGL ES and most mobile drivers silently clamp it, giving a hairline
 * that disappears against a busy camera image. So each segment is built as two triangles
 * whose width is computed on the CPU from the camera position, which also gets us a line
 * that is genuinely always edge-on to the viewer.
 *
 * The width is scaled by each endpoint's distance from the camera, cancelling perspective
 * so the ribbon holds a near-constant thickness on screen from half a metre to ten.
 */
internal class RibbonRenderer {

    private var program = 0
    private var positionAttribute = 0
    private var mvpUniform = 0
    private var colourUniform = 0

    private var vertices = GlUtil.floatBuffer(INITIAL_SEGMENTS * FLOATS_PER_SEGMENT)

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ViewProjection")
        colourUniform = GLES20.glGetUniformLocation(program, "u_Colour")
    }

    /**
     * @param cameraPosition where the viewer is, used to orient each ribbon edge-on.
     * @param widthFactor world metres of half-width per metre of range; see
     *   [widthFactorFor], which derives it from the projection matrix so a request in
     *   pixels comes out the same on any screen.
     */
    fun draw(
        segments: List<Pair<Vec3, Vec3>>,
        viewProjection: FloatArray,
        cameraPosition: Vec3,
        colour: GlColour,
        widthFactor: Float,
    ) {
        if (segments.isEmpty()) return

        val needed = segments.size * FLOATS_PER_SEGMENT
        if (vertices.capacity() < needed) vertices = GlUtil.floatBuffer(needed)
        vertices.clear()

        var written = 0
        segments.forEach { (from, to) ->
            if (appendRibbon(from, to, cameraPosition, widthFactor)) written++
        }
        if (written == 0) return
        vertices.flip()

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // A measurement usually crosses the furniture it is measuring around, so the
        // line stays visible through it rather than being occluded by the room.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjection, 0)
        GLES20.glUniform4f(colourUniform, colour.r, colour.g, colour.b, colour.a)
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, written * VERTICES_PER_SEGMENT)
        GLES20.glDisableVertexAttribArray(positionAttribute)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkErrors("ribbons")
    }

    private fun appendRibbon(
        from: Vec3,
        to: Vec3,
        cameraPosition: Vec3,
        widthFactor: Float,
    ): Boolean {
        val along = to - from
        if (along.lengthSquared < MIN_LENGTH_SQUARED) return false

        // Perpendicular to both the segment and the line of sight: the direction in which
        // the ribbon has to spread to look like a line of constant thickness.
        val sightFrom = from - cameraPosition
        val sightTo = to - cameraPosition
        val side = (along cross (sightFrom + sightTo)).normalised()
        if (side.lengthSquared < 0.5) return false // segment points straight at the camera

        val halfFrom = side * (sightFrom.length * widthFactor)
        val halfTo = side * (sightTo.length * widthFactor)

        val a = from - halfFrom
        val b = from + halfFrom
        val c = to - halfTo
        val d = to + halfTo

        listOf(a, b, c, b, d, c).forEach {
            vertices.put(it.x.toFloat()).put(it.y.toFloat()).put(it.z.toFloat())
        }
        return true
    }

    companion object {
        private const val INITIAL_SEGMENTS = 32
        private const val VERTICES_PER_SEGMENT = 6
        private const val FLOATS_PER_SEGMENT = VERTICES_PER_SEGMENT * 3

        /** A sub-millimetre segment has no meaningful direction to spread along. */
        private const val MIN_LENGTH_SQUARED = 1e-6

        /**
         * Half-width in world metres per metre of range, such that the ribbon covers
         * [widthPx] pixels at any distance.
         *
         * `projection[5]` of a standard perspective matrix is `1 / tan(fovY / 2)`, which
         * is exactly the factor relating a world offset at unit range to a fraction of
         * the viewport height.
         */
        fun widthFactorFor(projection: FloatArray, viewportHeight: Int, widthPx: Float): Float {
            val focal = projection[5]
            if (focal <= 0f || viewportHeight <= 0) return 0.002f
            return widthPx / (viewportHeight * focal)
        }

        private const val VERTEX_SHADER = """
            uniform mat4 u_ViewProjection;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ViewProjection * a_Position;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Colour;
            void main() {
                gl_FragColor = u_Colour;
            }
        """
    }
}
