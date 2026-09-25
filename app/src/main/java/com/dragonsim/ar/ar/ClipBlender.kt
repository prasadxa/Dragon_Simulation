package com.dragonsim.ar.ar

import com.google.android.filament.gltfio.Animator
import kotlin.math.exp

/**
 * Animation roles resolved from GLB clip names (case-insensitive), so any rigged
 * glTF with conventional names works without code changes.
 *
 * - [idle]: "idle" (not flying) → a flying idle → "survey"/"stand"/"rest" → clip 0
 * - [walk]/[run]: "walk" / "run"|"gallop" (a lone run clip becomes the walk)
 * - [fly]: "fly" but not idle; [flyIdle]: "fly" and "idle"
 * - [actions]: every other clip — playable as one-shots from the action menu
 */
data class ClipSet(
    val idle: Int,
    val walk: Int? = null,
    val run: Int? = null,
    val fly: Int? = null,
    val flyIdle: Int? = null,
    val actions: List<Int> = emptyList(),
) {
    val canFly get() = fly != null || flyIdle != null
    val canWalk get() = walk != null

    companion object {
        fun resolve(names: List<String>): ClipSet? {
            if (names.isEmpty()) return null
            val lower = names.map { it.lowercase() }
            fun first(pred: (String) -> Boolean) = lower.indexOfFirst(pred).takeIf { it >= 0 }
            fun isFly(n: String) = "fly" in n
            val flyIdle = first { isFly(it) && "idle" in it }
            val idle = first { "idle" in it && !isFly(it) }
                ?: flyIdle
                ?: first { "survey" in it || "stand" in it || "rest" in it }
                ?: 0
            var walk = first { "walk" in it && !isFly(it) }
            var run = first { ("run" in it || "gallop" in it) && !isFly(it) }
            if (walk == null) {
                walk = run
                run = null
            }
            val fly = first { isFly(it) && "idle" !in it }
            val used = setOfNotNull(idle, walk, run, fly, flyIdle)
            return ClipSet(idle, walk, run, fly, flyIdle, names.indices.filter { it !in used })
        }
    }
}

/**
 * Drives the Filament [Animator] directly from the node's `onFrame` — SceneView's
 * `playAnimation` can only hard-cut (and restarts clips), which pops between poses.
 *
 * Layers, applied each frame via Filament's cross-fade chain
 * (`applyAnimation(a)` then `applyCrossFade(b, t, alpha)` = lerp(b → current, alpha)):
 * 1. locomotion: walk ↔ run (phase-synced so feet don't skate), or the fly clip
 * 2. idle ↔ locomotion by speed
 * 3. mode-switch fade (walk ↔ fly) so toggling flight never pops
 * 4. one-shot action (tap reaction, action menu) with ramp in/out
 *
 * Timing lives in [advance] (pure, unit-tested); [apply] only touches Filament.
 */
class ClipBlender(
    val clips: ClipSet,
    private val durations: FloatArray,
) {
    /** 0..1 fraction of top speed, written from the UI thread. */
    @Volatile var targetSpeed: Float = 0f

    /** Airborne flight mode; ignored if the model has no fly clip. */
    @Volatile var flying: Boolean = false

    /** Weight of locomotion vs idle: 0 = idle, 1 = full speed. Eased toward [targetSpeed]. */
    var weight = 0f
        private set
    var idleTime = 0f
        private set

    /** Normalised 0..1 locomotion cycle shared by walk and run. */
    var locoPhase = 0f
        private set

    var actionIndex: Int? = null
        private set
    private var actionTime = 0f
    var actionWeight = 0f
        private set

    private var fadeFrom: Int? = null
    private var fadeFromTime = 0f
    private var fadeT = 1f
    private var wasFlying = false
    private var lastNanos = -1L

    private val isFlying get() = flying && clips.canFly
    private fun dur(i: Int?) = i?.let { durations.getOrNull(it) }?.takeIf { it > 0f } ?: 1f

    private fun idleClip() =
        if (isFlying) clips.flyIdle ?: clips.fly ?: clips.idle else clips.idle

    private fun locoClip() = if (isFlying) clips.fly ?: clips.walk else clips.walk ?: clips.fly

    /** Run blend 0..1 — upper half of the speed range, walking only. */
    private fun runMix() =
        if (!isFlying && clips.run != null) ((weight - 0.5f) / 0.5f).coerceIn(0f, 1f) else 0f

    /** Idle→loco blend: reaches full locomotion at half speed when a run clip exists. */
    private fun moveMix() =
        if (!isFlying && clips.run != null) (weight / 0.5f).coerceIn(0f, 1f) else weight

    /** Play a one-shot clip on top of the base blend. */
    fun playAction(index: Int) {
        if (index !in durations.indices) return
        actionIndex = index
        actionTime = 0f
    }

    fun advance(dtSeconds: Float, speed: Float) {
        val dt = dtSeconds.coerceIn(0f, MAX_DT)
        weight += (speed.coerceIn(0f, 1f) - weight) * (1f - exp(-BLEND_RATE * dt))
        if (weight < SNAP) weight = 0f
        if (weight > 1f - SNAP) weight = 1f

        if (isFlying != wasFlying) {
            // Remember the outgoing mode's dominant clip and fade from it.
            val outgoing = if (weight > 0.5f) locoClipFor(wasFlying) else idleClipFor(wasFlying)
            fadeFrom = outgoing
            fadeFromTime = if (outgoing == idleClipFor(wasFlying)) idleTime else locoPhase * dur(outgoing)
            fadeT = 0f
            wasFlying = isFlying
        }
        if (fadeT < 1f) fadeT = (fadeT + dt / MODE_FADE_S).coerceAtMost(1f)

        // Hovering without a dedicated fly-idle: slow the fly cycle down.
        val idleRate = if (isFlying && clips.flyIdle == null) 0.6f else 1f
        idleTime = wrap(idleTime + dt * idleRate, dur(idleClip()))

        val cycle = if (runMix() > 0f) lerp(dur(clips.walk), dur(clips.run), runMix()) else dur(locoClip())
        locoPhase = (locoPhase + dt * (MOVE_RATE_MIN + MOVE_RATE_GAIN * speed) / cycle) % 1f

        actionIndex?.let { a ->
            actionTime += dt
            val d = dur(a)
            actionWeight = minOf(1f, actionTime / ACTION_IN_S, (d - actionTime) / ACTION_OUT_S).coerceIn(0f, 1f)
            if (actionTime >= d) {
                actionIndex = null
                actionWeight = 0f
            }
        }
    }

    fun onFrame(animator: Animator, frameTimeNanos: Long) {
        val dt = if (lastNanos < 0L) 0f else (frameTimeNanos - lastNanos) / 1_000_000_000f
        lastNanos = frameTimeNanos
        advance(dt, targetSpeed)
        apply(animator)
    }

    private fun apply(animator: Animator) {
        val idle = idleClip()
        val loco = locoClip()
        val move = moveMix()
        if (loco == null || move == 0f) {
            animator.applyAnimation(idle, idleTime)
        } else {
            val run = runMix()
            val walk = clips.walk
            if (run > 0f && clips.run != null && walk != null) {
                animator.applyAnimation(clips.run, locoPhase * dur(clips.run))
                if (run < 1f) animator.applyCrossFade(walk, locoPhase * dur(walk), run)
            } else {
                animator.applyAnimation(loco, locoPhase * dur(loco))
            }
            if (move < 1f) animator.applyCrossFade(idle, idleTime, move)
        }
        fadeFrom?.let { if (fadeT < 1f) animator.applyCrossFade(it, fadeFromTime, fadeT) }
        actionIndex?.let { a ->
            if (actionWeight > 0f) animator.applyCrossFade(a, actionTime, 1f - actionWeight)
        }
        animator.updateBoneMatrices()
    }

    private fun idleClipFor(fly: Boolean) =
        if (fly && clips.canFly) clips.flyIdle ?: clips.fly ?: clips.idle else clips.idle

    private fun locoClipFor(fly: Boolean) =
        if (fly && clips.canFly) clips.fly ?: clips.walk else clips.walk ?: clips.fly

    companion object {
        /** ~0.3 s to settle a blend — slower than velocity easing so poses glide. */
        const val BLEND_RATE = 8f
        const val MOVE_RATE_MIN = 0.8f
        const val MOVE_RATE_GAIN = 0.6f
        const val MODE_FADE_S = 0.35f
        const val ACTION_IN_S = 0.15f
        const val ACTION_OUT_S = 0.25f
        private const val SNAP = 0.01f
        private const val MAX_DT = 0.1f

        private fun wrap(t: Float, duration: Float) = if (duration > 0f) t % duration else 0f
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun forAnimator(animator: Animator): ClipBlender? {
            val names = (0 until animator.animationCount).map { animator.getAnimationName(it) }
            val clips = ClipSet.resolve(names) ?: return null
            val durations = FloatArray(names.size) { animator.getAnimationDuration(it) }
            return ClipBlender(clips, durations)
        }
    }
}
