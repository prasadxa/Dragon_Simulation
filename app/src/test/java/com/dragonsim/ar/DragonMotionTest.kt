package com.dragonsim.ar

import com.dragonsim.ar.ar.ClipKind
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
    fun `pickClip prefers matching names case-insensitively`() {
        val names = listOf("Walk_Cycle", "IDLE_pose", "Attack")
        assertEquals("IDLE_pose", DragonMotion.pickClip(names, ClipKind.Idle))
        assertEquals("Walk_Cycle", DragonMotion.pickClip(names, ClipKind.Walk))
    }

    @Test
    fun `pickClip Walk returns null when no walk or run clip exists`() {
        assertEquals("RunFast", DragonMotion.pickClip(listOf("RunFast"), ClipKind.Idle))
        assertNull(DragonMotion.pickClip(listOf("rest"), ClipKind.Walk))
    }

    @Test
    fun `pickClip Walk is null on the shipped flying-dragon model`() {
        // Actual clips in app/src/main/assets/models/dragon.glb — no walk/run,
        // so the caller keeps Flying_Idle and modulates animation speed instead.
        val names = listOf(
            "CharacterArmature|Death",
            "CharacterArmature|Fast_Flying",
            "CharacterArmature|Flying_Idle",
            "CharacterArmature|Punch",
        )
        assertEquals("CharacterArmature|Flying_Idle", DragonMotion.pickClip(names, ClipKind.Idle))
        assertNull(DragonMotion.pickClip(names, ClipKind.Walk))
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

    @Test
    fun `pickClip returns null for empty list`() {
        assertNull(DragonMotion.pickClip(emptyList(), ClipKind.Idle))
        assertNull(DragonMotion.pickClip(emptyList(), ClipKind.Walk))
    }
}
