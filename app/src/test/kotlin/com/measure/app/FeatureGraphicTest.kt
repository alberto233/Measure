package com.measure.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 1024 × 500 banner at the top of the Play listing (`docs/STORE_LISTING.md` §8).
 *
 * Rendered from the same vector the listing icon uses and the same colour tokens every
 * screen uses, for the reason the icons are: a store asset that exists only as a PNG in
 * somebody's downloads folder is an asset that silently stops matching the app the first
 * time the mark moves.
 *
 * **Its type scale is its own, and deliberately not `MeasureType`'s.** Those sizes are for
 * a screen held at arm's length; this is a poster seen at thumbnail size in a listing and
 * at full width on a tablet. The weights, letter-spacing and colours still come from the
 * tokens, so it stays in the family without pretending a 52 px headline is a UI size.
 *
 * Kept in the test source set because nothing in the app ever draws it. It is a thing the
 * repository produces, not a thing the APK contains.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// mdpi so one dp is one pixel and one sp is one pixel: the numbers below are the numbers
// Play receives, rather than numbers a density has to be applied to before they mean
// anything.
@Config(qualifiers = "w1024dp-h500dp-mdpi", sdk = [34])
class FeatureGraphicTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the feature graphic renders at 1024 by 500`() {
        compose.setContent { FeatureGraphic() }

        compose.onRoot().captureRoboImage("build/outputs/roborazzi/feature-graphic-1024x500.png")
    }
}

/**
 * Ink ground, the plan mark, and the short description — nothing else.
 *
 * The one text is the 72-character short description from `docs/STORE_LISTING.md` §2, split
 * at its full stop. Repeating the line the user is about to read underneath the graphic is
 * the point rather than a redundancy: it is the sentence the whole listing is built on, and
 * a banner that says something *different* is a second promise to keep.
 *
 * Everything sits inside a generous margin because Play crops this image differently in
 * different placements, and the one thing it must never crop is a word.
 */
@Composable
private fun FeatureGraphic() {
    Row(
        Modifier
            .fillMaxSize()
            .background(MeasureColours.Ink)
            .padding(horizontal = 76.dp, vertical = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The listing icon's artwork, not the listing icon: no tile, no rounded corner, and
        // no ground of its own. Its ground and the banner's are the same ink, so a tile here
        // would be an invisible rectangle that only ever showed up as a seam.
        Image(
            painter = painterResource(R.drawable.ic_store_foreground),
            contentDescription = null,
            modifier = Modifier.size(300.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = "Measure rooms with your camera.",
                color = MeasureColours.OnScrim,
                style = MeasureType.Display.copy(fontSize = 52.sp, lineHeight = 62.sp),
            )
            Text(
                text = "Free, private, and honest about accuracy.",
                color = MeasureColours.OnScrimMuted,
                style = MeasureType.Body.copy(
                    fontSize = 27.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.01).em,
                ),
            )
        }
    }
}
