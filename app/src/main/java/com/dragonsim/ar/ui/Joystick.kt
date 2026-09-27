package com.dragonsim.ar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Virtual joystick: a 120dp base circle with a 48dp knob.
 *
 * The knob sits under the finger: `onMove(x, y)` is the finger's offset from the
 * base centre, normalized to [-1, 1] (screen convention: +y is down, so pushing
 * up gives a negative y) and clamped to the base. It responds on touch-down —
 * no drag slop — and emits `onMove(0, 0)` on release.
 *
 * Visibility is the caller's job — hide it until the dragon has spawned.
 */
@Composable
fun Joystick(
    onMove: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseSize = 120.dp
    val knobSize = 48.dp
    val maxRadiusPx = with(LocalDensity.current) { (baseSize - knobSize).toPx() / 2f }

    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val move by rememberUpdatedState(onMove)

    fun release() {
        knobOffset = Offset.Zero
        move(0f, 0f)
    }

    Box(
        modifier = modifier
            .size(baseSize)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .pointerInput(maxRadiusPx) {
                // Absolute positioning: the old drag-delta version started the knob at
                // the centre wherever you touched and swallowed the touch slop, so the
                // stick lagged and pointed away from the finger.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    fun follow(position: Offset) {
                        val raw = position - center
                        val distance = raw.getDistance()
                        val clamped = if (distance > maxRadiusPx) raw * (maxRadiusPx / distance) else raw
                        knobOffset = clamped
                        move(clamped.x / maxRadiusPx, clamped.y / maxRadiusPx)
                    }
                    down.consume()
                    follow(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        follow(change.position)
                    }
                    release()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(knobSize)
                .background(Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}
