package com.dragonsim.ar.ar

import android.opengl.Matrix
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * anchored with `Plane.createAnchor` — the only anchor type that proved steady
 * on-device. No depth-point / instant-placement anchors.
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
    private var jumpRequested = false
    private var throwRequested = false

    fun tap(x: Float, y: Float) { pendingTap = x to y }
    fun placeInFront() { placeRequested = true }
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
        anchor?.detach()
        anchor = null
        anchorPlane = null
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
            trySpawn(frame, planes, image, viewW, viewH)
            return
        }
        if (current.trackingState == TrackingState.STOPPED) {
            reset(requireImageLoss = false)
            return
        }
        // PAUSED keeps the last pose (SceneView sample finding #1435) — keep simulating.
        hint = null
        step(frame, current.pose, dt, viewW, viewH)
    }

    private fun trySpawn(frame: Frame, planes: List<Plane>, image: AugmentedImage?, w: Int, h: Int) {
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
                val placed = when {
                    tap != null -> {
                        val floor = planeHit(frame, tap.first, tap.second)
                        when {
                            floor != null -> {
                                val plane = floor.trackable as Plane
                                plane.createAnchor(Pose(floor.hitPose.translation, uprightRotation(plane))) to plane
                            }
                            wallHit(frame, tap.first, tap.second) != null -> {
                                hint = "That's a wall — tap the floor or a table"
                                return
                            }
                            else -> {
                                hint = "No surface there — aim at the floor or a table"
                                return
                            }
                        }
                    }
                    (placeRequested || Config.AUTO_PLACE) && candidate != null ->
                        candidate.createAnchor()?.let { it.anchor to it.plane }
                    else -> null
                }
                placeRequested = false
                if (placed == null) {
                    hint = if (floors.isEmpty()) "Move your phone slowly to find the floor or a table"
                    else "Point at the floor or a table, then tap it"
                    return
                }
                anchorPlane = placed.second
                arrive(placed.first, camPose)
            }
            PlacementMode.Image -> {
                if (!(imageTracked && spawnAllowed && image != null)) {
                    hint = "Point the camera at the target image"
                    return
                }
                anchorPlane = null
                arrive(image.createAnchor(Pose.makeTranslation(image.centerPose.translation)), camPose)
            }
        }
    }

    private fun arrive(newAnchor: Anchor, camPose: Pose) {
        anchor = newAnchor
        spawnAllowed = false
        spawnProgress = 0f
        val rel = newAnchor.pose.inverse().compose(camPose)
        pose = DragonPose(yawDeg = DragonMotion.yawToward(0f, 0f, rel.tx(), rel.tz()))
        hoverY = if (flying) sizeM * 0.6f else 0f
        walkTarget = null
        hint = null
        hapticTick++
    }

    private fun step(frame: Frame, anchorPose: Pose, dt: Float, w: Int, h: Int) {
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
        val speed = size * Config.BODIES_PER_S * if (flying) 1.6f else 1f
        var p = pose

        pendingTap?.let { (tx, ty) ->
            pendingTap = null
            if (tapHitsCreature(frame, anchorPose, tx, ty, w, h)) {
                react()
            } else {
                planeHit(frame, tx, ty)?.let { hit ->
                    val plane = hit.trackable as Plane
                    if (anchorPlane == null || plane == anchorPlane || plane.subsumedBy == anchorPlane) {
                        val local = inv.transformPoint(hit.hitPose.translation)
                        // Clamp into the roam circle so the target is always reachable —
                        // an out-of-range tap used to pin the creature pushing at the
                        // boundary forever (residual velocity, never arriving).
                        val r = hypot(local[0], local[2])
                        val cap = Config.MAX_ROAM_M * 0.9f
                        walkTarget = if (r > cap) (local[0] * cap / r) to (local[2] * cap / r)
                            else local[0] to local[2]
                    } else {
                        // Another surface: hop over there (re-anchor on that plane).
                        anchor?.detach()
                        anchorPlane = plane
                        arrive(
                            plane.createAnchor(Pose(hit.hitPose.translation, uprightRotation(plane))),
                            frame.camera.displayOrientedPose,
                        )
                        return
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
        }

        // ── Steering: stick > tap target > ball > behaviour ─────────────────
        val stick = joystick
        var seeking = false
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
            ball?.let { (it.resting || it.y < size * 0.3f) && hypot(it.x, it.z) < Config.MAX_ROAM_M * 0.95f } == true -> {
                val b = ball!!
                if (hypot(b.x - p.x, b.z - p.z) < size * 0.45f) {
                    ball = null
                    react("😋", 0.15f)
                    0f to 0f
                } else {
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
        if (seeking && !moved) walkTarget = null // unreachable target — stop pushing
        val plane = anchorPlane
        if (moved && !flying && plane != null) {
            val footWorld = anchorPose.compose(Pose.makeTranslation(p.x, 0f, p.z))
            if (!plane.isPoseInPolygon(footWorld)) {
                val lower = planeBelow(frame, footWorld)
                if (lower != null && p.grounded) {
                    // Walk off the edge: re-anchor on the surface below, fall the difference.
                    val lowerPlane = lower.trackable as Plane
                    val drop = footWorld.ty() - lower.hitPose.ty()
                    anchor?.detach()
                    anchorPlane = lowerPlane
                    anchor = lowerPlane.createAnchor(Pose(lower.hitPose.translation, uprightRotation(lowerPlane)))
                    pose = p.copy(x = 0f, z = 0f, y = drop, velY = 0f, grounded = false)
                    walkTarget = null
                    return
                }
                p = p.copy(x = prev.x, z = prev.z, velX = 0f, velZ = 0f)
                walkTarget = null
            }
        }
        if (moved && wallAhead(frame, anchorPose, prev, p, size)) {
            p = p.copy(x = prev.x, z = prev.z, velX = 0f, velZ = 0f)
            walkTarget = null
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

        // ── Ball: lands on the creature's surface; off the edge it falls away.
        ball?.let { b ->
            val onSurface = plane == null ||
                plane.isPoseInPolygon(anchorPose.compose(Pose.makeTranslation(b.x, 0f, b.z)))
            val nb = Physics.stepBall(b, if (onSurface) 0f else -10f, dt)
            ball = if (nb.ageS > 25f || nb.y < -3f) null else nb
        }

        if (!(p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && p.yawDeg.isFinite())) p = prev.copy(velY = 0f)
        happiness = (happiness - dt * HAPPINESS_DRAIN_PER_S).coerceAtLeast(0f)
        if (spawnProgress < 1f) spawnProgress = (spawnProgress + dt / 0.45f).coerceAtMost(1f)
        pose = p

        project(frame, anchorPose, w, h)?.let { screen = it }
        if (frameCount % 10 == 0) {
            val wp = anchorPose.transformPoint(floatArrayOf(p.x, p.y, p.z))
            creatureMarker = wp[0] to wp[2]
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
        val pick = c.actions.firstOrNull { i ->
            REACTION_NAMES.any { it in (clipNames.getOrNull(i)?.lowercase() ?: "") }
        } ?: c.actions.firstOrNull()
        if (pick != null && b != null) b.playAction(pick) else jumpRequested = true
    }

    // ── Map / scan ───────────────────────────────────────────────────────────

    private fun refreshMap(planes: List<Plane>, frame: Frame) {
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

    /** Anchor rotation: world-aligned on floors/tables (keeps +Y = up), plane's own on walls. */
    private fun uprightRotation(plane: Plane): FloatArray =
        if (plane.type == Plane.Type.VERTICAL) plane.centerPose.rotationQuaternion else floatArrayOf(0f, 0f, 0f, 1f)

    private fun wallAhead(frame: Frame, anchorPose: Pose, from: DragonPose, to: DragonPose, size: Float): Boolean {
        val mx = to.x - from.x
        val mz = to.z - from.z
        val d = hypot(mx, mz)
        val origin = anchorPose.transformPoint(floatArrayOf(from.x, from.y + size * 0.4f, from.z))
        val dir = anchorPose.rotateVector(floatArrayOf(mx / d, 0f, mz / d))
        return frame.hitTest(origin, 0, dir, 0).any { h ->
            val t = h.trackable
            h.distance <= d + size * 0.5f && t is Plane && t.type == Plane.Type.VERTICAL &&
                t.isPoseInPolygon(h.hitPose)
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
        val REACTION_NAMES = listOf("yes", "hitreact", "jump", "happy", "headbutt", "attack", "punch")
    }
}
