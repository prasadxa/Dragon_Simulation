package com.dragonsim.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.statusBarsPadding
import com.google.ar.core.AugmentedImage.TrackingMethod
import com.dragonsim.ar.BuildConfig
import com.dragonsim.ar.ar.ARSceneScreen
import com.dragonsim.ar.ar.DragonMotion
import com.dragonsim.ar.ar.DragonPose
import com.dragonsim.ar.ui.Joystick
import com.dragonsim.ar.ui.OverlayState
import com.dragonsim.ar.ui.StatusOverlay
import kotlin.math.hypot

/** App entry composable: theme + root. */
@Composable
fun DragonSimApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        AppRoot()
    }
}

/**
 * Owns the app state machine:
 *
 * ```
 *            tracking lost          tracking + spawnAllowed
 *  ┌───────┐  ──────────────►  ┌────────┐  ─────────────────►  ┌──────────┐
 *  │ start │                   │ latch  │                      │ spawned  │
 *  └───────┘                   └────────┘                      └────┬─────┘
 *       ▲                                                          │
 *       │                        Reset                             │ image lost → "Lost" banner
 *       └──────────────────────────────────────────────────────────┘
 * ```
 *
 * `spawnAllowed` is a one-shot latch: after Reset it stays false until the image
 * has been lost at least once, so the dragon doesn't instantly re-spawn while
 * the camera is still pointed at the print.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    var spawned by remember { mutableStateOf(false) }
    var spawnAllowed by remember { mutableStateOf(true) }
    var imageTracking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var pose by remember { mutableStateOf(DragonPose()) }
    var joystick by remember { mutableStateOf(0f to 0f) }
    var debugLine by remember { mutableStateOf("") }

    // Spawn/latch logic — kept out of composition proper.
    LaunchedEffect(spawned, imageTracking, spawnAllowed) {
        if (!spawned) {
            if (!imageTracking) {
                // Image not (or no longer) tracked → re-arm the spawn latch.
                spawnAllowed = true
            } else if (spawnAllowed) {
                pose = DragonPose()
                spawned = true
                spawnAllowed = false
            }
        }
    }

    // Per-frame motion integration while the dragon exists. step() runs even
    // with no input so velocity/pitch/roll can ease back to rest (glide-out).
    LaunchedEffect(spawned) {
        if (!spawned) return@LaunchedEffect
        var lastNanos = -1L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos < 0L) 0f else (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                if (dt > 0f) {
                    pose = DragonMotion.step(
                        pose = pose,
                        input = joystick,
                        dtSeconds = dt,
                        speedMps = Config.MOVE_SPEED_MPS,
                        maxRadius = Config.CLAMP_RADIUS_M,
                    )
                }
            }
        }
    }

    fun reset() {
        spawned = false
        spawnAllowed = false // require the image to be lost once before re-spawning
        pose = DragonPose()
        joystick = 0f to 0f
        errorMessage = null // also acts as "retry" after a session failure
    }

    val overlayState = when {
        errorMessage != null -> OverlayState.Unsupported
        !spawned -> OverlayState.Searching
        imageTracking -> OverlayState.Tracking
        else -> OverlayState.Lost
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (errorMessage == null) {
            ARSceneScreen(
                spawned = spawned,
                pose = pose,
                isMoving = DragonMotion.isMoving(joystick),
                onTrackingChanged = { img ->
                    imageTracking = img?.trackingMethod == TrackingMethod.FULL_TRACKING
                },
                onSessionError = { errorMessage = it },
                onDebugHud = { debugLine = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        StatusOverlay(
            state = overlayState,
            errorMessage = errorMessage,
            onReset = { reset() },
        )

        // Joystick only exists once a dragon does.
        if (spawned && errorMessage == null) {
            Joystick(
                onMove = { x, y -> joystick = x to y },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(24.dp),
            )
        }

        // Debug HUD — the whole live state machine in one line, so a single
        // screenshot answers "what is the app doing". Debug builds only.
        if (BuildConfig.DEBUG) {
            Text(
                text = "$debugLine | spawned=$spawned arm=$spawnAllowed | " +
                    "pose=(${fmt(pose.x)},${fmt(pose.z)}) yaw=${fmt(pose.yawDeg)} " +
                    "p=${fmt(pose.pitchDeg)} r=${fmt(pose.rollDeg)} " +
                    "v=${fmt(hypot(pose.velX, pose.velZ))} | " +
                    "joy=(${fmt(joystick.first)},${fmt(joystick.second)})",
                color = Color(0xFF7CFC00),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp),
            )
        }
    }
}

private fun fmt(f: Float) = "%.2f".format(f)
