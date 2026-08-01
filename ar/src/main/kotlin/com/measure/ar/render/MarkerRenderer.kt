package com.measure.ar.render

import android.opengl.GLES20
import com.measure.core.geometry.Vec3

/**
 * Draws captured points as flat discs of constant on-screen size.
 *
 * Constant *screen* size rather than constant world size is the right call for a marker:
 * it is a piece of interface, not an object in the room, and a marker that shrinks to a
 * speck at four metres stops doing its job precisely when aiming is hardest.
 *
 * Depth testing is off. A measurement between two corners of a room usually has furniture
 * in between, and a marker that vanishes behind the sofa it is measuring around would be
 * technically correct and practically useless.
 */
internal class MarkerRenderer {

    private var program = 0
    private var positionAttribute = 0
    private var mvpUniform = 0
    private var colourUniform = 0
    private var sizeUniform = 0

    private var vertices = GlUtil.floatBuffer(INITIAL_CAPACITY * 3)

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ViewProjection")
        colourUniform = GLES20.glGetUniformLocation(program, "u_Colour")
        sizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    fun draw(
        points: List<Vec3>,
        viewProjection: FloatArray,
        colour: GlColour,
        sizePx: Float,
    ) {
        if (points.isEmpty()) return

        if (vertices.capacity() < points.size * 3) {
            vertices = GlUtil.floatBuffer(points.size * 3)
        }
        vertices.clear()
        points.forEach {
            vertices.put(it.x.toFloat()).put(it.y.toFloat()).put(it.z.toFloat())
        }
        vertices.flip()

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjection, 0)
        GLES20.glUniform4f(colourUniform, colour.r, colour.g, colour.b, colour.a)
        GLES20.glUniform1f(sizeUniform, sizePx)

        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size)
        GLES20.glDisableVertexAttribArray(positionAttribute)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkErrors("markers")
    }

    private companion object {
        const val INITIAL_CAPACITY = 64

        const val VERTEX_SHADER = """
            uniform mat4 u_ViewProjection;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ViewProjection * a_Position;
                gl_PointSize = u_PointSize;
            }
        """

        /**
         * A filled disc with a soft edge and a brighter rim, so the marker reads against
         * both a dark floor and a white wall without needing an outline pass.
         */
        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Colour;
            void main() {
                float d = length(gl_PointCoord - vec2(0.5));
                if (d > 0.5) discard;
                float edge = 1.0 - smoothstep(0.42, 0.5, d);
                float rim = smoothstep(0.24, 0.34, d);
                vec3 tint = mix(u_Colour.rgb, vec3(1.0), rim * 0.55);
                gl_FragColor = vec4(tint, u_Colour.a * edge);
            }
        """
    }
}
