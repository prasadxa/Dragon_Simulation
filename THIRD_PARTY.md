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

### AR Reference Image — `target.png`

- **File:** `app/src/main/assets/images/target.png` (1024×1024 RGB PNG)
- **Generated in-repo** by `tools/make_target_image.py` (pure Python stdlib,
  deterministic seed). Project-internal asset; no external licence applies.
- Design: black-on-white, border frame, jittered-grid coverage of triangles,
  bars, rings, circles, QR-like micro-blocks and a bullseye; bold asymmetric
  corner glyph (top-left) plus a secondary diamond (bottom-right) for
  unambiguous orientation.

## Runtime Dependencies (declared for completeness — not bundled)

| Dependency | Licence / Terms |
|------------|-----------------|
| SceneView (io.github.sceneview) | Apache License 2.0 — https://github.com/SceneView/sceneview-android/blob/main/LICENSE |
| Google ARCore SDK (com.google.ar.core) | Google Terms of Service / ARCore SDK terms — https://developers.google.com/ar/develop/terms |
| Filament (transitive via SceneView) | Apache License 2.0 — https://github.com/google/filament |
| AndroidX / Kotlin / Jetpack Compose | Apache License 2.0 |
