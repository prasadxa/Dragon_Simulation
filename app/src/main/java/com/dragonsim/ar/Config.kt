package com.dragonsim.ar

/** Tunable constants (these stand in for the design-doc "Inspector" fields). */
object Config {

    // ── Assets ───────────────────────────────────────────────────────────────
    const val TARGET_IMAGE_ASSET = "images/target.png"
    const val DRAGON_MODEL_ASSET = "models/dragon.glb"

    // ── Tracking ─────────────────────────────────────────────────────────────
    /** Name the reference image is registered under in the AugmentedImageDatabase. */
    const val TARGET_IMAGE_NAME = "target"

    /** Physical width of the printed target image, in metres. */
    const val TARGET_IMAGE_WIDTH_M = 0.15f

    // ── Emulator testing (debug builds only) ─────────────────────────────────
    /** Poster baked into the ARCore emulator virtual scene — debug builds only. */
    const val EMULATOR_POSTER_ASSET = "images/emulator_poster.png"
    const val EMULATOR_POSTER_NAME = "emulator_poster"

    /** The virtual scene's wall poster is ~2 m wide (see Toren1BD.posters). */
    const val EMULATOR_POSTER_WIDTH_M = 2.0f

    // ── Dragon placement ─────────────────────────────────────────────────────
    /** Uniform scale: the dragon's bounding box is shrunk to fit this cube (metres). */
    const val DRAGON_SCALE_UNITS = 0.1f

    /** Extra lift along the image normal if the model's pivot isn't at its feet. */
    const val DRAGON_Y_OFFSET = 0f

    /** Additive yaw fix if the model's authored forward isn't +Z. */
    const val MODEL_YAW_OFFSET_DEG = 0f

    // ── Movement ─────────────────────────────────────────────────────────────
    /** Walk speed on the image plane, metres/second. */
    const val MOVE_SPEED_MPS = 0.1f

    /** Max distance from the image centre the dragon can walk to, in metres. */
    const val CLAMP_RADIUS_M = 0.3f
}
