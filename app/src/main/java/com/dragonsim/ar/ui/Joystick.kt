package com.dragonsim.ar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Dragging emits `onMove(x, y)` normalized to [-1, 1] on both axes (screen
 * convention: +y is down, so pushing up gives a negative y). The knob travel is
 * clamped so it stays inside the base. On release it emits `onMove(0, 0)`.
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

    fun release() {
        knobOffset = Offset.Zero
        onMove(0f, 0f)
    }

    Box(
        modifier = modifier
            .size(baseSize)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .pointerInput(maxRadiusPx) {
                detectDragGestures(
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, dragAmount ->
                    change.consume()
                    val raw = knobOffset + dragAmount
                    val distance = raw.getDistance()
                    val clamped =
                        if (distance > maxRadiusPx) raw * (maxRadiusPx / distance) else raw
                    knobOffset = clamped
                    onMove(
                        (clamped.x / maxRadiusPx).coerceIn(-1f, 1f),
                        (clamped.y / maxRadiusPx).coerceIn(-1f, 1f),
                    )
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
