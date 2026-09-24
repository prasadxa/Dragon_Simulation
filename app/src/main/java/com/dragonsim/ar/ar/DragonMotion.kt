package com.dragonsim.ar.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Which animation clip to request from the model. Names in the GLB are unknown until
 * load time, so [DragonMotion.pickClip] resolves them heuristically.
 */
enum class ClipKind { Idle, Walk }

/**
 * Dragon pose in the tracked image's local frame.
 *
 * Image-local axes (ARCore AugmentedImage): +X = left→right across the image,
 * +Y = out of the image face, +Z = top→bottom. The dragon moves on the XZ plane.
 *
 * @param x        metres right of image centre
 * @param z        metres down from image centre (so "up the image" is -z)
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
        }

        val speedNorm = (sqrt(vx * vx + vz * vz) / speedMps).coerceIn(0f, 1f)
        val rollTarget = (-yawRateDegS * BANK_PER_DEG_S).coerceIn(-MAX_BANK_DEG, MAX_BANK_DEG)
        val pitchTarget = -speedNorm * MAX_PITCH_DEG
        val rollDeg = settle(pose.rollDeg + (rollTarget - pose.rollDeg) * blend)
        val pitchDeg = settle(pose.pitchDeg + (pitchTarget - pose.pitchDeg) * blend)

        return DragonPose(nx, nz, yawDeg, pitchDeg, rollDeg, vx, vz)
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
     * Resolve a GLB animation name for [kind]. Case-insensitive.
     * Idle prefers names containing "idle", else index 0.
     * Walk only matches literal "walk"/"run" — when a model can't walk (flying
     * creatures), the caller keeps the idle clip and modulates playback speed
     * instead of hard-cutting to an unrelated pose.
     */
    fun pickClip(names: List<String>, kind: ClipKind): String? {
        if (names.isEmpty()) return null

        fun find(vararg needles: String): String? =
            names.firstOrNull { name -> needles.any { name.lowercase().contains(it) } }

        return when (kind) {
            ClipKind.Idle -> find("idle") ?: names[0]
            ClipKind.Walk -> find("walk", "run")
        }
    }
}
