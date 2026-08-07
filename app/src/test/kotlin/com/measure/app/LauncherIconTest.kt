package com.measure.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the launcher icon, at the sizes it is actually seen at.
 *
 * The icon shipped as `@android:drawable/ic_menu_compass` — a grey system menu glyph —
 * for six versions, because nothing ever displayed it anywhere a person would look. It is
 * simultaneously the most-seen asset in the product and the one with the least feedback:
 * it appears on a home screen and in a store listing, neither of which is a place this
 * project's tests had ever reached.
 *
 * Two sizes, because the failure modes differ. At 192 px the drawing is judged; at 48 px
 * the question is only whether it still reads as anything at all, which is where fine
 * strokes and clever detail quietly turn to mush.
 *
 * Written into the Roborazzi output directory so CI publishes it with the screenshots.
 * There is no pixel assertion here — the assertion is that somebody looked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LauncherIconTest {

    @Test
    fun `the launcher icon renders at every size it is seen at`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)

        assertNotNull("The manifest points at a launcher icon that does not resolve.", icon)

        val output = File("build/outputs/roborazzi").apply { mkdirs() }
        for (size in listOf(512, 192, 48)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon!!.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))

            val file = File(output, "launcher-icon-$size.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("Nothing was written for the ${size}px icon.", file.length() > 0)
        }
    }
}
