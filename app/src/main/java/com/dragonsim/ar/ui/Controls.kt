package com.dragonsim.ar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonsim.ar.BuildConfig

private val Glass = Color.Black.copy(alpha = 0.45f)

/** Round translucent action button with an emoji glyph and a tiny caption. */
@Composable
fun RoundAction(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .background(if (active) MaterialTheme.colorScheme.primary else Glass, CircleShape)
                .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
        ) { Text(glyph, fontSize = 22.sp) }
        Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Like [RoundAction] but reports press/release — for hold-to-climb controls. */
@Composable
fun HoldAction(glyph: String, label: String, onHold: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .background(Glass, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onHold(true)
                        tryAwaitRelease()
                        onHold(false)
                    })
                },
        ) { Text(glyph, fontSize = 22.sp) }
        Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Translucent pill for hints / status. */
@Composable
fun HintPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(Glass, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

data class SettingsState(
    /** Off by default: software depth is unreliable near screens / close objects. */
    val occlusion: Boolean = false,
    val showPlanes: Boolean = true,
    val shadow: Boolean = true,
    val faceCamera: Boolean = false,
    /** Developer readout — on by default only in debug builds. */
    val debugHud: Boolean = BuildConfig.DEBUG,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: SettingsState,
    onChange: (SettingsState) -> Unit,
    onDismiss: () -> Unit,
    onPlaceInFront: () -> Unit,
    onRescan: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            SettingRow("Real-world occlusion", "Real objects can hide the creature (depth phones)", state.occlusion) {
                onChange(state.copy(occlusion = it))
            }
            SettingRow("Show detected surfaces", "Dotted overlay on found planes", state.showPlanes) {
                onChange(state.copy(showPlanes = it))
            }
            SettingRow("Contact shadow", "Grounds the creature visually", state.shadow) {
                onChange(state.copy(shadow = it))
            }
            SettingRow("Face me when idle", "Turns toward the camera when standing still", state.faceCamera) {
                onChange(state.copy(faceCamera = it))
            }
            SettingRow("Debug HUD", "Live AR state readout", state.debugHud) {
                onChange(state.copy(debugHud = it))
            }
            FilledTonalButton(onClick = onPlaceInFront, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("Re-place creature in front of me")
            }
            FilledTonalButton(onClick = onRescan, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text("Rescan room")
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/**
 * A burst of [glyph]s that float up from ([x], [y]) px and fade, re-fired each
 * time [trigger] changes.
 */
@Composable
fun FloatingReaction(trigger: Int, glyph: String, x: Float, y: Float) {
    if (trigger == 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing)) }
    val t = progress.value
    if (t >= 1f) return
    val density = LocalDensity.current
    val rise = with(density) { 90.dp.toPx() }
    val drift = listOf(-28f, 0f, 30f)
    drift.forEachIndexed { i, dx ->
        val local = ((t - i * 0.12f) / 0.76f).coerceIn(0f, 1f)
        Text(
            text = glyph,
            fontSize = (22 + i * 4).sp,
            modifier = Modifier
                .offset { IntOffset((x + dx * density.density - 30).roundToInt(), (y - rise * local - 30).roundToInt()) }
                .alpha(1f - local),
        )
    }
}

/** Compact happiness meter: emoji + bar. */
@Composable
fun HappinessBar(value: Float, modifier: Modifier = Modifier) {
    val face = when {
        value > 0.75f -> "😄"
        value > 0.45f -> "🙂"
        value > 0.2f -> "😐"
        else -> "😢"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.background(Glass, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(face, fontSize = 16.sp)
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier.padding(start = 6.dp).width(90.dp).height(6.dp),
        )
    }
}
