package com.dragonsim.ar

/** Tunable constants (these stand in for the design-doc "Inspector" fields). */
object Config {

    // ── Assets ───────────────────────────────────────────────────────────────
    const val TARGET_IMAGE_ASSET = "images/target.png"

    /** Selectable creatures (in-app picker). First entry is the default. */
    val CREATURES = listOf(
        CreatureModel("Dark Dragon", "models/dragon_dark.glb"),
        CreatureModel(
            "Dragon", "models/dragon.glb",
            webUrl = "https://static.poly.pizza/90ed3740-d8c4-4910-88ce-ac2ed426022d.glb",
        ),
        CreatureModel("Classic Dragon", "models/dragon_classic.glb"),
        CreatureModel(
            "Fox", "models/fox.glb",
            webUrl = "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb",
        ),
    )

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
    const val DRAGON_SCALE_UNITS = 0.25f

    /** Extra lift along the image normal if the model's pivot isn't at its feet. */
    const val DRAGON_Y_OFFSET = 0f

    /** Additive yaw fix if the model's authored forward isn't +Z. */
    const val MODEL_YAW_OFFSET_DEG = 0f

    // ── Movement ─────────────────────────────────────────────────────────────
    /** Walk speed on the image plane, metres/second. */
    const val MOVE_SPEED_MPS = 0.1f

    /** Walking speed in body lengths per second — bigger creatures move faster… */
    const val BODIES_PER_S = 0.7f

    /** …up to this absolute cap (m/s), so a pinch-zoomed giant doesn't race across the room. */
    const val MAX_WALK_MPS = 0.45f

    /** Flying is this much faster than walking. */
    const val FLY_SPEED_FACTOR = 1.3f

    /** Surfaces within this height of each other count as one floor level (m). */
    const val SAME_LEVEL_M = 0.08f


    // ── World physics / behaviour ────────────────────────────────────────────
    /** Creatures may roam this far from where they were placed (m). */
    const val MAX_ROAM_M = 5f
    const val THROW_SPEED_MPS = 1.4f
    const val BALL_RADIUS_M = 0.012f
    /** Jump apex, in body sizes. */
    const val JUMP_HEIGHT_BODIES = 0.8f
    const val CLIMB_SPEED_MPS = 0.25f
    const val MAX_FLY_HEIGHT_M = 1.2f

    /** Placement sanity: ignore hits closer/farther than this from the camera (m). */
    const val MIN_PLACE_M = 0.15f
    const val MAX_PLACE_M = 3f
    // ── Room scan ────────────────────────────────────────────────────────────
    /** Detected surface area that counts as a complete scan (m²). */
    const val SCAN_TARGET_M2 = 1.5f
    /** After the scan, place automatically on the first good surface (Scene Viewer style). */
    const val AUTO_PLACE = true

    /** A ground below further than this is treated as bad depth, not a drop. */
    const val MAX_FALL_M = 2.5f

    // ── Pinch-to-zoom ────────────────────────────────────────────────────────
    const val MIN_USER_SCALE = 0.5f
    const val MAX_USER_SCALE = 6f
}

/**
 * A bundled GLB. Clips are resolved by name at load (idle + walk/run/fly), so any
 * rigged glTF with those names works without code changes.
 *
 * @param scaleUnits bounding box is fitted into this cube (metres) before pinch-zoom.
 * @param yawOffsetDeg additive yaw if the model's authored forward isn't +Z.
 */
data class CreatureModel(
    val label: String,
    val asset: String,
    val scaleUnits: Float = Config.DRAGON_SCALE_UNITS,
    val yawOffsetDeg: Float = Config.MODEL_YAW_OFFSET_DEG,
    /** Public copy of the same GLB, for Google Scene Viewer (it needs a URL). */
    val webUrl: String? = null,
)
