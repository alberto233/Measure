package com.traza.ar.render

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The small amount of OpenGL ES boilerplate the renderers share.
 *
 * We render with plain GLES 2.0 rather than through a scene graph library. The AR view
 * needs a camera background, translucent plane polygons, point markers and thick lines —
 * four shaders totalling a couple of hundred lines — and taking a community 3D engine for
 * that would buy churn risk in exchange for features we do not use. docs/TECHNICAL_DESIGN.md
 * anticipated this as the fallback; it turned out to be the right first choice.
 */
internal object GlUtil {

    private const val TAG = "MeasureGl"

    const val FLOAT_BYTES = 4

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }

        // The program keeps its own reference once linked.
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("shader compile failed: $log\n$source")
        }
        return shader
    }

    fun floatBuffer(capacityFloats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacityFloats * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    /**
     * Drains the GL error queue and logs anything in it.
     *
     * Deliberately non-fatal. A dropped draw call is a cosmetic problem; killing the AR
     * session over one would turn it into the crash this app is meant not to have.
     */
    fun checkErrors(where: String) {
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x${error.toString(16)} at $where")
            error = GLES20.glGetError()
        }
    }
}

/** ARGB colour components as floats, the form GLES uniforms want. */
internal data class GlColour(val r: Float, val g: Float, val b: Float, val a: Float) {
    fun withAlpha(alpha: Float) = copy(a = alpha)

    companion object {
        fun of(hex: Long, alpha: Float = 1f) = GlColour(
            r = ((hex shr 16) and 0xFF) / 255f,
            g = ((hex shr 8) and 0xFF) / 255f,
            b = (hex and 0xFF) / 255f,
            a = alpha,
        )
    }
}
