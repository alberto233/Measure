package com.measure.core.designsystem

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The smallest a control may be and still be reliably hit.
 *
 * Forty-eight density-independent pixels is roughly the contact patch of an adult
 * fingertip, and it is the floor both Android and WCAG give. Everything in this app was
 * built under it — pills at 37, chips at 32, the card actions at 29 — which is small
 * enough that a tap lands beside the control rather than on it, and the app appears to
 * have ignored it. The user has no way to tell that from a bug.
 *
 * Worse here than in most apps: this one is used standing up, in someone else's house,
 * one-handed, with the other hand holding a tape. That is the least accurate a person's
 * aim ever gets.
 */
val MinimumTouchTarget = 48.dp

/**
 * Grows a control to the minimum hittable size.
 *
 * **Grows it visibly, rather than hiding a larger invisible target behind a small
 * button.** Compose clips pointer input to a node's bounds, so a genuinely larger touch
 * area means a genuinely larger node either way — and given that, a control that *looks*
 * the size it responds to is the better of the two. A 29 dp button with a 48 dp hit area
 * also steals taps from whatever sits next to it.
 *
 * Belongs between `clickable` and `padding` in a modifier chain, so the click and the
 * background both take the grown size:
 *
 * ```
 * Box(
 *     Modifier.clip(shape).background(colour).clickable(onClick = …).touchTarget().padding(…),
 *     contentAlignment = Alignment.Center,
 * ) { Text(label) }
 * ```
 *
 * The [androidx.compose.foundation.layout.Box] is load-bearing: padding places its content
 * at the top-left of whatever space it is given, so a bare `Text` would sit at the top of
 * the grown control rather than in the middle of it.
 */
fun Modifier.touchTarget(minimum: Dp = MinimumTouchTarget): Modifier =
    sizeIn(minWidth = minimum, minHeight = minimum)
