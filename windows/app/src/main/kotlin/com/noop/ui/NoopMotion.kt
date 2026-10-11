package com.noop.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// MARK: - NoopMotion — the "Design Reset" motion set (JVM / Compose Desktop port)
//
// Compose port of StrandDesign/NoopMotion.swift. The house motion language for the
// WHOOP-flavoured redesign: smooth, snappy, almost no bounce. Beauty is in the restraint —
// type, spacing and a single confident settle, NOT effects. NO glow here, nothing that
// pulses or loops. Three things screens reach for constantly:
//
//   - a refined spring set (screen / card / value)
//   - `CountUpText` — big scores/metrics tick up to their new value
//   - `Modifier.staggeredAppear(index)` — list/grid items fade + rise in, once, in sequence
//
// Every helper is public, GPU-cheap (alpha / translation / scale only). The Android twin reads
// `Settings.Global.ANIMATOR_DURATION_SCALE` for reduce-motion; the JVM has no equivalent, so
// [rememberReduceMotion] reports a constant false (animations always run) — wire it to a pref
// if a desktop reduce-motion toggle is added later.

// MARK: - Reduce-motion detection

/**
 * True when the user has opted out of motion. The Android twin reads the system animator
 * scale; the JVM has no equivalent, so this reports `false` (animations always run). Kept as
 * a composable so call sites stay source-compatible with the Android port.
 */
@Composable
fun rememberReduceMotion(): Boolean = false

// MARK: - NoopMotion springs / tokens

object NoopMotion {

    // MARK: Springs — smooth, snappy, minimal bounce.
    // SwiftUI `spring(response:dampingFraction:)` maps to Compose `spring(dampingRatio, stiffness)`
    // where stiffness ~= (2*pi / response)^2 and dampingRatio == dampingFraction.

    /** Screen-level spring — page pushes, sheet/tab swaps. response 0.46 / damping 0.88. */
    fun <T> screen(): AnimationSpec<T> =
        spring(dampingRatio = 0.88f, stiffness = stiffnessFor(0.46f))

    /** Card-level spring — card insert/remove, row reflow, expand/collapse. response 0.40 / damping 0.85. */
    fun <T> card(): AnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = stiffnessFor(0.40f))

    /** Value-level spring — number ticks, gauge fraction, chip/state changes. response 0.34 / damping 0.90. */
    fun <T> value(): AnimationSpec<T> =
        spring(dampingRatio = 0.90f, stiffness = stiffnessFor(0.34f))

    /** Convert a SwiftUI spring `response` (perceptual duration, seconds) to a Compose `stiffness`. */
    private fun stiffnessFor(response: Float): Float {
        val omega = (2.0 * Math.PI) / response.toDouble()
        return (omega * omega).toFloat()
    }

    // MARK: Stagger

    /** Per-item delay (ms) for a staggered list/grid reveal. Index 0 fires immediately. */
    const val staggerMs: Int = 40

    /** The pre-reveal vertical offset (dp) for a staggered/appear item (rises UP into place). */
    val riseOffset: Dp = 8.dp

    /** Returns [spec] normally, or `null` when [reduced] (so the call site can snap instantly). */
    fun <T> gated(spec: AnimationSpec<T>, reduced: Boolean): AnimationSpec<T>? =
        if (reduced) null else spec
}

// MARK: - CountUpText
//
// Animates a numeric value counting up (or down) to its latest value whenever `value`
// changes, and on first appear (from 0 -> value). Reduce Motion -> final value shown instantly.

/**
 * A text view whose number animates from its previous value to the new one. Use for the big
 * scores / hero metric read-outs. Mirrors iOS `CountUpText`.
 */
@Composable
fun CountUpText(
    value: Double,
    format: (Double) -> String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    spec: AnimationSpec<Float> = NoopMotion.value(),
) {
    val reduced = rememberReduceMotion()

    var target by remember { mutableStateOf(if (reduced) value.toFloat() else 0f) }
    LaunchedEffect(value, reduced) { target = value.toFloat() }

    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) tween(durationMillis = 0) else spec,
        label = "CountUpText",
    )
    val shown = if (reduced) value.toFloat() else animated

    Text(
        text = format(shown.toDouble()),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

// MARK: - Staggered appear
//
// Fade-in + 8dp rise, sequenced by `index`. Runs ONCE per element (guarded by a saved flag) so
// re-composition / scroll recycling don't re-trigger it. Reduce Motion -> instant, no offset.

/**
 * Fade-in + 8dp rise on first appearance, delayed by `index * 40ms` for a sequenced list/grid
 * reveal. Runs ONCE per element. Honours Reduce Motion (appears instantly, no offset).
 */
fun Modifier.staggeredAppear(index: Int, isVisible: Boolean = true): Modifier = composed {
    val reduced = rememberReduceMotion()
    var hasAppeared by rememberSaveable { mutableStateOf(false) }

    val progress by animateFloatAsState(
        targetValue = if (!isVisible || hasAppeared || reduced) 1f else 0f,
        animationSpec = if (reduced) tween(0) else NoopMotion.card(),
        label = "staggeredAppear",
    )

    LaunchedEffect(isVisible, reduced) {
        if (!isVisible || hasAppeared) return@LaunchedEffect
        if (reduced) {
            hasAppeared = true
        } else {
            delay((index.coerceAtLeast(0) * NoopMotion.staggerMs).toLong())
            hasAppeared = true
        }
    }

    val rise = with(LocalDensity.current) { NoopMotion.riseOffset.toPx() }
    this
        .alpha(if (!isVisible) 1f else progress)
        .graphicsLayer { translationY = if (!isVisible) 0f else (1f - progress) * rise }
}
