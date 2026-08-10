package com.traza.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.traza.core.designsystem.MeasureSpace
import com.traza.core.geometry.Vec2
import com.traza.core.geometry.capture.HitSource
import com.traza.core.geometry.capture.MeasurementMode
import com.traza.core.geometry.capture.RangeAdvice
import com.traza.core.geometry.capture.TrackingIssue
import com.traza.core.geometry.capture.TrackingQuality
import com.traza.core.geometry.capture.TrackingStatus
import com.traza.ar.CaptureMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The capture overlays, over stand-ins for a camera image.
 *
 * `docs/PRODUCT_PLAN.md` M10a lists five constraints the design has to respect, and this is
 * the one that cannot be checked by reading code: everything on this screen sits over a
 * live camera feed that may be any colour and any brightness. A chip that is perfectly
 * legible in a screenshot of a dark app can vanish against a sunlit white wall, and the
 * only way to know is to put it on one.
 *
 * So each set of overlays is rendered three times: over near-black, over near-white, and
 * over a bright gradient that changes underneath a single control. If a slab loses its edge
 * or a label loses its text on the second or third, that is the fault this exists to find.
 *
 * **What this does not cover, deliberately stated:** the arrangement. `CaptureScreen`'s top
 * and bottom bars are driven by a live `ArUiState` from an ARCore session, and standing one
 * up on the JVM is not possible. Laying the overlays out here in an approximation of the
 * real screen would produce a picture that looks like the app and tests a layout the app
 * does not use — worse than no picture, because it would be believed. These are the parts,
 * in their states, on the backgrounds they have to survive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CaptureHudScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** A room mid-capture: three corners down, a fourth being aimed at. */
    private val corners = listOf(Vec2(0.0, 0.0), Vec2(4.2, 0.0), Vec2(4.2, 3.6))

    private fun shoot(name: String, background: Brush, content: @Composable () -> Unit) {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(background)) { content() }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Everything that sits over the camera, stacked so it can be compared at a glance. */
    @Composable
    private fun Overlays() {
        Column(
            Modifier.fillMaxSize().padding(MeasureSpace.Base),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Base),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TrackingChip(
                tracking = TrackingStatus(TrackingQuality.GOOD, TrackingIssue.NONE, 4, 260),
                depthEnabled = true,
            )
            TrackingChip(
                tracking = TrackingStatus(
                    TrackingQuality.POOR,
                    TrackingIssue.INSUFFICIENT_LIGHT,
                    1,
                    12,
                ),
                depthEnabled = false,
            )

            // The longest advice string there is, in a constrained row, with the depth
            // suffix beside it. This is the exact case that broke on the A36: the advice
            // claimed the whole row, the suffix was squeezed to a few pixels wide, and
            // Compose wrapped it one letter per line — "depth" ran vertically down the
            // side of the chip. Rendered here at a real width so it cannot come back.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(RETICLE))
                TrackingChip(
                    tracking = TrackingStatus(
                        TrackingQuality.POOR,
                        TrackingIssue.INSUFFICIENT_FEATURES,
                        2,
                        30,
                    ),
                    depthEnabled = true,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(RETICLE))
            }

            AimAdvice(
                advice = RangeAdvice.TOO_CLOSE,
                source = HitSource.FEATURE_POINT,
                rangeText = "0.31 m",
                offFloor = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Base)) {
                MeasurementLabel("2.44 m", emphasised = true)
                MeasurementLabel("4.20 m", emphasised = false)
            }

            // Each reticle boxed, because it draws to whatever space it is given and three
            // of them in a bare row overlap into a single unreadable knot.
            Row(
                Modifier.fillMaxWidth().padding(vertical = MeasureSpace.Loose),
                horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Base),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(RETICLE)) {
                    Reticle(ready = true, hasTarget = true, samplingProgress = null)
                }
                Box(Modifier.size(RETICLE)) {
                    Reticle(ready = false, hasTarget = false, samplingProgress = null)
                }
                Box(Modifier.size(RETICLE)) {
                    Reticle(ready = true, hasTarget = true, samplingProgress = 0.6f)
                }
                RoomMinimap(
                    corners = corners,
                    preview = Vec2(0.0, 3.6),
                    closed = false,
                    size = RETICLE,
                )
            }

            CaptureModeSelector(selected = CaptureMode.ROOM, onSelect = {})
            ModeSelector(selected = MeasurementMode.HORIZONTAL, onSelect = {})

            Row(horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Wide)) {
                CaptureButton(enabled = true, sampling = false, onClick = {})
                CaptureButton(enabled = false, sampling = false, onClick = {})
                CaptureButton(enabled = true, sampling = true, onClick = {})
            }
        }
    }

    /** An ordinary room in ordinary light. */
    @Test
    fun `over a dark scene`() {
        shoot("capture-hud-dark", solid(Color(0xFF1B1A18))) { Overlays() }
    }

    /** A sunlit white wall, which is the case every overlay here has to survive. */
    @Test
    fun `over a bright scene`() {
        shoot("capture-hud-bright", solid(Color(0xFFF3F1EC))) { Overlays() }
    }

    /**
     * A background that changes under a single control.
     *
     * The hard case is not a bright scene or a dark one, it is both at once — a window in
     * the corner of a room. A slab that only works because the wall behind it happens to be
     * uniform fails here first.
     */
    @Test
    fun `over a scene that changes underneath`() {
        shoot(
            "capture-hud-mixed",
            Brush.linearGradient(
                listOf(Color(0xFFFFFFFF), Color(0xFF8C7A5E), Color(0xFF0E0E10)),
            ),
        ) { Overlays() }
    }

    private fun solid(colour: Color) = Brush.linearGradient(listOf(colour, colour))
}

/** Four across a phone with room to spare. The reticle overdraws its bounds a little. */
private val RETICLE = 72.dp

