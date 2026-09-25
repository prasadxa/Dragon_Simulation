package com.dragonsim.ar.ar

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** A thrown ball in the anchor frame (+Y up). */
data class Ball(
    val x: Float,
    val y: Float,
    val z: Float,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val resting: Boolean = false,
    val ageS: Float = 0f,
)

/** Result of one vertical step. [impactSpeed] > 0 on the frame the creature lands. */
data class VerticalStep(val y: Float, val velY: Float, val grounded: Boolean, val impactSpeed: Float = 0f)

/**
 * Pure physics — real gravity in metres, no Android dependencies.
 * Ground heights come from AR hit tests (planes / depth) done by the caller.
 */
object Physics {
    const val GRAVITY = 9.81f
    const val BALL_RESTITUTION = 0.45f
    private const val BALL_GROUND_FRICTION = 3f
    private const val REST_SPEED = 0.05f
    private const val HOVER_RATE = 4f
    private const val CONTACT_EPS = 0.002f

    /**
     * Walking: falls under gravity when above [groundY] (e.g. stepped off a table
     * edge) and lands on it; a [jumpSpeed] > 0 launches while grounded.
     * Flying: eases toward [hoverY] with gravity off.
     */
    fun stepVertical(
        y: Float,
        velY: Float,
        grounded: Boolean,
        groundY: Float,
        dtSeconds: Float,
        flying: Boolean = false,
        hoverY: Float = groundY,
        jumpSpeed: Float = 0f,
    ): VerticalStep {
        if (dtSeconds <= 0f) return VerticalStep(y, velY, grounded)
        if (flying) {
            val target = maxOf(hoverY, groundY)
            val ny = y + (target - y) * (1f - exp(-HOVER_RATE * dtSeconds))
            return VerticalStep(ny, (ny - y) / dtSeconds, grounded = false)
        }
        var vy = velY
        if (grounded && jumpSpeed > 0f) vy = jumpSpeed
        val airborne = vy > 0f || y > groundY + CONTACT_EPS
        if (!airborne) return VerticalStep(groundY, 0f, grounded = true)
        vy -= GRAVITY * dtSeconds
        val ny = y + vy * dtSeconds
        if (ny <= groundY) return VerticalStep(groundY, 0f, grounded = true, impactSpeed = -vy)
        return VerticalStep(ny, vy, grounded = false)
    }

    /** Gravity, bounce on [groundY] with restitution, and rolling friction. */
    fun stepBall(ball: Ball, groundY: Float, dtSeconds: Float): Ball {
        if (dtSeconds <= 0f) return ball
        val age = ball.ageS + dtSeconds
        if (ball.resting) return ball.copy(y = groundY, ageS = age)
        var vx = ball.vx
        var vy = ball.vy - GRAVITY * dtSeconds
        var vz = ball.vz
        var x = ball.x + vx * dtSeconds
        var y = ball.y + vy * dtSeconds
        val z = ball.z + vz * dtSeconds
        val onGround = y <= groundY
        if (onGround) {
            y = groundY
            vy = if (abs(vy) * BALL_RESTITUTION > REST_SPEED * 4) -vy * BALL_RESTITUTION else 0f
            val f = exp(-BALL_GROUND_FRICTION * dtSeconds)
            vx *= f
            vz *= f
        }
        val resting = onGround && vy == 0f && sqrt(vx * vx + vz * vz) < REST_SPEED
        return Ball(x, y, z, vx, vy, vz, resting, age)
    }
}
