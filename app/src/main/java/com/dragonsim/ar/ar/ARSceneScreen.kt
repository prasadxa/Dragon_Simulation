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
import androidx.compose.ui.platform.LocalContext
import com.dragonsim.ar.Config
import com.dragonsim.ar.R
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImage.TrackingMethod
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.ARSessionFailure
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

private const val TAG = "ARSceneScreen"

/**
 * The ARCore view: registers the reference image, tracks it, and mounts the dragon
 * model on the first matching [AugmentedImage] while [spawned] is true.
 *
 * Callbacks report outward so [com.dragonsim.ar.AppRoot] can own the state machine.
 */
@Composable
fun ARSceneScreen(
    spawned: Boolean,
    pose: DragonPose,
    isMoving: Boolean,
    onTrackingChanged: (Boolean) -> Unit,
    onSessionError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, Config.DRAGON_MODEL_ASSET)

    // Reference image for the AugmentedImageDatabase — decoded once, forced to ARGB_8888.
    val targetBitmap = remember {
        decodeAssetBitmap(context, Config.TARGET_IMAGE_ASSET)
    }

    // Union of every AugmentedImage the session has ever returned — ARCore reuses
    // the same object per physical image across frames, so the node survives
    // frames where the image drops out of getUpdatedTrackables().
    var detectedImages by remember { mutableStateOf(listOf<AugmentedImage>()) }

    // GLB clip names are unknown until the model loads — resolve once per instance.
    var idleClip by remember { mutableStateOf<String?>(null) }
    var walkClip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modelInstance) {
        val names = modelInstance?.let { instance ->
            (0 until instance.animator.animationCount).map { instance.animator.getAnimationName(it) }
        }.orEmpty()
        idleClip = DragonMotion.pickClip(names, ClipKind.Idle)
        walkClip = DragonMotion.pickClip(names, ClipKind.Walk)
        Log.d(TAG, "dragon.glb clips=$names -> idle=$idleClip walk=$walkClip")
    }

    // Surface a packaging bug (missing target.png) as an error instead of
    // silently searching forever.
    LaunchedEffect(targetBitmap) {
        if (targetBitmap == null) {
            onSessionError("Missing or unreadable asset: ${Config.TARGET_IMAGE_ASSET}")
        }
    }

    ARSceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        // Image tracking only — skip plane detection cost and plane dots.
        planeRenderer = false,
        sessionConfiguration = { session, config ->
            targetBitmap?.let { bitmap ->
                config.augmentedImageDatabase = AugmentedImageDatabase(session).also { db ->
                    db.addImage(Config.TARGET_IMAGE_NAME, bitmap, Config.TARGET_IMAGE_WIDTH_M)
                }
            }
        },
        onSessionFailure = { failure ->
            Log.e(TAG, "AR session failure: $failure", failure.cause)
            onSessionError(failure.toUserMessage(context))
        },
        onSessionUpdated = { _, frame ->
            val updated = frame.getUpdatedTrackables(AugmentedImage::class.java)
            if (updated.isNotEmpty()) {
                // Identity check: same physical image => same object each frame.
                val fresh = updated.filter { u -> detectedImages.none { it === u } }
                if (fresh.isNotEmpty()) {
                    detectedImages = detectedImages + fresh
                }
            }
            onTrackingChanged(pickTargetImage(detectedImages)?.trackingState == TrackingState.TRACKING)
        },
        onTrackingFailureChanged = { reason -> Log.d(TAG, "tracking failure changed: $reason") },
    ) {
        // ARSceneScope — one dragon only, gated on the first matching image.
        val image = pickTargetImage(detectedImages)
        if (spawned && image != null) {
            key(image) {
                AugmentedImageNode(
                    augmentedImage = image,
                    // Hide children when the camera leaves the image — don't also
                    // toggle child visibility ourselves.
                    visibleTrackingMethods = setOf(TrackingMethod.FULL_TRACKING),
                ) {
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            autoAnimate = false,
                            // Reactive: switching the value switches the clip.
                            animationName = if (isMoving) walkClip else idleClip,
                            animationLoop = true,
                            scaleToUnits = Config.DRAGON_SCALE_UNITS,
                            // Bottom-align the bounding box so the dragon stands on the image.
                            centerOrigin = Position(0f, -1f, 0f),
                            position = Position(pose.x, Config.DRAGON_Y_OFFSET, pose.z),
                            rotation = Rotation(0f, pose.yawDeg + Config.MODEL_YAW_OFFSET_DEG, 0f),
                        )
                    }
                }
            }
        }
    }
}

/** Prefer the image registered as [Config.TARGET_IMAGE_NAME]; fall back to the first seen. */
private fun pickTargetImage(images: List<AugmentedImage>): AugmentedImage? =
    images.firstOrNull { it.name == Config.TARGET_IMAGE_NAME } ?: images.firstOrNull()

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
