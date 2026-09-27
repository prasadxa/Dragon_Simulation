package com.dragonsim.ar.ar

import android.opengl.Matrix
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dragonsim.ar.BuildConfig
import com.dragonsim.ar.Config
import com.dragonsim.ar.CreatureModel
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.findAutoPlacementSurface
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

enum class PlacementMode { Surface, Image }
enum class Behavior { Manual, Follow, Wander }
enum class Phase { Scanning, Playing }

/** Creature position projected to the screen (px). */
data class ScreenSpot(
    val x: Float = 0f,
    val y: Float = 0f,
    val visible: Boolean = true,
    val inFront: Boolean = true,
    val radiusPx: Float = 0f,
)

/**
 * The creature's world, stepped once per AR frame from `onSessionUpdated`.
 *
 * Placement and anchoring follow SceneView's official tap-to-place sample
 * (samples/android-demo …/TapToPlaceArSession.kt, Apache-2.0): the creature
 * lives on a real ARCore **plane**, placed via [findAutoPlacementSurface] and
 * anchored with `Plane.createAnchor` — the steadiest anchor type on-device. When
 * no plane can be found (plain, untextured floors), a tap or "Place" falls back to
 * an Instant Placement estimate that settles onto the real floor once ARCore
 * detects it; a stopped anchor is revived at its last pose instead of deleting
 * the creature.
 *
 * Coordinates are the anchor's frame: +Y = plane normal (up for floors/tables),
 * XZ = the surface. The creature walks in XZ, stays inside the plane's polygon,
 * and when it walks off an edge onto a lower plane it re-anchors there and falls
 * under gravity.
 */
class CreatureSim {
    // ── Scan phase ───────────────────────────────────────────────────────────
    var phase by mutableStateOf(Phase.Scanning)
        private set
    var scanAreaM2 by mutableFloatStateOf(0f)
        private set
    /** Feature points currently shown (written by the scene's point-cloud node). */
    var scanPoints by mutableIntStateOf(0)
    var planeCount by mutableIntStateOf(0)
        private set
    val scanProgress get() = (scanAreaM2 / Config.SCAN_TARGET_M2).coerceIn(0f, 1f)
    /** Top-down map: outlines of detected planes as world (x, z) points, + relative height 0..1. */
    var mapPolygons by mutableStateOf<List<Pair<FloatArray, Float>>>(emptyList())
        private set
    /** World (x, z, yawDeg) of the camera; world (x, z) of the creature — mini-map markers. */
    var camMarker by mutableStateOf(Triple(0f, 0f, 0f))
        private set
    var creatureMarker by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    /** Tracked horizontal planes — receive the creature's real shadow once placed. */
    var shadowPlanes by mutableStateOf<List<Plane>>(emptyList())
        private set

    fun finishScan() {
        phase = Phase.Playing
        hint = null
    }

    fun rescan() {
        reset(requireImageLoss = false)
        phase = Phase.Scanning
    }

    // ── Creature state ───────────────────────────────────────────────────────
    var mode by mutableStateOf(PlacementMode.Surface)
        private set
    var creature by mutableStateOf(Config.CREATURES.first())
        private set
    var anchor by mutableStateOf<Anchor?>(null)
        private set
    private var anchorPlane: Plane? = null
    var pose by mutableStateOf(DragonPose())
        private set
    var ball by mutableStateOf<Ball?>(null)
        private set
    var userScale by mutableFloatStateOf(1f)
    var behavior by mutableStateOf(Behavior.Manual)
    var flying by mutableStateOf(false)
        private set
    var clips by mutableStateOf<ClipSet?>(null)
        private set
    var spawnProgress by mutableFloatStateOf(1f)
        private set
    var squash by mutableFloatStateOf(0f)
        private set
    var hint by mutableStateOf<String?>(null)
        private set
    var imageTracked by mutableStateOf(false)
        private set
    var hapticTick by mutableIntStateOf(0)
        private set
    var faceCamera by mutableStateOf(false)
    var screen by mutableStateOf(ScreenSpot())
        private set
    var happiness by mutableFloatStateOf(0.6f)
    var reactionTick by mutableIntStateOf(0)
        private set
    var reactionGlyph by mutableStateOf("❤️")
        private set
    /** Whether a surface is under the screen centre right now (drives the Place button). */
    var surfaceReady by mutableStateOf(false)
        private set

    val spawned get() = anchor != null

    /**
     * Visual-only offset (anchor frame, m) that hides ARCore's anchor corrections:
     * when the anchor jumps, the creature is drawn where it was and glides to the
     * corrected spot instead of teleporting. Decays to zero.
     */
    val renderOffset = FloatArray(3)
    val sizeM get() = creature.scaleUnits * userScale
    /** Kept for the HUD — the creature always stands on its plane (local y = 0). */
    val groundY get() = 0f

    // ── Inputs ───────────────────────────────────────────────────────────────
    @Volatile var joystick: Pair<Float, Float> = 0f to 0f
    @Volatile var altitudeInput = 0f
    var blender: ClipBlender? = null
    var clipNames: List<String> = emptyList()

    private var pendingTap: Pair<Float, Float>? = null
    private var placeRequested = false
    private var homeRequested = false
    /** Where the creature was first placed — its own anchor, so ARCore keeps it accurate. */
    private var homeAnchor: Anchor? = null
    private var homePlane: Plane? = null
    /** Set on a fresh placement; the home anchor is created on the next frame. */
    private var homePending = false
    private var jumpRequested = false
    private var throwRequested = false

    fun tap(x: Float, y: Float) { pendingTap = x to y }
    fun placeInFront() { placeRequested = true }
    /** Send the creature back to where it was first placed. */
    fun goHome() { homeRequested = true }
    fun jump() { jumpRequested = true }
    fun throwBall() { throwRequested = true }

    fun toggleFlying() {
        val c = clips ?: return
        if (!c.canFly || (!c.canWalk && flying)) return
        flying = !flying
        hoverY = pose.y + sizeM * 0.8f
    }

    fun selectMode(m: PlacementMode) {
        if (m == mode) return
        mode = m
        reset(requireImageLoss = false)
    }

    fun selectCreature(c: CreatureModel) {
        creature = c
        blender = null
        clips = null
    }

    fun onModelLoaded(set: ClipSet, newBlender: ClipBlender) {
        clips = set
        blender = newBlender
        flying = !set.canWalk && set.canFly
        hoverY = pose.y + if (flying) sizeM * 0.6f else 0f
    }

    fun playAction(index: Int) {
        blender?.playAction(index)
        cheer("⭐", 0.04f)
        hapticTick++
    }

    fun reset(requireImageLoss: Boolean = true) {
        if (anchor != null) Log.i(TAG, "reset — creature removed (requireImageLoss=$requireImageLoss)")
        anchor?.detach()
        anchor = null
        anchorPlane = null
        lastAnchorPose = null
        renderOffset.fill(0f)
        homeAnchor?.detach()
        homeAnchor = null
        homePlane = null
        homePending = false
        homeRequested = false
        pose = DragonPose()
        ball = null
        walkTarget = null
        userScale = 1f
        squash = 0f
        spawnAllowed = !requireImageLoss
        clips?.let { flying = !it.canWalk && it.canFly }
    }

    // ── Internal ─────────────────────────────────────────────────────────────
    private var spawnAllowed = true
    private var lastTimestamp = -1L
    private var hoverY = 0f
    private var walkTarget: Pair<Float, Float>? = null
    /** Last pose the anchor had while tracking — revives the creature if the anchor stops. */
    private var lastAnchorPose: Pose? = null
    /** Cleared when the ball turns out unreachable (edge with no floor below, wall). */
    private var chaseBall = true
    private var wanderTimer = 0f
    private var frameCount = 0
    private val camLocal = FloatArray(3)
    private val camForward = FloatArray(3)
    private var camDirX = 0f
    private var camDirZ = -1f
    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val vpM = FloatArray(16)
    private val clipV = FloatArray(4)
    private val outV = FloatArray(4)

    // ── Per frame ────────────────────────────────────────────────────────────

    fun onArFrame(session: Session, frame: Frame, viewW: Int, viewH: Int) {
        val camera = frame.camera
        val dt = if (lastTimestamp < 0L) 0f else ((frame.timestamp - lastTimestamp) / 1e9f).coerceIn(0f, 0.1f)
        lastTimestamp = frame.timestamp
        frameCount++

        if (camera.trackingState != TrackingState.TRACKING) {
            hint = trackingHint(camera.trackingFailureReason)
            surfaceReady = false
            return
        }
        val planes = session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        if (frameCount % 20 == 0) refreshMap(planes, frame)
        if (frameCount % 5 == 0) updateCamMarker(frame)

        if (phase == Phase.Scanning) {
            // A tap or Place request during the scan places immediately and ends it —
            // queued taps must never sit in pendingTap unprocessed.
            if (pendingTap == null && !placeRequested) {
                hint = scanHint()
                return
            }
            finishScan()
        }

        val image = session.getAllTrackables(AugmentedImage::class.java)
            .firstOrNull { it.name == Config.TARGET_IMAGE_NAME }
        imageTracked = image?.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
        if (!imageTracked) spawnAllowed = true

        val current = anchor
        if (current == null) {
            trySpawn(session, frame, planes, image, viewW, viewH)
            return
        }
        if (current.trackingState == TrackingState.STOPPED) {
            // ARCore permanently stops anchors on estimated (instant-placement) points
            // whenever tracking drops — frequent on plain floors. Don't delete the
            // creature: pin a fresh world anchor where it last was.
            val revived = lastAnchorPose?.let { last ->
                runCatching { session.createAnchor(last) }
                    .onFailure { Log.w(TAG, "could not revive anchor", it) }.getOrNull()
            }
            if (revived == null) {
                Log.i(TAG, "anchor stopped tracking (plane=${anchorPlane?.type}) — removing creature")
                reset(requireImageLoss = false)
                return
            }
            Log.i(TAG, "anchor stopped tracking (plane=${anchorPlane?.type}) — re-anchored at its last pose")
            current.detach()
            anchor = revived
            anchorPlane = anchorPlane?.takeIf { it.trackingState != TrackingState.STOPPED }
            return
        }
        if (current.trackingState == TrackingState.TRACKING) {
            // ARCore corrections move the anchor itself — the creature jumps with it.
            lastAnchorPose?.let { lp ->
                val cp = current.pose
                val jump = hypot(hypot(lp.tx() - cp.tx(), lp.ty() - cp.ty()), lp.tz() - cp.tz())
                if (jump > 0.05f) Log.i(TAG, "anchor corrected by ARCore: moved %.2f m in one frame — gliding".format(jump))
                if (jump < MAX_GLIDE_M) {
                    // The old anchor origin expressed in the new anchor frame.
                    val was = cp.inverse().transformPoint(lp.transformPoint(floatArrayOf(0f, 0f, 0f)))
                    for (i in 0..2) renderOffset[i] += was[i]
                }
            }
            lastAnchorPose = current.pose
        }
        // ARCore merges planes as the scan grows; the absorbed plane's polygon stops
        // updating, so follow it to the plane that replaced it.
        anchorPlane = topPlane(anchorPlane)
        val estimated = mode == PlacementMode.Surface && anchorPlane == null
        if (estimated) settleOntoPlane(frame, current)
        if (homePending) rememberHome(session)
        // PAUSED keeps the last pose (SceneView sample finding #1435) — keep simulating.
        hint = if (mode == PlacementMode.Surface && anchorPlane == null) {
            "Move your phone slowly around the creature to lock it to the floor"
        } else null
        if (homeRequested) {
            homeRequested = false
            walkHome(session, frame)
        }
        anchor?.let { step(frame, planes, it.pose, dt, viewW, viewH) }
    }

    private fun trySpawn(session: Session, frame: Frame, planes: List<Plane>, image: AugmentedImage?, w: Int, h: Int) {
        val tap = pendingTap.also { pendingTap = null }
        // Creature walks on floors/tables — walls and ceilings are never valid
        // placement (a vertical anchor rotates the whole local frame: "up" becomes
        // the wall normal and the joystick slides the creature along the wall).
        val floors = planes.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val candidate = if (w > 0 && h > 0) findAutoPlacementSurface(frame, floors, w, h)
            ?.takeIf { it.plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING } else null
        surfaceReady = candidate != null
        val camPose = frame.camera.displayOrientedPose

        when (mode) {
            PlacementMode.Surface -> {
                // Tap on a plane → there; else Place button / auto → centre-first candidate.
                // Every anchor is created upright (identity rotation) so the sim frame's
                // axes are identical across re-anchors — SceneView's candidate anchor is
                // yawed to face the camera, which snapped heading/velocity on edge drops.
                // No plane at all (plain, untextured floors often never get one): fall back
                // to an estimated-distance point so taps / "Place" always respond; it
                // snaps onto the real floor once ARCore finds it (see settleOntoPlane).
                val placed: Pair<Anchor, Plane?>? = when {
                    tap != null -> {
                        val floor = planeHit(frame, tap.first, tap.second)
                        when {
                            floor != null -> {
                                val plane = floor.trackable as Plane
                                uprightAnchor(plane, floor.hitPose.translation)?.let { it to plane }
                            }
                            wallHit(frame, tap.first, tap.second) != null -> {
                                hint = "That's a wall — tap the floor or a table"
                                return
                            }
                            anyPlaneHit(frame, tap.first, tap.second) -> {
                                hint = "Too near or too far — tap the floor closer to you"
                                return
                            }
                            else -> estimatedAnchor(session, frame, tap.first, tap.second)?.let { it to null }
                        }
                    }
                    (placeRequested || Config.AUTO_PLACE) && candidate != null ->
                        uprightAnchor(candidate.plane, candidate.pose.translation)?.let { it to candidate.plane }
                    placeRequested && w > 0 && h > 0 ->
                        estimatedAnchor(session, frame, w / 2f, h * 0.6f)?.let { it to null }
                    else -> null
                }
                placeRequested = false
                if (placed == null) {
                    hint = if (floors.isEmpty()) "Move your phone slowly over the floor — or tap it to place"
                    else "Point at the floor or a table, then tap it"
                    return
                }
                anchorPlane = placed.second
                Log.i(TAG, "placed on ${placed.second?.type ?: "estimated point (no plane yet)"}")
                arrive(placed.first, camPose)
                homePending = true
            }
            PlacementMode.Image -> {
                if (!(imageTracked && spawnAllowed && image != null)) {
                    hint = "Point the camera at the target image"
                    return
                }
                // A print lying on a table/floor: adopt that plane so the creature keeps
                // to its edges (and can hop down) instead of roaming off into mid-air.
                val imageAt = image.centerPose
                anchorPlane = floors.firstOrNull {
                    abs(it.centerPose.ty() - imageAt.ty()) < IMAGE_ON_PLANE_M && it.isPoseInPolygon(imageAt)
                }
                arrive(image.createAnchor(Pose.makeTranslation(imageAt.translation)), camPose)
                homePending = true
            }
        }
    }

    private fun arrive(newAnchor: Anchor, camPose: Pose) {
        anchor = newAnchor
        lastAnchorPose = newAnchor.pose
        spawnAllowed = false
        spawnProgress = 0f
        val rel = newAnchor.pose.inverse().compose(camPose)
        pose = DragonPose(yawDeg = DragonMotion.yawToward(0f, 0f, rel.tx(), rel.tz()))
        hoverY = if (flying) sizeM * 0.6f else 0f
        walkTarget = null
        hint = null
        hapticTick++
    }

    private fun step(frame: Frame, planes: List<Plane>, anchorPose: Pose, dt: Float, w: Int, h: Int) {
        val inv = anchorPose.inverse()
        val rel = inv.compose(frame.camera.displayOrientedPose)
        rel.getTranslation(camLocal, 0)
        val zA = rel.zAxis
        val yA = rel.yAxis
        camForward[0] = -zA[0]; camForward[1] = -zA[1]; camForward[2] = -zA[2]
        val dx = -zA[0] + yA[0]
        val dz = -zA[2] + yA[2]
        if (hypot(dx, dz) > 1e-3f) {
            camDirX = dx
            camDirZ = dz
        }

        val size = sizeM
        val speed = DragonMotion.travelSpeed(size, flying)
        var p = pose

        pendingTap?.let { (tx, ty) ->
            pendingTap = null
            if (tapHitsCreature(frame, anchorPose, tx, ty, w, h)) {
                react()
            } else {
                planeHit(frame, tx, ty)?.let { hit ->
                    val plane = topPlane(hit.trackable as Plane)!!
                    val local = inv.transformPoint(hit.hitPose.translation)
                    // Walk — never teleport — to anything on this level (ARCore often
                    // splits one floor into several planes) or below (walks off the
                    // edge and drops). Only a higher surface needs the hop up.
                    if (anchorPlane == null || plane == anchorPlane || local[1] < Config.SAME_LEVEL_M) {
                        // Clamp into the roam circle so the target is always reachable —
                        // an out-of-range tap used to pin the creature pushing at the
                        // boundary forever (residual velocity, never arriving).
                        val r = hypot(local[0], local[2])
                        val cap = Config.MAX_ROAM_M * 0.9f
                        walkTarget = if (r > cap) (local[0] * cap / r) to (local[2] * cap / r)
                            else local[0] to local[2]
                    } else {
                        // A higher surface (e.g. a table from the floor): hop up onto it.
                        val hopped = uprightAnchor(plane, hit.hitPose.translation)
                        if (hopped != null) {
                            Log.i(TAG, "hop up %.2f m onto another surface".format(local[1]))
                            ball = ball?.reframed(anchorPose, hopped.pose)
                            anchor?.detach()
                            anchorPlane = plane
                            arrive(hopped, frame.camera.displayOrientedPose)
                            return
                        }
                    }
                }
            }
        }

        if (throwRequested) {
            throwRequested = false
            val v = Config.THROW_SPEED_MPS * sqrt(userScale)
            ball = Ball(
                x = camLocal[0] + camForward[0] * 0.1f,
                y = camLocal[1] + camForward[1] * 0.1f - 0.05f,
                z = camLocal[2] + camForward[2] * 0.1f,
                vx = camForward[0] * v,
                vy = camForward[1] * v + v * 0.6f,
                vz = camForward[2] * v,
            )
            chaseBall = true
        }

        // ── Steering: stick > tap target > ball > behaviour ─────────────────
        val stick = joystick
        var seeking = false
        var chasing = false
        val input: Pair<Float, Float> = when {
            DragonMotion.isMoving(stick) -> {
                walkTarget = null
                DragonMotion.cameraRelative(stick, camDirX, camDirZ)
            }
            walkTarget != null -> {
                seeking = true
                val (tx, tz) = walkTarget!!
                DragonMotion.seek(p.x, p.z, tx, tz, size, size * 0.15f).also {
                    if (it == (0f to 0f)) walkTarget = null
                }
            }
            // Chase once it's on the ground (not mid-fall off an edge) and in range.
            chaseBall && ball?.let {
                (it.resting || it.y in -size * 0.3f..size * 0.3f) && hypot(it.x, it.z) < Config.MAX_ROAM_M * 0.95f
            } == true -> {
                val b = ball!!
                if (hypot(b.x - p.x, b.z - p.z) < size * 0.45f) {
                    ball = null
                    react("😋", 0.15f)
                    0f to 0f
                } else {
                    chasing = true
                    DragonMotion.seek(p.x, p.z, b.x, b.z, size, 0f)
                }
            }
            behavior == Behavior.Follow ->
                DragonMotion.seek(p.x, p.z, camLocal[0], camLocal[2], size * 2f, size * 3f)
            behavior == Behavior.Wander -> {
                wanderTimer -= dt
                if (wanderTimer <= 0f) {
                    wanderTimer = Random.nextFloat() * 4f + 3f
                    val r = size * 4f
                    walkTarget = (Random.nextFloat() * 2f - 1f) * r to (Random.nextFloat() * 2f - 1f) * r
                }
                0f to 0f
            }
            else -> 0f to 0f
        }

        val face = if (faceCamera) DragonMotion.yawToward(p.x, p.z, camLocal[0], camLocal[2]) else null
        val prev = p
        p = DragonMotion.step(
            pose = p,
            input = input,
            dtSeconds = dt,
            speedMps = speed,
            maxRadius = Config.MAX_ROAM_M,
            faceYawDeg = face,
            maxPitchDeg = if (flying) 10f else 3f,
        )

        // ── Surface constraints (walkers): stay on the plane or drop to a lower one.
        val moved = hypot(p.x - prev.x, p.z - prev.z) > 1e-5f
        // Unreachable target / ball — stop pushing at it.
        fun giveUp() {
            walkTarget = null
            if (chasing) chaseBall = false
        }
        // dt == 0 (a repeated camera frame) moves nothing but isn't a stall.
        if (dt > 0f && (seeking || chasing) && !moved) giveUp()
        val plane = anchorPlane
        if (moved && !flying && plane != null) {
            val footWorld = anchorPose.compose(Pose.makeTranslation(p.x, 0f, p.z))
            // Stepping onto another plane of the same floor: just switch planes.
            val neighbour = if (plane.isPoseInPolygon(footWorld)) null else planes.firstOrNull {
                it != plane && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    abs(it.centerPose.ty() - footWorld.ty()) < Config.SAME_LEVEL_M && it.isPoseInPolygon(footWorld)
            }
            if (neighbour != null) anchorPlane = neighbour
            else if (!plane.isPoseInPolygon(footWorld)) {
                val lower = planeBelow(frame, footWorld)
                val lowerPlane = lower?.let { topPlane(it.trackable as Plane) }
                val dropped = if (lower != null && lowerPlane != null && p.grounded) {
                    uprightAnchor(lowerPlane, lower.hitPose.translation)
                } else null
                if (dropped != null) {
                    // Walk off the edge: re-anchor on the surface below, fall the difference.
                    val drop = footWorld.ty() - lower!!.hitPose.ty()
                    Log.i(TAG, "walked off an edge — dropping %.2f m".format(drop))
                    ball = ball?.reframed(anchorPose, dropped.pose)
                    // Keep heading for a tapped spot down there.
                    val toNew = dropped.pose.inverse()
                    walkTarget = walkTarget?.let { (tx, tz) ->
                        val l = toNew.transformPoint(anchorPose.transformPoint(floatArrayOf(tx, 0f, tz)))
                        l[0] to l[2]
                    }
                    anchor?.detach()
                    anchorPlane = lowerPlane
                    anchor = dropped
                    lastAnchorPose = dropped.pose
                    pose = p.copy(x = 0f, z = 0f, y = drop, velY = 0f, grounded = false)
                    return
                }
                // Slide along the edge (keep whichever axis stays on the surface)
                // instead of freezing whenever the stick points partly off it.
                val alongX = p.copy(z = prev.z, velZ = 0f)
                val alongZ = p.copy(x = prev.x, velX = 0f)
                val xOk = onWalkable(plane, planes, anchorPose, alongX.x, alongX.z)
                val zOk = onWalkable(plane, planes, anchorPose, alongZ.x, alongZ.z)
                p = when {
                    xOk && (!zOk || abs(p.x - prev.x) >= abs(p.z - prev.z)) -> alongX
                    zOk -> alongZ
                    else -> p.copy(x = prev.x, z = prev.z, velX = 0f, velZ = 0f)
                }
                if (seeking || chasing) giveUp()
            }
        }
        if (moved && wallAhead(frame, anchorPose, prev, p, size)) {
            p = p.copy(x = prev.x, z = prev.z, velX = 0f, velZ = 0f)
            giveUp()
        }

        // ── Gravity / flight / jump — the surface is local y = 0 ─────────────
        if (flying) {
            hoverY = (hoverY + altitudeInput * Config.CLIMB_SPEED_MPS * userScale * dt)
                .coerceIn(size * 0.3f, Config.MAX_FLY_HEIGHT_M)
        }
        val jumpSpeed = if (jumpRequested && p.grounded && !flying) {
            sqrt(2f * Physics.GRAVITY * size * Config.JUMP_HEIGHT_BODIES)
        } else 0f
        jumpRequested = false
        val v = Physics.stepVertical(p.y, p.velY, p.grounded, 0f, dt, flying, hoverY, jumpSpeed)
        if (v.impactSpeed > 0.4f) {
            squash = (v.impactSpeed / 6f).coerceAtMost(0.3f)
            hapticTick++
        }
        squash *= exp(-10f * dt)
        if (squash < 0.005f) squash = 0f
        p = p.copy(y = v.y, velY = v.velY, grounded = v.grounded)

        // ── Ball: lands on whatever surface is under it (off a table edge it drops
        // to the floor below); with nothing detected there it falls away.
        ball?.let { b ->
            val ground = surfaceUnder(planes, anchorPose, b.x, b.y, b.z) ?: -10f
            val nb = Physics.stepBall(b, ground, dt)
            ball = if (nb.ageS > 25f || nb.y < -3f) null else nb
        }

        if (!(p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && p.yawDeg.isFinite())) p = prev.copy(velY = 0f)
        happiness = (happiness - dt * HAPPINESS_DRAIN_PER_S).coerceAtLeast(0f)
        if (spawnProgress < 1f) spawnProgress = (spawnProgress + dt / 0.45f).coerceAtMost(1f)
        val glide = exp(-GLIDE_RATE * dt)
        for (i in 0..2) renderOffset[i] = if (abs(renderOffset[i]) < 1e-4f) 0f else renderOffset[i] * glide
        pose = p

        project(frame, anchorPose, w, h)?.let { screen = it }
        if (frameCount % 10 == 0) {
            val wp = anchorPose.transformPoint(floatArrayOf(p.x, p.y, p.z))
            creatureMarker = wp[0] to wp[2]
        }
        if (BuildConfig.DEBUG && frameCount % 15 == 0 && (DragonMotion.isMoving(stick) || p.velX != 0f || p.velZ != 0f)) {
            // Heading check: velYaw is the direction of travel, yaw the body facing.
            val velYaw = DragonMotion.yawToward(0f, 0f, p.velX, p.velZ)
            Log.i(
                TAG,
                "move stick=(%.2f,%.2f) camUp=(%.2f,%.2f) input=(%.2f,%.2f) pos=(%.2f,%.2f) v=%.2f m/s velYaw=%.0f yaw=%.0f"
                    .format(stick.first, stick.second, camDirX, camDirZ, input.first, input.second,
                        p.x, p.z, hypot(p.velX, p.velZ), velYaw, p.yawDeg),
            )
        }
        if (frameCount % 60 == 0) {
            Log.d(
                TAG,
                "creature stick=(%.2f,%.2f) local=(%.2f,%.2f,%.2f) visible=%b plane=%s".format(
                    stick.first, stick.second, p.x, p.y, p.z, screen.visible, anchorPlane?.type,
                ),
            )
        }
        blender?.let {
            it.targetSpeed = (hypot(p.velX, p.velZ) / speed).coerceIn(0f, 1f)
            it.flying = flying
        }
    }

    // ── Reactions ────────────────────────────────────────────────────────────

    private fun cheer(glyph: String, boost: Float) {
        reactionGlyph = glyph
        reactionTick++
        happiness = (happiness + boost).coerceAtMost(1f)
    }

    private fun react(glyph: String = "❤️", boost: Float = 0.08f) {
        cheer(glyph, boost)
        hapticTick++
        val c = clips ?: return
        val b = blender
        // REACTION_NAMES is in preference order ("Yes" beats "Headbutt"). No match →
        // jump; never an arbitrary clip, which could be "Death".
        val pick = REACTION_NAMES.firstNotNullOfOrNull { want ->
            c.actions.firstOrNull { want in (clipNames.getOrNull(it)?.lowercase() ?: "") }
        }
        if (pick != null && b != null) b.playAction(pick) else jumpRequested = true
    }

    // ── Map / scan ───────────────────────────────────────────────────────────

    private fun refreshMap(planes: List<Plane>, frame: Frame) {
        if (frameCount % 120 == 0) {
            Log.i(TAG, "planes: ${planes.groupingBy { it.type }.eachCount()} spawned=$spawned phase=$phase")
        }
        planeCount = planes.size
        scanAreaM2 = planes.sumOf { (it.extentX * it.extentZ).toDouble() }.toFloat()
        shadowPlanes = planes.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val camY = frame.camera.pose.ty()
        mapPolygons = planes.map { plane ->
            val poly = plane.polygon
            val n = poly.limit() / 2
            val out = FloatArray(n * 2)
            val c = plane.centerPose
            for (i in 0 until n) {
                val wpt = c.transformPoint(floatArrayOf(poly.get(i * 2), 0f, poly.get(i * 2 + 1)))
                out[i * 2] = wpt[0]
                out[i * 2 + 1] = wpt[2]
            }
            out to (1f - (camY - c.ty()) / 1.4f).coerceIn(0f, 1f)
        }
    }

    private fun updateCamMarker(frame: Frame) {
        val cam = frame.camera.displayOrientedPose
        val fwd = cam.zAxis
        camMarker = Triple(cam.tx(), cam.tz(), DragonMotion.yawToward(0f, 0f, -fwd[0], -fwd[2]))
    }

    private fun scanHint(): String = when {
        planeCount == 0 -> "Slowly move your phone — look at the floor and furniture"
        scanProgress < 0.5f -> "Keep going — sweep across the floor and tables"
        scanProgress < 1f -> "Almost there — cover more of the surfaces"
        else -> "Scan complete — tap Finish"
    }

    // ── AR queries (planes only) ─────────────────────────────────────────────

    /** Nearest tap hit on a walkable surface (floor/table top). Walls are skipped —
     *  a tap through a wall onto the floor beyond resolves to that floor instead. */
    private fun planeHit(frame: Frame, x: Float, y: Float) = frame.hitTest(x, y).firstOrNull { h ->
        val t = h.trackable
        t is Plane && t.trackingState == TrackingState.TRACKING &&
            t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
            t.isPoseInPolygon(h.hitPose) &&
            h.distance in Config.MIN_PLACE_M..Config.MAX_PLACE_M
    }

    /** Nearest tap hit on a non-walkable plane (wall/ceiling) — only for hints. */
    private fun wallHit(frame: Frame, x: Float, y: Float) = frame.hitTest(x, y).firstOrNull { h ->
        val t = h.trackable
        t is Plane && t.trackingState == TrackingState.TRACKING &&
            t.type != Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(h.hitPose)
    }

    /** Next upward-facing plane below a world pose (e.g. the floor under a table edge). */
    private fun planeBelow(frame: Frame, at: Pose) =
        frame.hitTest(floatArrayOf(at.tx(), at.ty() + 0.02f, at.tz()), 0, DOWN, 0).firstOrNull { h ->
            val t = h.trackable
            t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(h.hitPose) &&
                h.distance in 0.05f..Config.MAX_FALL_M
        }

    /**
     * World-aligned anchor (+Y = up, no yaw) on a floor/table plane. Null if ARCore
     * refuses — e.g. the plane stopped tracking between the hit test and now.
     */
    private fun uprightAnchor(plane: Plane, translation: FloatArray): Anchor? =
        runCatching { plane.createAnchor(Pose(translation, UPRIGHT)) }
            .onFailure { Log.w(TAG, "createAnchor failed", it) }
            .getOrNull()

    /**
     * Anchor where the screen ray at (x, y) meets a floor assumed [ASSUMED_CAMERA_HEIGHT_M]
     * below the phone — for when no plane is detected. ARCore Instant Placement gives
     * the ray; the anchor is a fixed *world* anchor, not one on the InstantPlacementPoint:
     * that point keeps re-guessing its distance and dragged the creature up to ~1 m
     * in a single frame (and ARCore stops it outright whenever tracking dips).
     */
    private fun estimatedAnchor(session: Session, frame: Frame, x: Float, y: Float): Anchor? =
        runCatching {
            val probe = frame.hitTestInstantPlacement(x, y, 1f).firstOrNull() ?: return@runCatching null
            val cam = frame.camera.pose
            val at = probe.hitPose.translation
            // Unit ray: the probe sits 1 m from the camera along it.
            val dx = at[0] - cam.tx()
            val dy = at[1] - cam.ty()
            val dz = at[2] - cam.tz()
            val dist = if (dy < -0.15f) (ASSUMED_CAMERA_HEIGHT_M / -dy).coerceIn(0.3f, Config.MAX_PLACE_M)
            else INSTANT_DISTANCE_M
            val point = floatArrayOf(cam.tx() + dx * dist, cam.ty() + dy * dist, cam.tz() + dz * dist)
            session.createAnchor(Pose(point, UPRIGHT))
        }.onFailure { Log.w(TAG, "estimated placement failed", it) }.getOrNull()

    /**
     * An estimated placement moves onto the real floor as soon as ARCore detects one:
     * a ray from the camera through the creature finds the floor it is actually
     * standing on (the estimate was only a guess of distance along that ray), so it
     * stays where it appears on screen. It then gains plane edges, drops and a
     * steady plane anchor.
     */
    private fun settleOntoPlane(frame: Frame, current: Anchor) {
        val anchorPose = current.pose
        val foot = anchorPose.transformPoint(floatArrayOf(pose.x, 0f, pose.z))
        val cam = frame.camera.pose
        val dx = foot[0] - cam.tx()
        val dy = foot[1] - cam.ty()
        val dz = foot[2] - cam.tz()
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-3f) return
        val hit = frame.hitTest(floatArrayOf(cam.tx(), cam.ty(), cam.tz()), 0, floatArrayOf(dx / len, dy / len, dz / len), 0)
            .firstOrNull { h ->
                val t = h.trackable
                t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(h.hitPose) &&
                    h.distance <= Config.MAX_PLACE_M * 2f
            } ?: return
        val plane = topPlane(hit.trackable as Plane) ?: return
        val settled = uprightAnchor(plane, hit.hitPose.translation) ?: return
        ball = ball?.reframed(anchorPose, settled.pose)
        current.detach()
        anchor = settled
        // The start spot was only an estimate too — drop it onto the real floor.
        homeAnchor?.let { old ->
            val hp = old.pose
            uprightAnchor(plane, floatArrayOf(hp.tx(), plane.centerPose.ty(), hp.tz()))?.let { fixed ->
                old.detach()
                homeAnchor = fixed
                homePlane = plane
            }
        }
        lastAnchorPose = settled.pose
        anchorPlane = plane
        pose = pose.copy(x = 0f, z = 0f)
        walkTarget = null
        Log.i(TAG, "estimated placement settled onto floor (moved %.2f m)".format(
            hypot(hypot(foot[0] - hit.hitPose.tx(), foot[2] - hit.hitPose.tz()), foot[1] - hit.hitPose.ty()),
        ))
    }

    private fun rememberHome(session: Session) {
        val current = anchor ?: return
        homePending = false
        homeAnchor?.detach()
        val plane = anchorPlane
        homeAnchor = (plane?.let { uprightAnchor(it, current.pose.translation) })
            ?: runCatching { session.createAnchor(Pose(current.pose.translation, UPRIGHT)) }.getOrNull()
        homePlane = plane
    }

    /**
     * Back to the start spot: walks there on the same level or one below (dropping
     * off an edge on the way); hops back up to a higher start spot or one out of
     * walking range. Switches to Manual so it stays put.
     */
    private fun walkHome(session: Session, frame: Frame) {
        val current = anchor ?: return
        val home = homeAnchor?.takeIf { it.trackingState != TrackingState.STOPPED }
        if (home == null) {
            hint = "The starting spot was lost — tap where it should go"
            return
        }
        behavior = Behavior.Manual
        val anchorPose = current.pose
        val local = anchorPose.inverse().transformPoint(home.pose.translation)
        val reach = hypot(local[0], local[2])
        if (local[1] < Config.SAME_LEVEL_M && hypot(local[0], local[2]) < Config.MAX_ROAM_M * 0.9f) {
            walkTarget = local[0] to local[2]
            Log.i(TAG, "going home: walking %.2f m".format(reach))
            return
        }
        val hp = home.pose.translation
        val back = homePlane?.let { topPlane(it) }?.takeIf { it.trackingState == TrackingState.TRACKING }
            ?.let { pl -> uprightAnchor(pl, hp)?.let { it to pl } }
            ?: runCatching { session.createAnchor(Pose(hp, UPRIGHT)) to null }.getOrNull()
            ?: return
        Log.i(TAG, "going home: hop %.2f m up, %.2f m away".format(local[1], reach))
        ball = ball?.reframed(anchorPose, back.first.pose)
        current.detach()
        anchorPlane = back.second
        arrive(back.first, frame.camera.displayOrientedPose)
    }

    /** Whether local (x, z) is on [plane] or another plane of the same floor level. */
    private fun onWalkable(plane: Plane, planes: List<Plane>, anchorPose: Pose, x: Float, z: Float): Boolean {
        val foot = anchorPose.compose(Pose.makeTranslation(x, 0f, z))
        return plane.isPoseInPolygon(foot) || planes.any {
            it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                abs(it.centerPose.ty() - foot.ty()) < Config.SAME_LEVEL_M && it.isPoseInPolygon(foot)
        }
    }

    /** Whether a tap ray meets any tracked plane at all (even out of placement range). */
    private fun anyPlaneHit(frame: Frame, x: Float, y: Float) = frame.hitTest(x, y).any { h ->
        val t = h.trackable
        t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(h.hitPose)
    }

    /** The plane that has absorbed [plane], transitively (itself if not merged). */
    private fun topPlane(plane: Plane?): Plane? {
        var p = plane
        while (p?.subsumedBy != null) p = p.subsumedBy
        return p
    }

    /**
     * Local height of the highest walkable surface at or below [y] under (x, z):
     * 0 on the creature's own plane, else the tallest other detected floor/table.
     */
    private fun surfaceUnder(planes: List<Plane>, anchorPose: Pose, x: Float, y: Float, z: Float): Float? {
        val own = anchorPlane
        val w = anchorPose.transformPoint(floatArrayOf(x, 0f, z))
        if (own == null || own.isPoseInPolygon(Pose.makeTranslation(w[0], w[1], w[2]))) return 0f
        var best: Float? = null
        for (pl in planes) {
            if (pl == own || pl.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val py = pl.centerPose.ty()
            val h = py - anchorPose.ty()
            if (h > y + 0.01f || (best != null && h <= best)) continue
            if (pl.isPoseInPolygon(Pose.makeTranslation(w[0], py, w[2]))) best = h
        }
        return best
    }

    /** This ball re-expressed in another anchor's frame (same place in the world). */
    private fun Ball.reframed(from: Pose, to: Pose): Ball {
        val inv = to.inverse()
        val pos = inv.transformPoint(from.transformPoint(floatArrayOf(x, y, z)))
        val vel = inv.rotateVector(from.rotateVector(floatArrayOf(vx, vy, vz)))
        return copy(x = pos[0], y = pos[1], z = pos[2], vx = vel[0], vy = vel[1], vz = vel[2])
    }

    private fun wallAhead(frame: Frame, anchorPose: Pose, from: DragonPose, to: DragonPose, size: Float): Boolean {
        val mx = to.x - from.x
        val mz = to.z - from.z
        val d = hypot(mx, mz)
        val origin = anchorPose.transformPoint(floatArrayOf(from.x, from.y + size * 0.4f, from.z))
        val dir = anchorPose.rotateVector(floatArrayOf(mx / d, 0f, mz / d))
        return frame.hitTest(origin, 0, dir, 0).any { h ->
            val t = h.trackable
            h.distance <= d + size * 0.5f && t is Plane && t.type == Plane.Type.VERTICAL &&
                t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(h.hitPose)
        }
    }

    private fun tapHitsCreature(frame: Frame, anchorPose: Pose, tx: Float, ty: Float, w: Int, h: Int): Boolean {
        val spot = project(frame, anchorPose, w, h) ?: return false
        return spot.inFront && hypot(tx - spot.x, ty - spot.y) <= maxOf(spot.radiusPx, 80f)
    }

    private fun project(frame: Frame, anchorPose: Pose, w: Int, h: Int): ScreenSpot? {
        if (w == 0 || h == 0) return null
        val p = pose
        val c = anchorPose.transformPoint(floatArrayOf(p.x, p.y + sizeM * 0.5f, p.z))
        frame.camera.getViewMatrix(viewM, 0)
        frame.camera.getProjectionMatrix(projM, 0, 0.01f, 100f)
        Matrix.multiplyMM(vpM, 0, projM, 0, viewM, 0)
        clipV[0] = c[0]; clipV[1] = c[1]; clipV[2] = c[2]; clipV[3] = 1f
        Matrix.multiplyMV(outV, 0, vpM, 0, clipV, 0)
        val cw = outV[3]
        val inFront = cw > 0f
        val sw = if (inFront) cw else -cw.coerceAtMost(-1e-4f)
        val sx = (outV[0] / sw + 1f) / 2f * w
        val sy = (1f - outV[1] / sw) / 2f * h
        val visible = inFront && sx in 0f..w.toFloat() && sy in 0f..h.toFloat()
        val radius = if (inFront) (sizeM * 0.7f / cw) * projM[5] * h / 2f else 0f
        return ScreenSpot(sx, sy, visible, inFront, radius)
    }

    private fun trackingHint(reason: TrackingFailureReason?): String = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — find more light"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast — slow down"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a textured surface"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> "Starting AR — move your phone slowly"
    }

    private companion object {
        const val TAG = "CreatureSim"
        const val HAPPINESS_DRAIN_PER_S = 1f / 600f
        val DOWN = floatArrayOf(0f, -1f, 0f)
        val UPRIGHT = floatArrayOf(0f, 0f, 0f, 1f)
        /** Anchor corrections up to this size glide; bigger ones are real relocations (m). */
        const val MAX_GLIDE_M = 2f
        /** Glide-out rate for [renderOffset] (per s) — ~0.4 s. */
        const val GLIDE_RATE = 7f
        /** A tracked image this close to a plane's height is lying on it (m). */
        const val IMAGE_ON_PLANE_M = 0.05f
        /** Estimated placement when aiming level or up (no floor below the ray), m. */
        const val INSTANT_DISTANCE_M = 1.2f
        /** Typical phone height above the floor when held — the no-plane floor guess (m). */
        const val ASSUMED_CAMERA_HEIGHT_M = 1.3f
        val REACTION_NAMES = listOf("yes", "hitreact", "jump", "happy", "headbutt", "attack", "punch")
    }
}
