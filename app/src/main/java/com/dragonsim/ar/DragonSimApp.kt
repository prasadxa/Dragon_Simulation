package com.dragonsim.ar

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dragonsim.ar.ar.ARSceneScreen
import com.dragonsim.ar.ar.ArCapture
import com.dragonsim.ar.ar.Behavior
import com.dragonsim.ar.ar.CreatureSim
import com.dragonsim.ar.ar.Phase
import com.dragonsim.ar.ar.PlacementMode
import com.dragonsim.ar.ui.MiniMap
import com.dragonsim.ar.ui.ScanOverlay
import androidx.compose.foundation.layout.size
import com.dragonsim.ar.ui.FloatingReaction
import com.dragonsim.ar.ui.HappinessBar
import com.dragonsim.ar.ui.HintPill
import com.dragonsim.ar.ui.HoldAction
import com.dragonsim.ar.ui.Joystick
import com.dragonsim.ar.ui.OverlayState
import com.dragonsim.ar.ui.RoundAction
import com.dragonsim.ar.ui.SettingsSheet
import com.dragonsim.ar.ui.SettingsState
import com.dragonsim.ar.ui.StatusOverlay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** App entry composable: theme + root. */
@Composable
fun DragonSimApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        AppRoot()
    }
}

/**
 * Screen layout + UI state. The world simulation lives in [CreatureSim]; this
 * only routes gestures/buttons into it and lays out the HUD.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sim = remember { CreatureSim() }
    val capture = remember { ArCapture(context.applicationContext) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(SettingsState()) }
    var showSettings by remember { mutableStateOf(false) }
    var hideUi by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var joystick by remember { mutableStateOf(0f to 0f) }
    var debugLine by remember { mutableStateOf("") }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(sim.hapticTick) {
        if (sim.hapticTick > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(settings.faceCamera) { sim.faceCamera = settings.faceCamera }

    // Persist choices + happiness across launches.
    val prefs = remember { context.getSharedPreferences("dragon_ar", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        Config.CREATURES.firstOrNull { it.asset == prefs.getString("creature", null) }?.let(sim::selectCreature)
        PlacementMode.entries.firstOrNull { it.name == prefs.getString("mode", null) }?.let(sim::selectMode)
        sim.happiness = prefs.getFloat("happiness", sim.happiness)
        settings = settings.copy(
            occlusion = prefs.getBoolean("occlusion_v2", settings.occlusion),
            showPlanes = prefs.getBoolean("planes", settings.showPlanes),
            shadow = prefs.getBoolean("shadow", settings.shadow),
            faceCamera = prefs.getBoolean("faceCamera", settings.faceCamera),
        )
        while (true) {
            kotlinx.coroutines.delay(5_000)
            prefs.edit().putFloat("happiness", sim.happiness).apply()
        }
    }
    LaunchedEffect(sim.creature, sim.mode, settings) {
        prefs.edit()
            .putString("creature", sim.creature.asset)
            .putString("mode", sim.mode.name)
            .putBoolean("occlusion_v2", settings.occlusion)
            .putBoolean("planes", settings.showPlanes)
            .putBoolean("shadow", settings.shadow)
            .putBoolean("faceCamera", settings.faceCamera)
            .putFloat("happiness", sim.happiness)
            .apply()
    }

    fun offerShare(uri: Uri?, mime: String, what: String) = scope.launch {
        if (uri == null) {
            snackbar.showSnackbar("$what failed")
            return@launch
        }
        val shareable = uri.scheme == "content"
        val result = snackbar.showSnackbar(
            message = "$what saved to gallery",
            actionLabel = if (shareable) "Share" else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) capture.share(uri, mime)
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        if (errorMessage == null) {
            ARSceneScreen(
                sim = sim,
                occlusion = settings.occlusion,
                showPlanes = settings.showPlanes && !hideUi,
                showShadow = settings.shadow,
                mirrorer = capture.mirrorer,
                onSessionError = { errorMessage = it },
                onDebugHud = { debugLine = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            StatusOverlay(
                state = OverlayState.Unsupported,
                errorMessage = errorMessage,
                onReset = {
                    errorMessage = null
                    sim.reset(requireImageLoss = false)
                },
            )
            return@Box
        }

        // Gesture layer: tap = place / pet / walk-here, pinch = resize.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { sim.tap(it.x, it.y) }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (sim.spawned) {
                            sim.userScale = (sim.userScale * zoom)
                                .coerceIn(Config.MIN_USER_SCALE, Config.MAX_USER_SCALE)
                        }
                    }
                },
        )

        if (sim.phase == Phase.Scanning) {
            ScanOverlay(sim, onFinish = { sim.finishScan() }, onSkip = { sim.finishScan() })
            return@Box
        }

        if (hideUi) {
            RoundAction("👁", "Show UI", { hideUi = false }, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
            return@Box
        }

        // ── Top bar: mode, creature picker, hint ────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp).fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow {
                    PlacementMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = sim.mode == m,
                            onClick = { sim.selectMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, PlacementMode.entries.size),
                            label = { Text(if (m == PlacementMode.Surface) "Surface" else "Image") },
                        )
                    }
                }
                Spacer(Modifier.padding(4.dp))
                RoundAction("⚙️", "", { showSettings = true })
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            ) {
                Config.CREATURES.forEach { option ->
                    FilterChip(
                        selected = option == sim.creature,
                        onClick = { sim.selectCreature(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            if (sim.spawned) HappinessBar(sim.happiness, Modifier.padding(top = 6.dp))
            sim.hint?.let { HintPill(it, Modifier.padding(top = 8.dp)) }
            if (settings.debugHud) {
                Text(
                    text = "$debugLine | spawned=${sim.spawned} ground=${fmt(sim.groundY)} " +
                        "pose=(${fmt(sim.pose.x)},${fmt(sim.pose.y)},${fmt(sim.pose.z)}) " +
                        "v=${fmt(hypot(sim.pose.velX, sim.pose.velZ))} fly=${sim.flying} " +
                        "zoom=${fmt(sim.userScale)} joy=(${fmt(joystick.first)},${fmt(joystick.second)})",
                    color = Color(0xFF7CFC00),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        MiniMap(
            sim = sim,
            rangeM = 2f,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 150.dp, end = 12.dp).size(104.dp),
        )

        // ── Not placed yet: place without an image or plane ─────────────────
        if (!sim.spawned && sim.mode == PlacementMode.Surface) {
            Button(
                onClick = { sim.placeInFront() },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
            ) { Text("✨ Place in front of me") }
        }

        FloatingReaction(sim.reactionTick, sim.reactionGlyph, sim.screen.x, sim.screen.y - sim.screen.radiusPx)

        // Off-screen indicator: arrow on the screen edge pointing at the creature.
        val spot = sim.screen
        if (sim.spawned && !spot.visible && viewSize.width > 0) {
            val cx = viewSize.width / 2f
            val cy = viewSize.height / 2f
            val ang = atan2(spot.y - cy, spot.x - cx)
            val margin = with(LocalDensity.current) { 56.dp.toPx() }
            val rx = cx - margin
            val ry = cy - margin
            val k = minOf(rx / maxOf(abs(cos(ang)), 1e-3f), ry / maxOf(abs(sin(ang)), 1e-3f))
            val ax = cx + cos(ang) * k
            val ay = cy + sin(ang) * k
            Text(
                text = "➤",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier
                    .offset { IntOffset((ax - 40).roundToInt(), (ay - 40).roundToInt()) }
                    .rotate(Math.toDegrees(ang.toDouble()).toFloat())
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(8.dp),
            )
        }

        if (sim.spawned) {
            Joystick(
                onMove = { x, y ->
                    joystick = x to y
                    sim.joystick = x to y
                },
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(24.dp),
            )

            // ── Right-hand action column ────────────────────────────────────
            val clips = sim.clips
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 24.dp),
            ) {
                if (sim.flying) {
                    HoldAction("⬆️", "Up", { sim.altitudeInput = if (it) 1f else 0f })
                    HoldAction("⬇️", "Down", { sim.altitudeInput = if (it) -1f else 0f })
                } else {
                    RoundAction("🦘", "Jump", { sim.jump() })
                }
                if (clips?.canFly == true && clips.canWalk) {
                    RoundAction(if (sim.flying) "🛬" else "🪽", if (sim.flying) "Land" else "Fly", { sim.toggleFlying() }, active = sim.flying)
                }
                RoundAction("🎾", "Throw", { sim.throwBall() })
                if (!clips?.actions.isNullOrEmpty()) {
                    Box {
                        RoundAction("✨", "Tricks", { actionsOpen = true })
                        DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                            clips.actions.forEach { i ->
                                DropdownMenuItem(
                                    text = { Text(prettyClip(sim.clipNames.getOrNull(i) ?: "Clip $i")) },
                                    onClick = {
                                        sim.playAction(i)
                                        actionsOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                val (glyph, label) = when (sim.behavior) {
                    Behavior.Manual -> "🕹️" to "Manual"
                    Behavior.Follow -> "🚶" to "Follow"
                    Behavior.Wander -> "🌿" to "Roam"
                }
                RoundAction(glyph, label, {
                    sim.behavior = Behavior.entries[(sim.behavior.ordinal + 1) % Behavior.entries.size]
                })
            }

            // ── Left-hand capture column ────────────────────────────────────
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            ) {
                RoundAction("📷", "Photo", {
                    capture.takePhoto(viewSize.width, viewSize.height) { uri ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        offerShare(uri, "image/jpeg", "Photo")
                    }
                })
                RoundAction(if (recording) "⏹️" else "⏺️", if (recording) "Stop" else "Video", {
                    if (recording) {
                        recording = false
                        offerShare(capture.stopRecording(), "video/mp4", "Video")
                    } else {
                        recording = capture.startRecording(viewSize.width, viewSize.height)
                        if (!recording) scope.launch { snackbar.showSnackbar("Recording unavailable") }
                    }
                }, active = recording)
                RoundAction("🙈", "Hide UI", { hideUi = true })
                if (sim.creature.webUrl != null) {
                    RoundAction("🌐", "Google AR", {
                        if (!SceneViewerLauncher.open(context, sim.creature)) {
                            scope.launch { snackbar.showSnackbar("Google AR viewer not available") }
                        }
                    })
                }
                RoundAction("🏠", "Home", {
                    sim.goHome()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                })
                RoundAction("🔄", "Reset", {
                    if (recording) {
                        recording = false
                        capture.stopRecording()
                    }
                    sim.reset(requireImageLoss = sim.mode == PlacementMode.Image)
                })
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 140.dp),
        )
        Spacer(Modifier.height(0.dp))
    }

    if (showSettings) {
        SettingsSheet(
            state = settings,
            onChange = { settings = it },
            onDismiss = { showSettings = false },
            onPlaceInFront = {
                showSettings = false
                sim.reset(requireImageLoss = false)
                sim.placeInFront()
            },
            onRescan = {
                showSettings = false
                sim.rescan()
            },
        )
    }
}

/** "CharacterArmature|HitReact" → "Hit React". */
private fun prettyClip(name: String): String =
    name.substringAfterLast('|').replace('_', ' ').replace('-', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .removeSuffix(" loop").trim()
        .replaceFirstChar { it.uppercase() }

private fun fmt(f: Float) = "%.2f".format(f)
