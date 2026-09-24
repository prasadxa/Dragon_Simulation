# Dragon Simulation

ARCore image-tracking demo. Point the camera at the printed target image and an
animated dragon spawns standing on it. An on-screen joystick (bottom-left) walks
the dragon around on the image plane — it faces its movement direction and plays
its **Walk** clip while moving, **Idle** while still. A Reset button (bottom-right)
despawns it; non-ARCore devices get a readable error screen instead of a black
view.

## Versions

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 (Kotlin built in — no `org.jetbrains.kotlin.android` plugin; the Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` 2.4.20 still applies) |
| Gradle | 9.8.0 via `./gradlew` wrapper |
| JDK | 21 (`org.gradle.java.home` in `gradle.properties`) |
| SceneView | `io.github.sceneview:arsceneview:4.39.0` (ARCore + Filament + Compose) |
| Compose BOM | `androidx.compose:compose-bom:2026.09.00` |
| activity-compose | 1.13.0 |
| lifecycle-runtime-compose | 2.11.0 |
| compileSdk | 37 (SceneView 4.39 / Compose 1.12 require ≥ 37 → AGP ≥ 9.1) |
| targetSdk / minSdk | 36 / 26 |
| ABI | arm64-v8a only |

> Why these versions: SceneView 4.39.0 and the 2026.09 Compose BOM declare
> compileSdk 37 and AGP ≥ 9.1 in their AAR metadata, and AGP 9.x needs
> Gradle ≥ 9.6. If you ever downgrade SceneView, the older AGP 8.13 +
> Gradle ≤ 9.5 combination also works.

## Build & install

```bash
# Needs ANDROID_HOME or local.properties with sdk.dir — SDK lives at ~/Library/Android/sdk
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # if not auto-detected

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (pure JVM, no device needed):

```bash
./gradlew testDebugUnitTest
```

## Replacing assets

| Asset | Path | Notes |
|---|---|---|
| Target image | `app/src/main/assets/images/target.png` | Any PNG/JPG works; textured, high-contrast images track best. Physical width is set in code — `Config.TARGET_IMAGE_WIDTH_M` (`0.15f` metres) in `app/src/main/java/com/dragonsim/ar/Config.kt`. The name registered in the ARCore database is `Config.TARGET_IMAGE_NAME` (`"target"`). Print the image so its real-world width matches, or tracking will be jittery. |
| Dragon model | `app/src/main/assets/models/dragon.glb` | Any animated GLB. Clip names are resolved at runtime by `DragonMotion.pickClip` — case-insensitive, prefers names containing `"idle"` / `"walk"`/`"run"`/`"fly"`/`"move"`, falls back to index 0. Resolved names are logged via `Log.d("ARSceneScreen", …)`. The bundled model ships `CharacterArmature|Flying_Idle`, `CharacterArmature|Fast_Flying`, `Death`, `Headbutt`, `HitReact`, `No`, `Punch`, `Yes` — so it resolves Idle → `Flying_Idle`, Walk → `Fast_Flying`. |

## Tuning constants

All gameplay numbers live in `app/src/main/java/com/dragonsim/ar/Config.kt`:

| Constant | Default | Meaning |
|---|---|---|
| `TARGET_IMAGE_WIDTH_M` | `0.15f` | Physical width of the printed image (m). |
| `DRAGON_SCALE_UNITS` | `0.1f` | Dragon's bounding box fit into this cube (m). |
| `DRAGON_Y_OFFSET` | `0f` | Extra lift along the image normal if the pivot isn't at the feet. |
| `MODEL_YAW_OFFSET_DEG` | `0f` | Additive yaw fix if the model's authored forward isn't +Z. |
| `MOVE_SPEED_MPS` | `0.1f` | Walk speed on the image plane (m/s). |
| `CLAMP_RADIUS_M` | `0.3f` | Max distance from the image centre (m). |

The dragon is bottom-aligned to the image plane via
`ModelNode(centerOrigin = Position(0f, -1f, 0f))`, so `DRAGON_Y_OFFSET` normally
stays at 0.

## Architecture

```
MainActivity            edge-to-edge, hosts DragonSimApp
DragonSimApp.kt         AppRoot — state machine (Searching/Tracking/Lost/Unsupported),
                        spawn latch, withFrameNanos motion loop, layout Box
ar/ARSceneScreen.kt     ARSceneView + AugmentedImageDatabase wiring, image union by
                        trackableId, clip-name resolution, ModelNode mounting
ar/DragonMotion.kt      PURE logic (no Android deps): DragonPose, step(), isMoving(),
                        pickClip() — unit-tested
ui/Joystick.kt          120dp base / 48dp knob, normalized [-1,1] output
ui/StatusOverlay.kt     status chip + Reset button + full-screen error state
```

Movement mapping: joystick up (screen `-y`) → image-local `-z` ("up" the printed
image, since +Z runs top→bottom). Yaw = `atan2(dx, dz)`, exponentially smoothed.

## Manual device-test checklist

1. **Launch** — camera preview appears, status chip reads "Point your camera at the image".
2. **Spawn** — point at the printed `target.png`: exactly one dragon appears standing on it.
3. **Anchoring** — move/tilt the phone: the dragon stays glued to the image.
4. **Hide/show** — move the camera off the image (dragon hides via `FULL_TRACKING` visibility), back on (dragon reappears). Status shows "Image lost — point back at it" while the image isn't tracked.
5. **Joystick** — push it: dragon walks in that direction, faces its travel direction, plays Walk; release: dragon stops, plays Idle.
6. **Clamp** — hold the stick fully one direction: dragon stops at the 0.3 m radius and never leaves the image area.
7. **Reset** — tap Reset: dragon despawns, status returns to "Point your camera at the image". Still pointing at the image → no re-spawn; move away and back → dragon respawns.
8. **Unsupported device** — run on a non-ARCore device/emulator: full-screen message ("This device doesn't support ARCore" / "AR failed to start: …"), never a black screen.

## Assumptions & unverified

- **Needs a physical ARCore device** — image tracking, clip switching, anchoring and the joystick feel cannot be exercised on an emulator or in unit tests. Everything below should be confirmed on-device:
- Clip names in `models/dragon.glb` are unknown until it loads — `pickClip` guesses from names and logs the resolved choice (`adb logcat -s ARSceneScreen`). If the GLB's forward axis isn't +Z, set `MODEL_YAW_OFFSET_DEG`.
- `TrackingMethod` import is `com.google.ar.core.AugmentedImage.TrackingMethod` (nested enum) — verified against the SceneView source, which imports it the same way.
- Session-failure routing uses `onSessionFailure` (`ARSessionFailure` sealed class); `DeviceNotCompatible`/`SessionUnsupported` map to the unsupported-device message and cover `UnavailableDeviceNotCompatibleException`.
- SceneView auto-handles the CAMERA runtime permission on a `ComponentActivity` (`ARPermissionHandler` auto-detect); a denial surfaces as a session failure → error screen.
- After Reset while still pointing at the image, the status text reads "Point your camera at the image" even though the respawn latch is armed — by design (move away and back to respawn).
- Plugin versions are inlined in the root `build.gradle.kts` `plugins {}` block (Kotlin DSL resolves `plugins {}` before top-level `val`s — they can't live in variables).
