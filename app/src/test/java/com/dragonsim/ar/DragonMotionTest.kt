package com.dragonsim.ar

import com.dragonsim.ar.ar.ClipBlender
import com.dragonsim.ar.ar.Ball
import com.dragonsim.ar.ar.ClipSet
import com.dragonsim.ar.ar.Physics
import com.dragonsim.ar.ar.DragonMotion
import com.dragonsim.ar.ar.DragonPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class DragonMotionTest {

    private fun radius(p: DragonPose) = sqrt(p.x * p.x + p.z * p.z)

    @Test
    fun `position never exceeds clamp radius`() {
        var pose = DragonPose()
        repeat(600) {
            pose = DragonMotion.step(pose, 1f to 1f, dtSeconds = 1f / 60f, speedMps = 1f, maxRadius = 0.3f)
            assertTrue("radius=${radius(pose)}", radius(pose) <= 0.3f + 1e-4f)
        }
    }

    @Test
    fun `zero input keeps position and yaw`() {
        val pose = DragonPose(x = 0.05f, z = -0.02f, yawDeg = 42f)
        val next = DragonMotion.step(pose, 0f to 0f, dtSeconds = 0.016f, speedMps = 0.1f, maxRadius = 0.3f)
        assertEquals(pose, next)
    }

    @Test
    fun `input inside deadzone keeps position`() {
        val pose = DragonPose(x = 0.01f, z = 0.01f)
        val next = DragonMotion.step(pose, 0.1f to 0.1f, dtSeconds = 0.016f, speedMps = 0.1f, maxRadius = 0.3f)
        assertEquals(pose, next)
    }

    @Test
    fun `yaw converges on movement direction`() {
        var pose = DragonPose()
        // Pushing right (+x) should converge to yaw = atan2(1, 0) = +90 degrees.
        repeat(240) {
            pose = DragonMotion.step(pose, 1f to 0f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
        }
        assertTrue("yaw=${pose.yawDeg}", abs(DragonMotion.wrapDeg(90f - pose.yawDeg)) < 2f)
        assertTrue(pose.x > 0f)

        // Pushing down (+y on the stick) moves +z → yaw = atan2(0, 1) = 0 degrees.
        pose = DragonPose()
        repeat(240) {
            pose = DragonMotion.step(pose, 0f to 1f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
        }
        assertTrue("yaw=${pose.yawDeg}", abs(DragonMotion.wrapDeg(0f - pose.yawDeg)) < 2f)
        assertTrue(pose.z > 0f)
    }

    @Test
    fun `cameraRelative is identity when screen-up is image minus z`() {
        val out = DragonMotion.cameraRelative(0.3f to -0.7f, upX = 0f, upZ = -1f)
        assertEquals(0.3f, out.first, 1e-5f)
        assertEquals(-0.7f, out.second, 1e-5f)
    }

    @Test
    fun `cameraRelative pushes up along screen-up when viewed from the side`() {
        // Viewer stands at the image's -x side: screen-up points along image +x.
        val out = DragonMotion.cameraRelative(0f to -1f, upX = 1f, upZ = 0f)
        assertEquals(1f, out.first, 1e-5f)
        assertEquals(0f, out.second, 1e-5f)
    }

    @Test
    fun `cameraRelative passes stick through when projection degenerates`() {
        assertEquals(0.5f to 0.5f, DragonMotion.cameraRelative(0.5f to 0.5f, 0f, 0f))
    }

    @Test
    fun `released stick glides to a full stop`() {
        var pose = DragonPose()
        // Build up speed.
        repeat(120) {
            pose = DragonMotion.step(pose, 1f to 0f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
        }
        assertTrue(pose.velX > 0.05f)
        // Release — velocity must decay to an exact rest, not freeze mid-flight.
        repeat(120) {
            pose = DragonMotion.step(pose, 0f to 0f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
        }
        assertEquals(0f, pose.velX, 1e-6f)
        assertEquals(0f, pose.velZ, 1e-6f)
        assertEquals(0f, pose.pitchDeg, 1e-6f)
        assertEquals(0f, pose.rollDeg, 1e-6f)
        // A parked dragon produces an equal pose — no per-frame recomposition churn.
        assertEquals(pose, DragonMotion.step(pose, 0f to 0f, 1f / 60f, 0.1f, 0.3f))
    }

    @Test
    fun `dragon banks while turning and levels out`() {
        var pose = DragonPose()
        // First get it flying straight so a direction change produces yaw rate.
        repeat(240) {
            pose = DragonMotion.step(pose, 1f to 0f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
        }
        // Snap the stick to the opposite-ish direction — peak bank mid-turn.
        var peakRoll = 0f
        repeat(60) {
            pose = DragonMotion.step(pose, -1f to 0f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
            peakRoll = maxOf(peakRoll, abs(pose.rollDeg))
        }
        assertTrue("peakRoll=$peakRoll", peakRoll > 1f)
        assertTrue("peakRoll=$peakRoll", peakRoll <= 22f)
    }

    @Test
    fun `pitch dips with speed and never exceeds limit`() {
        var pose = DragonPose()
        var peakPitch = 0f
        repeat(240) {
            pose = DragonMotion.step(pose, 1f to 0f, dtSeconds = 1f / 60f, speedMps = 0.1f, maxRadius = 0.3f)
            peakPitch = maxOf(peakPitch, abs(pose.pitchDeg))
        }
        assertTrue("peakPitch=$peakPitch", peakPitch > 1f)
        assertTrue("peakPitch=$peakPitch", peakPitch <= 10f)
    }


    // ── ClipSet ─────────────────────────────────────────────────────────────

    @Test
    fun `ClipSet on the shipped flying dragon`() {
        val names = listOf(
            "CharacterArmature|Death", "CharacterArmature|Fast_Flying", "CharacterArmature|Flying_Idle",
            "CharacterArmature|Headbutt", "CharacterArmature|Yes",
        )
        val c = ClipSet.resolve(names)!!
        assertEquals(2, c.idle)
        assertEquals(2, c.flyIdle)
        assertEquals(1, c.fly)
        assertNull(c.walk)
        assertTrue(c.canFly && !c.canWalk)
        assertEquals(listOf(0, 3, 4), c.actions)
    }

    @Test
    fun `ClipSet on the classic dragon has walk run and fly`() {
        val c = ClipSet.resolve(listOf("Flying-loop", "Idle-loop", "Run-loop", "Walk-loop"))!!
        assertEquals(1, c.idle)
        assertEquals(3, c.walk)
        assertEquals(2, c.run)
        assertEquals(0, c.fly)
        assertNull(c.flyIdle)
        assertTrue(c.actions.isEmpty())
    }

    @Test
    fun `ClipSet on the Tarisland dark dragon has idle walk and fly`() {
        // Verbatim clip names shipped in dragon_dark.glb.
        val names = listOf(
            "Attack_1", "Attack_2", "Death", "Down", "Down_2", "Down_3", "Fly",
            "skill02", "skill05", "skill06", "skill06_dz", "skill07", "skill08",
            "skill09", "skill10", "skill10_dz", "skill11",
            "Idle", "Turn_Left", "Turn_Right", "Up", "Up_2", "Up_3", "Walk",
        )
        val c = ClipSet.resolve(names)!!
        assertEquals(17, c.idle)
        assertEquals(23, c.walk)
        assertEquals(6, c.fly)
        assertNull(c.run)
        assertNull(c.flyIdle)
        assertTrue(c.canWalk && c.canFly) // walker + flyer — the fly/land toggle shows
        assertEquals(21, c.actions.size)
    }

    @Test
    fun `ClipSet on the fox uses Survey as idle`() {
        val c = ClipSet.resolve(listOf("Survey", "Walk", "Run"))!!
        assertEquals(0, c.idle)
        assertEquals(1, c.walk)
        assertEquals(2, c.run)
        assertTrue(!c.canFly)
    }

    @Test
    fun `ClipSet promotes a lone run to walk and handles empty`() {
        val c = ClipSet.resolve(listOf("Idle", "RunFast", "Attack"))!!
        assertEquals(1, c.walk)
        assertNull(c.run)
        assertEquals(listOf(2), c.actions)
        assertNull(ClipSet.resolve(emptyList()))
    }

    @Test
    fun `ClipBlender eases weight toward speed and back to exact zero`() {
        val b = ClipBlender(ClipSet(idle = 0, walk = 1), floatArrayOf(1f, 0.5f))
        repeat(60) { b.advance(1f / 60f, 1f) }
        assertEquals(1f, b.weight, 1e-6f)
        repeat(60) { b.advance(1f / 60f, 0f) }
        assertEquals(0f, b.weight, 0f)
        assertTrue(b.idleTime in 0f..1f && b.locoPhase in 0f..1f)
    }

    @Test
    fun `ClipBlender one-shot action ramps in and expires`() {
        val b = ClipBlender(ClipSet(idle = 0, actions = listOf(1)), floatArrayOf(1f, 1f))
        b.playAction(1)
        b.advance(0.5f / 60f * 60f, 0f) // 0.1 s max dt clamp → mid-ramp
        assertTrue(b.actionWeight > 0f)
        repeat(20) { b.advance(0.1f, 0f) }
        assertNull(b.actionIndex)
        assertEquals(0f, b.actionWeight, 0f)
    }

    // ── Physics ─────────────────────────────────────────────────────────────

    @Test
    fun `walking off a table falls under gravity and lands on the floor`() {
        // Table top at 0, floor 0.75 m below.
        var y = 0f
        var vy = 0f
        var grounded = true
        var t = 0f
        var impact = 0f
        while (t < 2f) {
            val s = Physics.stepVertical(y, vy, grounded, groundY = -0.75f, dtSeconds = 1f / 120f)
            y = s.y; vy = s.velY; grounded = s.grounded
            if (s.impactSpeed > 0f) { impact = s.impactSpeed; break }
            t += 1f / 120f
        }
        assertEquals(-0.75f, y, 1e-6f)
        assertTrue(grounded)
        // Free fall from 0.75 m: t = sqrt(2h/g) ≈ 0.39 s, v ≈ 3.84 m/s.
        assertEquals(0.39f, t, 0.02f)
        assertEquals(3.84f, impact, 0.1f)
    }

    @Test
    fun `jump rises and returns to the ground`() {
        var s = Physics.stepVertical(0f, 0f, true, 0f, 1f / 60f, jumpSpeed = 1f)
        assertTrue(s.y > 0f && !s.grounded)
        repeat(60) { s = Physics.stepVertical(s.y, s.velY, s.grounded, 0f, 1f / 60f) }
        assertEquals(0f, s.y, 0f)
        assertTrue(s.grounded)
    }

    @Test
    fun `flying hovers toward target with gravity off`() {
        var s = Physics.stepVertical(0f, 0f, true, 0f, 1f / 60f, flying = true, hoverY = 0.3f)
        repeat(240) { s = Physics.stepVertical(s.y, s.velY, s.grounded, 0f, 1f / 60f, flying = true, hoverY = 0.3f) }
        assertEquals(0.3f, s.y, 1e-3f)
    }

    @Test
    fun `ball bounces lower each time and comes to rest`() {
        var b = Ball(0f, 0.5f, 0f, 0.5f, 0f, 0f)
        var bounces = 0
        var lastVy = 0f
        repeat(1200) {
            val n = Physics.stepBall(b, 0f, 1f / 120f)
            if (b.vy < 0f && n.vy > 0f) bounces++
            lastVy = n.vy
            b = n
        }
        assertTrue(bounces >= 2)
        assertTrue(b.resting)
        assertEquals(0f, b.y, 1e-6f)
        assertEquals(0f, lastVy, 0f)
    }

    // ── Steering ────────────────────────────────────────────────────────────

    @Test
    fun `seek points at the target and stops inside stop radius`() {
        val (x, z) = DragonMotion.seek(0f, 0f, 1f, 0f, arriveRadius = 0.1f, stopRadius = 0.05f)
        assertEquals(1f, x, 1e-5f)
        assertEquals(0f, z, 1e-5f)
        assertEquals(0f to 0f, DragonMotion.seek(0f, 0f, 0.04f, 0f, 0.1f, 0.05f))
    }

    @Test
    fun `idle creature turns to face the camera`() {
        var pose = DragonPose(yawDeg = 0f)
        repeat(600) {
            pose = DragonMotion.step(pose, 0f to 0f, 1f / 60f, 0.1f, 5f, faceYawDeg = 90f)
        }
        assertEquals(90f, pose.yawDeg, 0.5f)
    }
}
