package com.measure.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * The controls, defined once — see `docs/DESIGN.md` §7.
 *
 * **There are three buttons here, and that is the point.** Until this file had them, the app
 * contained exactly one button component at one height with one type size, which meant the
 * main action on a screen and a sort filter were the same object. No amount of arranging
 * fixes that, and it was the first thing a user said about the built app: "the hierarchy in
 * the buttons is way off, the new measurement CTA and the filters have the same size in font
 * and button height."
 *
 * - [MeasurePrimaryButton] — 52dp, 17sp. The one thing a screen is for. One per screen.
 * - [MeasureButton] — 38dp, 14.5sp. Lock, Share, Set, Remove.
 * - [MeasureChip] — 29dp visible, 48dp to the finger. Filters, options, units.
 */
private val Hairline = 1.dp

/**
 * The micro-eyebrow that introduces a value.
 *
 * The only place the uppercase transform survives, and a component rather than a bare text
 * style so that stays true: the transform happens here, once, where it can be argued with.
 * A blanket `uppercase()` on every label produced two shipped bugs on its own — a units
 * toggle labelled `"m"` that became a lone capital letter in a box, and the Turkish `i`,
 * which `String.uppercase()` turns into `İ` under the default locale.
 *
 * **The casing follows the text, not the phone.** `uppercase()` with no argument uses
 * `Locale.getDefault()`, which is the *device* locale — so an English string on a Turkish
 * phone becomes `İ`, and the string being uppercased has nothing to do with the language
 * that rule belongs to. Taking the locale from the resource configuration asks the right
 * question: uppercase this the way the language it is written in does.
 */
@Composable
fun MeasureTag(
    text: String,
    modifier: Modifier = Modifier,
    colour: Color = MeasureColours.InkFaint,
) {
    val locale = LocalConfiguration.current.locales[0]
    Text(text.uppercase(locale), modifier, color = colour, style = MeasureType.Tag)
}

/**
 * A value with its label above it, which is how every number in this app is presented.
 *
 * @param unit kept separate from [value] so it can be set smaller and quieter. "8.40 m"
 *   written as one string puts the unit at the size of the measurement, which is the most
 *   common way a reading stops looking like a reading.
 */
@Composable
fun MeasureReading(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    large: Boolean = false,
    colour: Color = MeasureColours.Ink,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair)) {
        MeasureTag(label)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Hair),
        ) {
            Text(
                text = value,
                color = colour,
                style = if (large) MeasureType.Reading else MeasureType.Value,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    color = MeasureColours.InkFaint,
                    style = MeasureType.ValueSmall,
                    modifier = Modifier.padding(bottom = if (large) MeasureSpace.Tight else 1.dp),
                )
            }
        }
    }
}

/**
 * The one thing this screen is for.
 *
 * Full width and black by default. One per screen — a second competes with the first and
 * neither reads as the answer.
 */
@Composable
fun MeasurePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(MeasureShape.Edge)
    Box(
        modifier
            .fillMaxWidth()
            .height(PrimaryHeight)
            .clip(shape)
            .background(if (enabled) MeasureColours.Primary else MeasureColours.Sunk)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MeasureSpace.Wide),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) MeasureColours.OnPrimary else MeasureColours.InkFaint,
            style = MeasureType.Title,
            maxLines = 1,
        )
    }
}

/**
 * Everything that is an action but not *the* action.
 *
 * A recessed fill rather than an outline, because on a white ground an outlined button and a
 * text field look identical — which is a fault this app has already had, in reverse, when
 * fields on a dark panel read as gaps.
 *
 * @param filled promotes it to the black fill without promoting it to primary size. For the
 *   one action in a sheet that the sheet exists for.
 */
@Composable
fun MeasureButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(MeasureShape.Edge)
    val solid = filled && enabled

    Box(
        modifier
            .height(SecondaryHeight)
            .clip(shape)
            .background(if (solid) MeasureColours.Primary else MeasureColours.Sunk)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MeasureSpace.Base),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> MeasureColours.InkFaint
                solid -> MeasureColours.OnPrimary
                else -> MeasureColours.Ink
            },
            style = MeasureType.Label,
            maxLines = 1,
        )
    }
}

/**
 * One option among several: a filter, a waste percentage, a coat count, a unit.
 *
 * **29dp to the eye, 48dp to the finger.** The visible pill shrinks so the hierarchy is
 * real; the touch target does not, because the 48dp minimum here was never a generic
 * accessibility rule. It came from watching this app used standing up, one-handed, in
 * someone else's hallway with a tape in the other hand — the least accurate a person's aim
 * ever gets. Hierarchy and target size are not in conflict; only hierarchy and *visible*
 * size are, and this is how iOS resolves the same tension.
 */
@Composable
fun MeasureChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(MeasureShape.Pill)

    Box(
        // The hit area. Transparent, taller than the pill, and outside the clip so the
        // padding genuinely takes taps rather than merely reserving space.
        modifier
            .defaultMinSize(minHeight = MinimumTouchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(ChipHeight)
                .clip(shape)
                .background(if (selected) MeasureColours.AccentWash else Color.Transparent)
                .border(
                    Hairline,
                    if (selected) Color.Transparent else MeasureColours.Line,
                    shape,
                )
                .padding(horizontal = MeasureSpace.Snug),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = when {
                    !enabled -> MeasureColours.InkFaint
                    selected -> MeasureColours.Accent
                    else -> MeasureColours.InkMuted
                },
                style = MeasureType.Small,
                maxLines = 1,
            )
        }
    }
}

/**
 * One choice from a short, fixed set — the editor's mode switch.
 *
 * A track with a sliding white knob rather than a row of hard-edged cells: the segmented
 * control is the one place where "which of these am I in" has to be legible at a glance from
 * arm's length, and a raised knob reads faster than a colour change.
 *
 * Sized by weight rather than by content so the segments do not shuffle when a translation
 * is longer — Spanish runs 20–30% longer, which is one of M10a's five constraints.
 */
@Composable
fun MeasureSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = RoundedCornerShape(MeasureShape.Pill)

    Row(
        modifier
            .fillMaxWidth()
            .clip(track)
            .background(MeasureColours.Sunk)
            .padding(SegmentInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(SegmentHeight)
                    .clip(track)
                    .background(if (selected) MeasureColours.Surface else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) MeasureColours.Ink else MeasureColours.InkMuted,
                    style = MeasureType.Small,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The same control, over the camera.
 *
 * A separate composable rather than a flag on [MeasureSegmented], because it is not a
 * variant — it is the same idea rendered in the overlay palette, and the two have no colour
 * in common. Keeping them apart is what stops a light token being reached for over a live
 * camera image, which is the fault `docs/DESIGN.md` §3 exists to prevent.
 */
@Composable
fun MeasureScrimSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = RoundedCornerShape(MeasureShape.Pill)

    Row(
        modifier
            .fillMaxWidth()
            .clip(track)
            .background(MeasureColours.ScrimSoft)
            .padding(SegmentInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(SegmentHeight)
                    .clip(track)
                    .background(if (selected) MeasureColours.OnScrim else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) MeasureColours.Ink else MeasureColours.OnScrimMuted,
                    style = MeasureType.Small,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * An action over the camera.
 *
 * The overlay's own secondary button: a dark slab with light text, because a `Sunk` grey
 * fill and near-black label would be invisible against a bright wall.
 */
@Composable
fun MeasureScrimButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(MeasureShape.Edge)
    Box(
        modifier
            .height(SecondaryHeight)
            .clip(shape)
            .background(MeasureColours.Scrim)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MeasureSpace.Base),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) MeasureColours.OnScrim else MeasureColours.OnScrimMuted,
            style = MeasureType.Label,
            maxLines = 1,
        )
    }
}

/**
 * A text field that looks like one.
 *
 * A recessed fill, and a focus state that swaps the fill for a hairline in the accent. The
 * border is not styling: an earlier version shipped fields a shade off the panel behind them
 * and on hardware they read as gaps rather than inputs — nobody could tell there was
 * anywhere to type.
 */
@Composable
fun MeasureField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    numeric: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(MeasureShape.Edge)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MeasureType.Body.copy(color = MeasureColours.Ink),
        cursorBrush = SolidColor(MeasureColours.Accent),
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        modifier = modifier
            .height(SecondaryHeight)
            .clip(shape)
            .background(MeasureColours.Sunk)
            .border(
                if (focused) Hairline + Hairline else Hairline,
                if (focused) MeasureColours.Accent else Color.Transparent,
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = MeasureSpace.Snug),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(hint, color = MeasureColours.InkFaint, style = MeasureType.Body)
                }
                field()
            }
        },
    )
}

/**
 * A raised surface with an edge.
 *
 * The edge is the entire component. `Panel` and `Surface` are both white in this direction,
 * so a card that sets only a background has no boundary at all — on hardware the editor's
 * status banners had their text sitting directly on the drawing with the plan's own lines
 * running behind it, which reads as a rendering fault rather than as a card.
 *
 * Opaque for the same reason: whatever this covers must stop being visible.
 */
@Composable
fun MeasureCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MeasureShape.Panel)
    Column(
        modifier
            .clip(shape)
            .background(MeasureColours.Panel)
            .border(Hairline, MeasureColours.Line, shape)
            .padding(MeasureSpace.Snug),
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair),
        content = content,
    )
}

/** A hairline. Structure in this direction comes from rules rather than from boxes. */
@Composable
fun MeasureRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Hairline)
            .background(MeasureColours.Line),
    )
}

/** The one thing a screen is for. */
private val PrimaryHeight = 52.dp

/** An action, but not *the* action. */
private val SecondaryHeight = 38.dp

/** One option among several — see the note on [MeasureChip] about the hit area. */
private val ChipHeight = 29.dp

private val SegmentHeight = 30.dp
private val SegmentInset = 3.dp
