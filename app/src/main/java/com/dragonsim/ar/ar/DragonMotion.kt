package com.dragonsim.ar.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Creature pose in its anchor's frame. The anchor is gravity-aligned (+Y = world
 * up), so XZ is the horizontal plane and Y is height.
 *
 * @param x        metres along anchor +X
 * @param z        metres along anchor +Z
 * @param yawDeg   facing direction in degrees; 0 = +Z, 90 = +X (see [DragonMotion.step])
 * @param pitchDeg nose-down while moving (negative = dips toward travel direction)
 * @param rollDeg  bank while turning (positive = right wing lifts)
 * @param velX     smoothed X velocity, m/s — carried between frames so the dragon
 * @param velZ     glides in/out instead of popping between still and full speed
 */
data class DragonPose(
    val x: Float = 0f,
    val z: Float = 0f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val velX: Float = 0f,
    val velZ: Float = 0f,
    /** Height of the feet above the anchor origin, metres. */
    val y: Float = 0f,
    /** Vertical velocity, m/s (positive = up). */
    val velY: Float = 0f,
    val grounded: Boolean = true,
)

/**
 * Pure motion/animation logic — no Android dependencies, unit-testable on the JVM.
 */
object DragonMotion {

    /** Joystick magnitudes below this count as "released". */
    const val INPUT_DEADZONE = 0.15f

    /** Yaw easing rate (per second); higher = snappier turns. */
    private const val YAW_SMOOTH_RATE = 12f

    /** Velocity ease rate (per second); ~0.1 s to reach target speed. */
    private const val ACCEL_RATE = 10f

    /** Roll applied per degree/second of yaw rate — banking into turns. */
    private const val BANK_PER_DEG_S = 0.045f
    private const val MAX_BANK_DEG = 22f

    /** Slight nose-down at full stick. */
    private const val MAX_PITCH_DEG = 10f

    /** Below this, residual values snap to zero so a parked dragon rests exactly. */
    private const val SETTLE_EPS = 0.01f

    /** Idle turn-to-face runs at a fraction of the travel turn rate. */
    private const val FACE_RATE_SCALE = 0.25f

    /**
     * @param input (x, y) joystick output in screen convention — y is negative when the
     *              stick is pushed up. Pushing up maps to -z on the image ("up" the image).
     */
    fun isMoving(input: Pair<Float, Float>): Boolean {
        val (x, y) = input
        return sqrt(x * x + y * y) > INPUT_DEADZONE
    }

    /**
     * Integrate one frame of joystick input into a new pose.
     *
     * Velocity eases toward stick direction × [speedMps] (glide in/out); position is
     * clamped inside a circle of [maxRadius] around the image centre and velocity is
     * recomputed from actual displacement so the dragon doesn't push at the boundary.
     * Yaw eases toward the travel direction while input is held — a flyer keeps its
     * heading during a glide-out. Roll banks into turns, pitch dips with speed.
     */
    fun step(
        pose: DragonPose,
        input: Pair<Float, Float>,
        dtSeconds: Float,
        speedMps: Float,
        maxRadius: Float,
        /** When idle, slowly turn to face this yaw (e.g. toward the camera). */
        faceYawDeg: Float? = null,
        maxPitchDeg: Float = MAX_PITCH_DEG,
    ): DragonPose {
        if (dtSeconds <= 0f) return pose

        var ix = input.first
        var iz = input.second
        val mag = sqrt(ix * ix + iz * iz)
        if (mag > 1f) {
            ix /= mag
            iz /= mag
        }
        val active = mag > INPUT_DEADZONE

        val targetVx = if (active) ix * speedMps else 0f
        val targetVz = if (active) iz * speedMps else 0f
        val blend = 1f - exp(-ACCEL_RATE * dtSeconds)
        var vx = settle(pose.velX + (targetVx - pose.velX) * blend)
        var vz = settle(pose.velZ + (targetVz - pose.velZ) * blend)

        var nx = pose.x + vx * dtSeconds
        var nz = pose.z + vz * dtSeconds
        val radius = sqrt(nx * nx + nz * nz)
        if (radius > maxRadius) {
            val k = maxRadius / radius
            nx *= k
            nz *= k
            vx = settle((nx - pose.x) / dtSeconds)
            vz = settle((nz - pose.z) / dtSeconds)
        }

        var yawDeg = pose.yawDeg
        var yawRateDegS = 0f
        if (active) {
            val targetYawDeg = Math.toDegrees(atan2(ix.toDouble(), iz.toDouble())).toFloat()
            val newYaw = approachYaw(pose.yawDeg, targetYawDeg, dtSeconds)
            yawRateDegS = wrapDeg(newYaw - pose.yawDeg) / dtSeconds
            yawDeg = newYaw
        } else if (faceYawDeg != null && vx == 0f && vz == 0f) {
            yawDeg = approachYaw(pose.yawDeg, faceYawDeg, dtSeconds * FACE_RATE_SCALE)
        }

        val speedNorm = (sqrt(vx * vx + vz * vz) / speedMps).coerceIn(0f, 1f)
        val rollTarget = (-yawRateDegS * BANK_PER_DEG_S).coerceIn(-MAX_BANK_DEG, MAX_BANK_DEG)
        val pitchTarget = -speedNorm * maxPitchDeg
        val rollDeg = settle(pose.rollDeg + (rollTarget - pose.rollDeg) * blend)
        val pitchDeg = settle(pose.pitchDeg + (pitchTarget - pose.pitchDeg) * blend)

        return pose.copy(
            x = nx, z = nz, yawDeg = yawDeg, pitchDeg = pitchDeg, rollDeg = rollDeg,
            velX = vx, velZ = vz,
        )
    }

    /** Exponential shortest-arc approach of [targetDeg] from [currentDeg]. */
    private fun approachYaw(currentDeg: Float, targetDeg: Float, dtSeconds: Float): Float {
        val delta = wrapDeg(targetDeg - currentDeg)
        val t = 1f - exp(-YAW_SMOOTH_RATE * dtSeconds)
        return wrapDeg(currentDeg + delta * t)
    }

    private fun settle(v: Float) = if (abs(v) < SETTLE_EPS) 0f else v

    /** Wraps degrees into (-180, 180]. */
    internal fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Steering input (same convention as a stick, magnitude 0..1) that walks from
     * (fromX, fromZ) toward (toX, toZ), easing off inside [arriveRadius] and
     * returning zero within [stopRadius].
     */
    fun seek(
        fromX: Float, fromZ: Float, toX: Float, toZ: Float,
        arriveRadius: Float, stopRadius: Float,
    ): Pair<Float, Float> {
        val dx = toX - fromX
        val dz = toZ - fromZ
        val d = sqrt(dx * dx + dz * dz)
        if (d <= stopRadius || d < 1e-5f) return 0f to 0f
        val mag = ((d - stopRadius) / arriveRadius).coerceIn(INPUT_DEADZONE + 0.05f, 1f)
        return (dx / d * mag) to (dz / d * mag)
    }

    /** Yaw (degrees, [step] convention: 0 = +Z, 90 = +X) looking from a point toward another. */
    fun yawToward(fromX: Float, fromZ: Float, toX: Float, toZ: Float): Float =
        Math.toDegrees(atan2((toX - fromX).toDouble(), (toZ - fromZ).toDouble())).toFloat()

    /**
     * Rotates a screen-space stick (x right, y down) into image-local (x, z) so
     * "push up" moves the creature away from the viewer along the screen's up
     * direction, however the phone is held around the image.
     *
     * [upX]/[upZ] = the camera's screen-up axis projected onto the image plane
     * (image-local). Using screen-up rather than view-forward keeps this valid
     * both looking down at a table print and facing a vertical monitor. When the
     * projection degenerates, the stick passes through unchanged.
     */
    fun cameraRelative(stick: Pair<Float, Float>, upX: Float, upZ: Float): Pair<Float, Float> {
        val len = sqrt(upX * upX + upZ * upZ)
        if (len < 1e-3f) return stick
        val fx = upX / len
        val fz = upZ / len
        // right = forward rotated -90° on the plane; identity when forward = (0, -1).
        val rx = -fz
        val rz = fx
        val (sx, sy) = stick
        return (sx * rx - sy * fx) to (sx * rz - sy * fz)
    }
}
