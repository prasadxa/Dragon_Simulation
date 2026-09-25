# Third-Party Assets & Dependencies

## Bundled Assets

### Dragon 3D Model — `Dragon Evolved`

- **File:** `app/src/main/assets/models/dragon.glb` (binary glTF 2.0, 436,000 bytes)
- **Author:** Quaternius (https://quaternius.com)
- **Source page:** https://poly.pizza/m/LlwD0QNUPj
- **Direct download used:** https://static.poly.pizza/90ed3740-d8c4-4910-88ce-ac2ed426022d.glb
- **Licence:** CC0 1.0 Universal (Public Domain) — https://creativecommons.org/publicdomain/zero/1.0/
  No attribution required; redistribution permitted.
- **Format details:** glTF 2.0 binary (GLB), self-contained (no external
  textures/buffers; flat vertex-coloured materials). Contains 1 skinned mesh
  ("Dragon", 46 joints, `skins[0]`) and 8 skeletal animation clips.

**Animation clip names** (verbatim from the GLB `animations[].name`; runtime
should match case-insensitively on `idle`/`fly`/`walk`/`run` keywords, with
these exact names as fallback):

| Clip name                      | Duration | Suggested use        |
|--------------------------------|----------|----------------------|
| `CharacterArmature|Flying_Idle` | ~1.50 s  | idle (contains "Idle") |
| `CharacterArmature|Fast_Flying` | ~0.83 s  | locomotion (fly)     |
| `CharacterArmature|Death`       | ~0.67 s  | one-shot             |
| `CharacterArmature|Headbutt`    | ~1.50 s  | one-shot             |
| `CharacterArmature|HitReact`    | ~0.67 s  | one-shot             |
| `CharacterArmature|No`          | ~1.17 s  | one-shot             |
| `CharacterArmature|Punch`       | ~1.33 s  | one-shot             |
| `CharacterArmature|Yes`         | ~1.17 s  | one-shot             |

Note: Quaternius dragons are flying creatures — the clip set has no ground
"Walk" animation. `Flying_Idle` covers the "idle" keyword and `Fast_Flying`
the "fly" keyword.

### Dragon 3D Model — `Tarisland - Dragon (High Poly)` ("Dark Dragon" in-app, default)

- **File:** `app/src/main/assets/models/dragon_dark.glb` (17.5 MB)
- **Author:** Doctor A. — https://sketchfab.com/s8819296 (displayed as Pigcraft)
- **Source page:** https://sketchfab.com/3d-models/tarisland-dragon-high-poly-ecf63885166c40e2bbbcdf11cd14e65f
- **Licence:** CC-BY 4.0 — https://creativecommons.org/licenses/by/4.0/
  Attribution required (this entry + in-app credit); commercial use allowed.
- **Pipeline:** source `M_B_44_Qishilong_skin_Skeleton.FBX` (118 MB, Unreal
  export) → FBX2glTF v0.13.1 → clip rename (canonical `Idle`/`Walk`/`Fly` +
  `Qishilong_` prefix stripped; 3 cinematic cutscene clips dropped) →
  gltf-transform `resample` + `prune` + `dedup`.
- **Clips (21):** `Idle`, `Walk`, `Fly`, `Up`/`Up_2`/`Up_3`, `Down`/`Down_2`/`Down_3`,
  `Attack_1`, `Attack_2`, `Death`, `Turn_Left`, `Turn_Right`, `skill02`–`skill11`.
  40,437 tris · 151-joint skin · 1024² base+normal textures ×2.

### AR Reference Image — `target.png`

- **File:** `app/src/main/assets/images/target.png` (1024×1024 RGB PNG)
- **Generated in-repo** by `tools/make_target_image.py` (pure Python stdlib,
  deterministic seed). Project-internal asset; no external licence applies.
- Design: black-on-white, border frame, jittered-grid coverage of triangles,
  bars, rings, circles, QR-like micro-blocks and a bullseye; bold asymmetric
  corner glyph (top-left) plus a secondary diamond (bottom-right) for
  unambiguous orientation.

### Dragon 3D Model — `Simple 3D Dragon Model` ("Classic Dragon" in-app)

- **File:** `app/src/main/assets/models/dragon_classic.glb`
- **Author:** MattBas — https://opengameart.org/content/simple-3d-dragon-model
- **Direct download used:** https://opengameart.org/sites/default/files/dragon.tar_1.gz
- **Licence:** CC-BY-SA 4.0 — https://creativecommons.org/licenses/by-sa/4.0/
  Attribution required (this entry + in-app credit). Share-alike applies to the
  model and any modified versions of it.
- **Clips:** `Idle-loop`, `Walk-loop`, `Run-loop`, `Flying-loop`

### Fox — Khronos glTF Sample Assets

- **File:** `app/src/main/assets/models/fox.glb`
- **Source:** https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Fox
- **Licence:** mesh CC0 1.0 (PixelMannen); rigging & animation CC-BY 4.0
  (@tomkranis on Sketchfab); glTF conversion CC-BY 4.0 (@AsoboStudio, @scurest).
- **Clips:** `Survey` (idle), `Walk`, `Run`

### SceneView official AR samples (pattern reference)

- **Source:** https://github.com/sceneview/sceneview — `samples/android-demo`
  (`TapToPlaceArSession.kt`, `ArPlacement.kt`, `ARPointCloudDemo.kt`), v4.39.0
- **Licence:** Apache-2.0
- Placement (`findAutoPlacementSurface` → plane anchor), pause-tolerant
  `AnchorNode` visibility, `ShadowReceiverPlane` and `PointCloudNode` usage in
  `ARSceneScreen.kt` / `CreatureSim.kt` follow these samples.

## Runtime Dependencies (declared for completeness — not bundled)

| Dependency | Licence / Terms |
|------------|-----------------|
| SceneView (io.github.sceneview) | Apache License 2.0 — https://github.com/SceneView/sceneview-android/blob/main/LICENSE |
| Google ARCore SDK (com.google.ar.core) | Google Terms of Service / ARCore SDK terms — https://developers.google.com/ar/develop/terms |
| Filament (transitive via SceneView) | Apache License 2.0 — https://github.com/google/filament |
| AndroidX / Kotlin / Jetpack Compose | Apache License 2.0 |

## web/ (WebXR build)
- three.js r186 (MIT, https://github.com/mrdoob/three.js), loaded from jsDelivr.
  `web/index.html` follows its `webxr_ar_hittest` and `webgl_animation_skinning_blending`
  examples and the immersive-web `hit-test-anchors` sample (MIT).
