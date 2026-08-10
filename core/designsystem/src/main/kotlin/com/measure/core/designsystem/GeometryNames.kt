package com.measure.core.designsystem

import androidx.annotation.StringRes
import com.measure.core.geometry.DoorSwing
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.capture.HitSource
import com.measure.core.geometry.capture.MeasurementMode
import com.measure.core.geometry.capture.RangeAdvice

/**
 * What the interface calls the values `:core:geometry` computes with.
 *
 * These used to be `label` properties on the enums themselves, which was convenient and
 * wrong in two ways at once. `:core:geometry` is pure Kotlin — it has no resources and could
 * never have translated one — and a name that lives on the value is a name each feature is
 * free to render differently, which is how a door ends up hung "Left, in" on the capture
 * screen and "left-hand inward" in the editor.
 *
 * Here instead, once, in the module every feature already depends on. The enums keep what is
 * genuinely theirs: a `DoorSwing` still knows which jamb its hinge is on, a `HitSource` still
 * knows its own sigma. What they no longer know is English.
 *
 * They are `@StringRes` rather than `String` so a caller in a composable can use
 * `stringResource` and one outside it can use `Resources.getString`, which is the split every
 * call site here actually has.
 */

@StringRes
fun MeasurementMode.labelRes(): Int = when (this) {
    MeasurementMode.FREE -> R.string.mode_free
    MeasurementMode.HORIZONTAL -> R.string.mode_level
    MeasurementMode.VERTICAL -> R.string.mode_plumb
}

@StringRes
fun MeasurementMode.hintRes(): Int = when (this) {
    MeasurementMode.FREE -> R.string.mode_free_hint
    MeasurementMode.HORIZONTAL -> R.string.mode_level_hint
    MeasurementMode.VERTICAL -> R.string.mode_plumb_hint
}

@StringRes
fun OpeningKind.labelRes(): Int = when (this) {
    OpeningKind.DOOR -> R.string.opening_door
    OpeningKind.WINDOW -> R.string.opening_window
    OpeningKind.PASSAGE -> R.string.opening_passage
}

@StringRes
fun DoorSwing.labelRes(): Int = when (this) {
    DoorSwing.HINGE_NEAR_OPENS_IN -> R.string.swing_near_in
    DoorSwing.HINGE_FAR_OPENS_IN -> R.string.swing_far_in
    DoorSwing.HINGE_NEAR_OPENS_OUT -> R.string.swing_near_out
    DoorSwing.HINGE_FAR_OPENS_OUT -> R.string.swing_far_out
}

@StringRes
fun HitSource.labelRes(): Int = when (this) {
    HitSource.PLANE_POLYGON -> R.string.source_surface
    HitSource.PLANE_INFINITE -> R.string.source_surface_extended
    HitSource.DEPTH -> R.string.source_depth
    HitSource.PLUMB -> R.string.source_plumb
    HitSource.FEATURE_POINT -> R.string.source_feature
    HitSource.INSTANT_PLACEMENT -> R.string.source_estimate
}

/**
 * What to say about how far away the user is aiming, or null when there is nothing to say.
 *
 * Nullable because [RangeAdvice.IDEAL] deliberately has no message: an indicator that is
 * always lit stops being read, so the comfortable case is silent.
 */
@StringRes
fun RangeAdvice.messageRes(): Int? = when (this) {
    RangeAdvice.TOO_CLOSE -> R.string.range_too_close
    RangeAdvice.IDEAL -> null
    RangeAdvice.LONG -> R.string.range_long
    RangeAdvice.VERY_LONG -> R.string.range_very_long
}
