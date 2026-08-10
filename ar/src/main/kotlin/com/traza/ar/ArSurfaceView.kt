package com.traza.ar

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * The `GLSurfaceView` the AR session draws into.
 *
 * Thin on purpose — it exists to configure the surface correctly and to keep ARCore
 * informed of the display rotation, which is the one piece of state the renderer cannot
 * discover for itself and the one whose absence produces a sideways camera image.
 */
class ArSurfaceView(
    context: Context,
    private val controller: MeasureArController,
) : GLSurfaceView(context) {

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        // An alpha channel and a depth buffer: alpha so translucent plane shading blends,
        // depth so AR content sorts against itself.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(controller)
        renderMode = RENDERMODE_CONTINUOUSLY
        // The camera feed is opaque and fills the view, so there is nothing behind it
        // worth compositing against.
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.setDisplayRotation(currentRotation())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        controller.setDisplayRotation(currentRotation())
    }

    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
}
