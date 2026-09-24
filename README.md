# Dragon Simulation

ARCore image-tracking demo. Point the camera at the printed target image and an
animated dragon spawns hovering on it. An on-screen joystick (bottom-left) flies
the dragon around on the image plane — it glides up to speed, banks into turns,
dips its nose while moving, and flaps faster the harder you push. A Reset button
(bottom-right) despawns it; non-ARCore devices get a readable error screen
instead of a black view.

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

### Emulator testing

The debug APK also registers a second reference image — `emulator_poster.png`,
the poster baked into the Android emulator's ARCore virtual scene
(`<sdk>/emulator/resources/poster.png`, ~2 m wall poster). It lives in
`app/src/debug/assets/` so release builds don't ship it, and it's only added
to the image database when `BuildConfig.DEBUG` is true.

To exercise the spawn flow on an emulator: create an AVD from a
**Google Play** system image (`google_apis_playstore`), set
`hw.camera.back = virtualscene` (emulator ≤ 36.x) or `environment`
(emulator 37.x) and `hw.camera.front = emulated` in its `config.ini`, boot,
install the debug APK, then aim the virtual camera at the wall poster in the
virtual scene.

**Known limitation (verified 2025-09 on emulator 37.1.11, Apple Silicon):**
on the arm64 API 34 Play Store image the scene camera registers as
`device@1.0/internal/10`, while ARCore's emulator device profile asks for
camera id `0` — `Session.resume()` then throws `FatalException`
("Failed to create cameras using image subsystem … unknown device 0").
This is google-ar/arcore-android-sdk#1647, still open. With
`hw.camera.back = emulated` the session *does* create (camera 0 exists and
the AugmentedImage detector runs) but ARCore's VIO aborts on the fake test
pattern. Net: arm64 API-34 emulators can't run the full AR pipeline; use a
physical device (what we verified on) or an x86/older-API image on an Intel
host where virtualscene still maps to camera 0.

## Replacing assets

| Asset | Path | Notes |
|---|---|---|
| Target image | `app/src/main/assets/images/target.png` | Any PNG/JPG works; textured, high-contrast images track best. Physical width is set in code — `Config.TARGET_IMAGE_WIDTH_M` (`0.15f` metres) in `app/src/main/java/com/dragonsim/ar/Config.kt`. The name registered in the ARCore database is `Config.TARGET_IMAGE_NAME` (`"target"`). Print the image so its real-world width matches, or tracking will be jittery. |
| Dragon model | `app/src/main/assets/models/dragon.glb` | Any animated GLB. Clip names are resolved at runtime by `DragonMotion.pickClip` — case-insensitive. Idle prefers `"idle"`, else index 0; Walk matches only `"walk"`/`"run"` — when no walk clip exists (flying creatures), the model keeps its idle clip and `animationSpeed` scales with joystick speed instead of hard-cutting poses. The bundled model ships `CharacterArmature|Flying_Idle`, `Fast_Flying`, `Death`, `Headbutt`, `HitReact`, `No`, `Punch`, `Yes`. |

## Tuning constants

All gameplay numbers live in `app/src/main/java/com/dragonsim/ar/Config.kt`:

| Constant | Default | Meaning |
|---|---|---|
| `TARGET_IMAGE_WIDTH_M` | `0.15f` | Physical width of the printed image (m). |
| `DRAGON_SCALE_UNITS` | `0.1f` | Dragon's bounding box fit into this cube (m). |
| `DRAGON_Y_OFFSET` | `0f` | Extra lift along the image normal if the pivot isn't at the feet. |
| `MODEL_YAW_OFFSET_DEG` | `0f` | Additive yaw fix if the model's authored forward isn't +Z. |
| `MOVE_SPEED_MPS` | `0.1f` | Top flight speed on the image plane (m/s). |
| `CLAMP_RADIUS_M` | `0.08f` | Max distance from the image centre (m). Was 0.3 per the original brief, but that let the dragon hover two card-widths past a 0.15 m card's edge — reported on-device as "vanishing". 0.08 keeps its centre on the card. |

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
image, since +Z runs top→bottom).

Flight model (`DragonMotion.step`, all exponential, dt-based):

- **Velocity** eases toward `stick × MOVE_SPEED_MPS` (~0.1 s constant) — the
  dragon glides in/out instead of popping between still and full speed.
- **Yaw** eases toward `atan2(dx, dz)` while input is held — a flyer keeps its
  heading during a glide-out.
- **Roll** banks into turns proportional to yaw rate (max ±22°), **pitch** dips
  ~10° at full speed; both ease back to level when the dragon stops.
- **Clamp**: position is capped inside `CLAMP_RADIUS_M` and velocity is
  recomputed from actual displacement, so it doesn't push at the boundary.
- **Wings**: the bundled dragon is a flyer with no walk clip, so `Flying_Idle`
  plays continuously and `animationSpeed` scales 0.9→2.1× with speed — a
  hard cut to `Fast_Flying` was what made the dragon appear to "change size"
  (the two clips' torso poses differ by ~0.66 model units). Drop in a GLB with
  a real `walk`/`run` clip and it switches clips on movement automatically.

Tracking semantics: the dragon renders only while `trackingMethod ==
FULL_TRACKING` (the image is actually in view). ARCore keeps an
`AugmentedImage` at `trackingState == TRACKING` via `LAST_KNOWN_POSE` long
after it leaves the frame, so the status text and the post-reset respawn
latch are driven by `trackingMethod`, not `trackingState` — gating on the
latter is what made "image lost" never appear and reset look broken.

## Manual device-test checklist

1. **Launch** — camera preview appears, status chip reads "Point your camera at the image".
2. **Spawn** — point at the printed `target.png`: exactly one dragon appears standing on it.
3. **Anchoring** — move/tilt the phone: the dragon stays glued to the image.
4. **Hide/show** — move the camera off the image (dragon hides via `FULL_TRACKING` visibility), back on (dragon reappears). Status shows "Image lost — point back at it" while the image isn't tracked.
5. **Joystick** — push it: dragon glides in that direction, turns to face its travel direction, banks into the turn, flaps faster; release: it glides to a stop and levels out. Its size never changes — the same clip plays throughout.
6. **Clamp** — hold the stick fully one direction: dragon stops at the 0.08 m radius and stays over the 0.15 m card.
7. **Reset** — tap Reset: dragon despawns, status returns to "Point your camera at the image". Still pointing at the image → no re-spawn; move away and back → dragon respawns.
8. **Unsupported device** — run on a non-ARCore device/emulator: full-screen message ("This device doesn't support ARCore" / "AR failed to start: …"), never a black screen.

## Assumptions & unverified

**Verified on a physical device** (iQOO I2202, Android 14, ARCore 1.56 —
debug APK installed over `adb`, `target.png` shown on a Mac screen):

- App launches, camera feed renders, status chip shows "Point your camera at
  the image" while searching and hides while tracking.
- The image is detected and **exactly one dragon spawns anchored to it**;
  the joystick appears only after spawn; the Reset button is present.
- On the API-34 emulator, where ARCore's camera HAL is unusable, the same
  build renders the **opaque error screen** ("AR failed to start:
  FatalException") instead of a black view — the Unsupported path works.

- A live joystick drag was observed via the debug HUD: pose integrated to
  (0.03, 0.00) m, yaw eased to -106°, roll banked to +14.7°, pitch dipped, and
  velocity/pitch/roll all settled to exact zero on release — glide-in/out,
  banking, and the no-size-change animation path confirmed on hardware.
- "Image lost — point back at it" banner appears the moment `trackingMethod`
  drops to `LAST_KNOWN_POSE`.

Still worth confirming by eye while holding the device:

- The dragon's banking/pitch/flap-rate look right from a natural viewing angle.
- Dragon hide/show on camera-away then camera-back transitions.
- Reset → move-away → re-approach respawn latch.

Everything else below remains assume-at-your-own-risk:
- Clip names in `models/dragon.glb` are unknown until it loads — `pickClip` guesses from names and logs the resolved choice (`adb logcat -s ARSceneScreen`). If the GLB's forward axis isn't +Z, set `MODEL_YAW_OFFSET_DEG`.
- `TrackingMethod` import is `com.google.ar.core.AugmentedImage.TrackingMethod` (nested enum) — verified against the SceneView source, which imports it the same way.
- Session-failure routing uses `onSessionFailure` (`ARSessionFailure` sealed class); `DeviceNotCompatible`/`SessionUnsupported` map to the unsupported-device message and cover `UnavailableDeviceNotCompatibleException`.
- SceneView auto-handles the CAMERA runtime permission on a `ComponentActivity` (`ARPermissionHandler` auto-detect); a denial surfaces as a session failure → error screen.
- After Reset while still pointing at the image, the status text reads "Point your camera at the image" even though the respawn latch is armed — by design (move away and back to respawn).
- Plugin versions are inlined in the root `build.gradle.kts` `plugins {}` block (Kotlin DSL resolves `plugins {}` before top-level `val`s — they can't live in variables).
