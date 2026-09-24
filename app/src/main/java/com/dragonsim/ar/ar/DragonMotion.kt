package com.dragonsim.ar.ar

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
 * @param x      metres right of image centre
 * @param z      metres down from image centre (so "up the image" is -z)
 * @param yawDeg facing direction in degrees; 0 = +Z, 90 = +X (see [DragonMotion.step])
 */
data class DragonPose(
    val x: Float = 0f,
    val z: Float = 0f,
    val yawDeg: Float = 0f,
)

/**
 * Pure motion/animation logic — no Android dependencies, unit-testable on the JVM.
 */
object DragonMotion {

    /** Joystick magnitudes below this count as "released". */
    const val INPUT_DEADZONE = 0.15f

    /** Yaw easing rate (per second); higher = snappier turns. */
    private const val YAW_SMOOTH_RATE = 12f

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
     * - Velocity = input clamped to unit length × [speedMps]; x→x, y→z.
     * - Position is clamped inside a circle of [maxRadius] around the image centre.
     * - Yaw eases toward the travel direction: `atan2(dx, dz)` in degrees.
     */
    fun step(
        pose: DragonPose,
        input: Pair<Float, Float>,
        dtSeconds: Float,
        speedMps: Float,
        maxRadius: Float,
    ): DragonPose {
        if (dtSeconds <= 0f || !isMoving(input)) return pose

        var vx = input.first
        var vz = input.second
        val mag = sqrt(vx * vx + vz * vz)
        if (mag > 1f) {
            vx /= mag
            vz /= mag
        }

        var nx = pose.x + vx * speedMps * dtSeconds
        var nz = pose.z + vz * speedMps * dtSeconds

        val radius = sqrt(nx * nx + nz * nz)
        if (radius > maxRadius) {
            val k = maxRadius / radius
            nx *= k
            nz *= k
        }

        val targetYawDeg = Math.toDegrees(atan2(vx, vz).toDouble()).toFloat()
        val yawDeg = approachYaw(pose.yawDeg, targetYawDeg, dtSeconds)

        return DragonPose(nx, nz, yawDeg)
    }

    /** Exponential shortest-arc approach of [targetDeg] from [currentDeg]. */
    private fun approachYaw(currentDeg: Float, targetDeg: Float, dtSeconds: Float): Float {
        val delta = wrapDeg(targetDeg - currentDeg)
        val t = 1f - exp(-YAW_SMOOTH_RATE * dtSeconds)
        return wrapDeg(currentDeg + delta * t)
    }

    /** Wraps degrees into (-180, 180]. */
    internal fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Resolve a GLB animation name for [kind]. Case-insensitive:
     * Idle prefers names containing "idle"; Walk prefers "walk"|"run", then a
     * "fly"/"move" clip that isn't the idle pick (e.g. a clip named
     * "Flying_Idle" contains "fly" but must never be chosen as Walk). Falls back
     * to the other family, then index 0. Returns null for an empty list.
     */
    fun pickClip(names: List<String>, kind: ClipKind): String? {
        if (names.isEmpty()) return null

        fun find(vararg needles: String): String? =
            names.firstOrNull { name -> needles.any { name.lowercase().contains(it) } }

        val idle = find("idle")
        return when (kind) {
            ClipKind.Idle -> idle ?: find("walk", "run", "fly", "move") ?: names[0]
            ClipKind.Walk ->
                find("walk", "run")
                    ?: names.firstOrNull {
                        val n = it.lowercase()
                        (n.contains("fly") || n.contains("move")) && it != idle
                    }
                    ?: names.firstOrNull { it != idle }
                    ?: names[0]
        }
    }
}
