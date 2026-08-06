package com.measure.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * The controls, defined once — docs/PRODUCT_PLAN.md M10a.
 *
 * Before this there were two text fields and three chips, each with its own padding and its
 * own colour logic, and each written because the one next door was in another module. That
 * is what made restyling an 87-site edit rather than a one-file edit, and it is what this
 * set exists to end. Every control here meets the 48 dp minimum through [touchTarget].
 */
private val Hairline = 1.dp

/**
 * The micro-label that introduces a value.
 *
 * The signature of this direction, and a component rather than a bare text style because
 * the uppercasing happens here, once — so no call site has to remember it, and a translated
 * string is uppercased by the same rule as an English one.
 */
@Composable
fun MeasureTag(
    text: String,
    modifier: Modifier = Modifier,
    colour: Color = MeasureColours.OnScrimMuted,
) {
    Text(text.uppercase(), modifier, color = colour, style = MeasureType.Tag)
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
    colour: Color = MeasureColours.OnScrim,
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
                    color = MeasureColours.OnScrimMuted,
                    style = MeasureType.ValueSmall,
                    modifier = Modifier.padding(bottom = if (large) MeasureSpace.Tight else 1.dp),
                )
            }
        }
    }
}

/**
 * A button.
 *
 * Filled with the accent when it is the primary action or a selected mode; a bordered box
 * otherwise. Hard-edged, because the difference between an instrument and a consumer app is
 * largely whether the controls look machined or moulded.
 */
@Composable
fun MeasureButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val filled = (primary || selected) && enabled
    val shape = RoundedCornerShape(MeasureShape.Edge)

    Box(
        modifier
            .clip(shape)
            .background(if (filled) MeasureColours.Accent else Color.Transparent)
            .border(Hairline, if (filled) MeasureColours.Accent else MeasureColours.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .touchTarget()
            .padding(horizontal = MeasureSpace.Base, vertical = MeasureSpace.Snug),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = when {
                !enabled -> MeasureColours.OnScrimMuted
                filled -> MeasureColours.OnAccent
                else -> MeasureColours.OnScrim
            },
            style = MeasureType.Label.copy(letterSpacing = MeasureType.Tag.letterSpacing),
        )
    }
}

/**
 * One choice from a short, fixed set — the mode switch, and anything else shaped like it.
 *
 * A segmented control rather than a toggle button, because a toggle can only say "on" and
 * the editor has three modes. It also states the whole set: the editor's Quantities view was
 * unreachable and effectively invisible while mode was a button labelled "Measure", since a
 * button that is off tells you nothing about what else exists.
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
    val shape = RoundedCornerShape(MeasureShape.Edge)

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MeasureColours.Panel)
            .border(Hairline, MeasureColours.Line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            // A hairline between segments rather than a gap, so the control reads as one
            // object with divisions instead of as several buttons that happen to touch.
            if (index > 0) {
                Box(
                    Modifier
                        .width(Hairline)
                        .height(SegmentDividerHeight)
                        .background(MeasureColours.Line),
                )
            }
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .background(if (selected) MeasureColours.Accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .touchTarget()
                    .padding(horizontal = MeasureSpace.Tight, vertical = MeasureSpace.Snug),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.uppercase(),
                    color = if (selected) MeasureColours.OnAccent else MeasureColours.OnScrimMuted,
                    style = MeasureType.Label.copy(letterSpacing = MeasureType.Tag.letterSpacing),
                    maxLines = 1,
                )
            }
        }
    }
}

private val SegmentDividerHeight = 28.dp

/**
 * A text field that looks like one.
 *
 * The border is not styling. An earlier version shipped fields a shade off the panel behind
 * them, and on hardware they read as gaps rather than inputs — nobody could tell there was
 * anywhere to type. A visible edge, a hint, and a focus state that changes the edge are the
 * minimum for a control that invites typing.
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
        textStyle = MeasureType.Body.copy(color = MeasureColours.OnScrim),
        cursorBrush = SolidColor(MeasureColours.Accent),
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        modifier = modifier
            .clip(shape)
            .background(MeasureColours.Surface)
            .border(Hairline, if (focused) MeasureColours.Accent else MeasureColours.Line, shape)
            .onFocusChanged { focused = it.isFocused }
            .touchTarget()
            .padding(horizontal = MeasureSpace.Snug, vertical = MeasureSpace.Snug),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(hint, color = MeasureColours.OnScrimMuted, style = MeasureType.Body)
                }
                field()
            }
        },
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
