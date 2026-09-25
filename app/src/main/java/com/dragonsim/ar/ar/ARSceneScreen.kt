package com.dragonsim.ar.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.dragonsim.ar.BuildConfig
import com.dragonsim.ar.Config
import com.dragonsim.ar.R
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.ARSessionFailure
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.utils.SurfaceMirrorer

private const val TAG = "ARSceneScreen"
private val VISIBLE_STATES = setOf(TrackingState.TRACKING, TrackingState.PAUSED)

/**
 * Renders [sim] into an ARCore scene. All behaviour lives in [CreatureSim]; this
 * composable wires the session config, forwards each AR frame to the sim, and
 * draws the creature, its contact shadow and the thrown ball under the anchor.
 */
@Composable
fun ARSceneScreen(
    sim: CreatureSim,
    occlusion: Boolean,
    showPlanes: Boolean,
    showShadow: Boolean,
    mirrorer: SurfaceMirrorer?,
    onSessionError: (String) -> Unit,
    onDebugHud: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraStream = rememberARCameraStream(materialLoader)
    val creature = sim.creature
    val modelInstance = rememberModelInstance(modelLoader, creature.asset)
    val pointMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF00E5FF), metallic = 0f, roughness = 1f)
    }
    val ballMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFFF7043), metallic = 0f, roughness = 0.35f)
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Reference image for the AugmentedImageDatabase — decoded once, forced to ARGB_8888.
    val targetBitmap = remember { decodeAssetBitmap(context, Config.TARGET_IMAGE_ASSET) }

    // Real-world occlusion from the Depth API — only on devices that support it:
    // without depth data the occlusion pass can hide the creature entirely.
    var depthSupported by remember { mutableStateOf(false) }
    LaunchedEffect(occlusion, depthSupported) {
        cameraStream.isDepthOcclusionEnabled = occlusion && depthSupported
        Log.i(TAG, "occlusion requested=$occlusion depthSupported=$depthSupported")
    }

    // One blender per loaded model — captured by that model's node, so a node
    // mid-teardown never drives another model's animator.
    val blender = remember(modelInstance) {
        modelInstance?.let { ClipBlender.forAnimator(it.animator) }
    }
    LaunchedEffect(blender) {
        val animator = modelInstance?.animator ?: return@LaunchedEffect
        val names = (0 until animator.animationCount).map { animator.getAnimationName(it) }
        Log.d(TAG, "${creature.asset} clips=$names -> ${blender?.clips}")
        sim.clipNames = names
        blender?.let { sim.onModelLoaded(it.clips, it) }
    }

    LaunchedEffect(targetBitmap) {
        if (targetBitmap == null) {
            onSessionError("Missing or unreadable asset: ${Config.TARGET_IMAGE_ASSET}")
        }
    }

    ARSceneView(
        modifier = modifier.onSizeChanged { viewSize = it },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        cameraStream = cameraStream,
        sessionCameraConfig = ::preferredCameraConfig,
        // Placement uses planes only (official SceneView tap-to-place pattern). Depth is
        // only switched on for the optional occlusion setting.
        depthMode = if (occlusion) com.google.ar.core.Config.DepthMode.AUTOMATIC
        else com.google.ar.core.Config.DepthMode.DISABLED,
        instantPlacementMode = com.google.ar.core.Config.InstantPlacementMode.DISABLED,
        planeRenderer = sim.phase == Phase.Scanning || (showPlanes && !sim.spawned),
        surfaceMirrorer = mirrorer,
        onSessionCreated = { session -> if (BuildConfig.DEBUG) startDebugPlayback(context, session) },
        onSessionResumed = { session -> if (BuildConfig.DEBUG) startDebugRecording(context, session) },
        sessionConfiguration = { session, config ->
            Log.i(TAG, "sessionConfiguration invoked")
            val bitmap = targetBitmap
            if (bitmap == null) {
                onSessionError("Missing or unreadable asset: ${Config.TARGET_IMAGE_ASSET}")
            } else {
                configureStability(session, config)
                depthSupported = session.isDepthModeSupported(com.google.ar.core.Config.DepthMode.AUTOMATIC)
                config.augmentedImageDatabase = AugmentedImageDatabase(session).also { db ->
                    db.addImage(Config.TARGET_IMAGE_NAME, bitmap, Config.TARGET_IMAGE_WIDTH_M)
                    // Debug-only extra reference image: the poster baked into the
                    // Android emulator's ARCore virtual scene. Lives in src/debug/assets
                    // so release builds don't ship it.
                    if (BuildConfig.DEBUG) {
                        decodeAssetBitmap(context, Config.EMULATOR_POSTER_ASSET)?.let { poster ->
                            db.addImage(Config.EMULATOR_POSTER_NAME, poster, Config.EMULATOR_POSTER_WIDTH_M)
                        }
                    }
                    Log.i(TAG, "image database: ${db.numImages} image(s)")
                }
            }
        },
        onSessionFailure = { failure ->
            Log.e(TAG, "AR session failure: $failure", failure.cause)
            onSessionError(failure.toUserMessage(context))
        },
        onSessionUpdated = { session, frame ->
            sim.onArFrame(session, frame, viewSize.width, viewSize.height)
            if (BuildConfig.DEBUG) {
                onDebugHud(
                    "cam=${frame.camera.trackingState} img=${sim.imageTracked}" +
                        " | model=${if (modelInstance != null) "ok" else "loading"}",
                )
            }
        },
        onTrackingFailureChanged = { reason -> Log.d(TAG, "tracking failure changed: $reason") },
    ) {
        if (sim.phase == Phase.Scanning) {
            // Live 3D feature points while scanning (SceneView ARPointCloudDemo pattern).
            val cloud = rememberPointCloud(
                confidenceThreshold = 0.2f,
                materialInstance = pointMaterial,
                onPointCloudUpdated = { sim.scanPoints = it },
            )
            PointCloudNode(node = cloud)
        }
        val anchor = sim.anchor ?: return@ARSceneView
        // Real shadows cast onto the detected surfaces (SceneView ShadowReceiverPlane).
        if (showShadow) {
            sim.shadowPlanes.forEach { plane -> key(plane) { ShadowReceiverPlane(plane = plane) } }
        }
        key(anchor) {
            // TRACKING + PAUSED: keep the creature at its last pose through brief tracking
            // loss instead of vanishing (SceneView sample finding #1435).
            AnchorNode(anchor = anchor, visibleTrackingStates = VISIBLE_STATES) {
                modelInstance?.let { instance ->
                    key(instance) {
                        ModelNode(
                            modelInstance = instance,
                            // Nothing is played through SceneView (its playAnimation
                            // hard-cuts and restarts clips); ClipBlender drives the
                            // Filament animator from onFrame instead.
                            autoAnimate = false,
                            scaleToUnits = creature.scaleUnits,
                            // Bottom-align the bounding box so the creature stands on the ground.
                            centerOrigin = Position(0f, -1f, 0f),
                            apply = {
                                val node = this
                                isShadowCaster = true
                                // Creation-time fit scale + bottom-align offset, copied
                                // (Float3 is mutable) before we start driving the node.
                                val baseScale = Scale(node.scale.x, node.scale.y, node.scale.z)
                                val baseOffset = Position(node.position.x, node.position.y, node.position.z)
                                // Transform is written imperatively every frame: a wrapper
                                // Node's transform did not reach the model on-device (only
                                // the shadow moved), and this avoids per-frame recomposition.
                                onFrame = { nanos ->
                                    blender?.onFrame(node.animator, nanos)
                                    driveCreature(node, sim, baseScale, baseOffset, creature.yawOffsetDeg)
                                }
                            },
                        )
                    }
                }
                sim.ball?.let { b ->
                    val r = Config.BALL_RADIUS_M
                    SphereNode(
                        radius = r,
                        materialInstance = ballMaterial,
                        position = Position(b.x, b.y + r * sim.userScale, b.z),
                        scale = Scale(sim.userScale),
                    )
                }
            }
        }
    }
}

/**
 * Pose + zoom + pop-in + landing squash onto the model node. The bottom-align
 * offset is scaled and rotated with the body so the creature pivots and
 * squashes about its feet.
 */
private fun driveCreature(
    node: ModelNode,
    sim: CreatureSim,
    baseScale: Scale,
    baseOffset: Position,
    yawOffsetDeg: Float,
) {
    val p = sim.pose
    val k = sim.userScale * easeOutBack(sim.spawnProgress).coerceAtLeast(0.01f)
    val sq = sim.squash
    val kxz = k * (1f + sq * 0.5f)
    val ky = k * (1f - sq)
    val rot = Rotation(p.pitchDeg, p.yawDeg + yawOffsetDeg, p.rollDeg)
    val off = rotation(rot) * Float4(baseOffset.x * kxz, baseOffset.y * ky, baseOffset.z * kxz, 0f)
    node.position = Position(p.x + off.x, p.y + Config.DRAGON_Y_OFFSET + off.y, p.z + off.z)
    node.rotation = rot
    node.scale = Scale(baseScale.x * kxz, baseScale.y * ky, baseScale.z * kxz)
}

/** Overshoot ease for the spawn pop-in. */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * ARCore's own default config (first in the list, tuned for tracking) unless the
 * phone has a hardware depth sensor, which ARCore fuses into tracking. Picking the
 * highest-resolution CPU image slows tracking on mid-range phones.
 */
private fun preferredCameraConfig(session: Session): CameraConfig {
    val all = session.getSupportedCameraConfigs(CameraConfigFilter(session))
    val chosen = all.firstOrNull { it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE }
        ?: session.cameraConfig
    Log.i(TAG, "camera config: ${chosen.imageSize} fps=${chosen.fpsRange} depth=${chosen.depthSensorUsage}")
    return chosen
}

/**
 * Debug-only ARCore Recording & Playback so the app can be tested on-device
 * without a person moving the phone:
 *   record: adb shell am start -n com.dragonsim.ar/.MainActivity --ez record true
 *   replay: adb shell am start -n com.dragonsim.ar/.MainActivity --ez playback true
 * The dataset lives at <external files>/session.mp4.
 */
private fun debugDataset(context: Context) = java.io.File(context.getExternalFilesDir(null), "session.mp4")

private fun startDebugPlayback(context: Context, session: Session) {
    val intent = (context as? android.app.Activity)?.intent ?: return
    if (!intent.getBooleanExtra("playback", false)) return
    val file = debugDataset(context)
    runCatching { session.setPlaybackDatasetUri(android.net.Uri.fromFile(file)) }
        .onSuccess { Log.i(TAG, "playback from $file") }
        .onFailure { Log.e(TAG, "playback failed", it) }
}

private fun startDebugRecording(context: Context, session: Session) {
    val intent = (context as? android.app.Activity)?.intent ?: return
    if (!intent.getBooleanExtra("record", false) || session.recordingStatus == com.google.ar.core.RecordingStatus.OK) return
    val file = debugDataset(context)
    runCatching {
        session.startRecording(
            com.google.ar.core.RecordingConfig(session)
                .setMp4DatasetUri(android.net.Uri.fromFile(file))
                .setAutoStopOnPause(true),
        )
    }.onSuccess { Log.i(TAG, "recording to $file") }
        .onFailure { Log.e(TAG, "recording failed", it) }
}

/**
 * Continuous autofocus (sharper features at close range) and ARCore electronic
 * image stabilisation where the device supports it (ARCore 1.43+).
 */
private fun configureStability(session: Session, config: com.google.ar.core.Config) {
    config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
    val eis = com.google.ar.core.Config.ImageStabilizationMode.EIS
    if (session.isImageStabilizationModeSupported(eis)) config.imageStabilizationMode = eis
    Log.i(TAG, "stability: eis=${config.imageStabilizationMode} focus=${config.focusMode}")
}

private fun decodeAssetBitmap(context: Context, assetPath: String): Bitmap? =
    runCatching {
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            ?.let { decoded ->
                if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                else decoded.copy(Bitmap.Config.ARGB_8888, false)
            }
    }.onFailure { Log.e(TAG, "failed to decode $assetPath", it) }
        .getOrNull()

private fun ARSessionFailure.toUserMessage(context: Context): String = when (this) {
    is ARSessionFailure.DeviceNotCompatible,
    is ARSessionFailure.SessionUnsupported -> context.getString(R.string.status_unsupported)

    is ARSessionFailure.ArCoreNotInstalled ->
        "Google ARCore is not installed — install it from the Play Store and restart"

    is ARSessionFailure.ApkTooOld -> "ARCore needs an update — update it from the Play Store"
    is ARSessionFailure.SdkTooOld -> "This app needs an update (ARCore SDK too old)"
    is ARSessionFailure.UserDeclinedInstall -> "ARCore installation was declined"

    else -> context.getString(
        R.string.status_ar_failed,
        cause.message ?: cause.javaClass.simpleName,
    )
}
