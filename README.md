# Dragon Simulation

AR creature pet for Android. Scan the room, place a dragon on the floor or a
table (or on the printed target image), fly it with the on-screen joystick, tap
to send it walking, throw it a ball to fetch, make it jump, trigger trick
animations, and take photos/video of it. A companion WebXR build (`web/`) runs
the same experience in Chrome on ARCore phones — no install.

Default creature is **Dark Dragon** — a realistic PBR-textured model (Tarisland
dragon, CC-BY) with idle/walk/fly blending and 21 action clips. A cartoon
Quaternius dragon, a classic walking dragon, and the Khronos Fox are also in the
picker.

## Versions

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 (Kotlin built in — no `org.jetbrains.kotlin.android` plugin; the Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` 2.4.20 still applies) |
| Gradle | 9.8.0 via `./gradlew` wrapper |
| JDK | 21 (`org.gradle.java.home` in `gradle.properties`) |
| SceneView | `io.github.sceneview:arsceneview:4.39.0` (ARCore + Filament + Compose) |
| Compose BOM | `androidx.compose:compose-bom:2026.09.00` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| ABI | arm64-v8a only |

> SceneView 4.39.0 and the 2026.09 Compose BOM declare compileSdk 37 and
> AGP ≥ 9.1 in their AAR metadata, and AGP 9.x needs Gradle ≥ 9.6.

## Build & install

```bash
# Needs ANDROID_HOME or local.properties with sdk.dir — SDK lives at ~/Library/Android/sdk
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # if not auto-detected

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (pure JVM, no device needed — motion, physics, clip resolution):

```bash
./gradlew testDebugUnitTest    # 23 tests
./gradlew lintDebug            # lint report → app/build/reports/lint-results-debug.html
```

Debug hooks (debug builds only):

```bash
# ARCore record/replay — test without moving the phone:
adb shell am start -n com.dragonsim.ar/.MainActivity --ez record true    # writes files/session.mp4
adb shell am start -n com.dragonsim.ar/.MainActivity --ez playback true  # replays it
adb logcat -s ARSceneScreen CreatureSim
```

### Emulator testing

Full AR does **not** work on arm64 API-34 emulators (ARCore's camera HAL maps
the scene camera to id 10 while its emulator profile wants id 0 → native VIO
abort; google-ar/arcore-android-sdk#1647). Use a physical device. A debug-only
`emulator_poster.png` reference image ships in `app/src/debug/assets/` for the
x86/older-API case where virtualscene still maps to camera 0.

## How it works

Launch lands in a **scan phase**: the point cloud and detected planes render
live while the mini-map fills in — a tap on a surface (or Finish/Skip) ends it.
After that, placement is automatic on the first good surface at screen centre
(`AUTO_PLACE`), or explicit via tap / "Place in front of me". Only
**horizontal upward-facing planes** accept placement — walls and ceilings are
rejected with a hint.

Once placed, the creature lives under a `Plane.createAnchor` whose frame is
gravity-aligned (local +Y = world up): it stays put through `PAUSED` tracking,
keeps walking inside its plane's polygon, and drops to a lower plane (with
gravity + landing squash) if it walks off an edge. Vertical planes block it.

- **Joystick** (bottom-left): camera-relative steering — push up = away from
  you, however the phone is held. Velocity eases in/out; yaw banks into turns.
- **Tap a surface**: walk there (targets clamp to the roam circle).
- **Tap the creature**: reaction animation + floating hearts + haptic.
- **Right column**: Jump / fly-altitude, Fly↔Land toggle, throw ball, Tricks
  menu (every spare clip as a one-shot), behaviour cycle Manual → Follow →
  Wander.
- **Left column**: photo → `Pictures/DragonAR`, video → `Movies/DragonAR`
  (share sheet), hide-UI toggle, Google Scene Viewer, Reset.
- **Pinch**: resize the creature 0.5–6×. **Settings**: occlusion (depth phones),
  plane overlay, contact shadow, face-camera, debug HUD, rescan/re-place.

Animation is driven by `ClipBlender` straight into the Filament animator
(SceneView `playAnimation` hard-cuts and restarts clips — never used): idle ↔
walk ↔ run crossfades by speed, fly-mode fades, phase-synced cycles, one-shot
actions on top. Clip roles are resolved from names — any rigged GLB with
idle/walk/run/fly-style clip names works unmodified.

## Replacing assets

| Asset | Path | Notes |
|---|---|---|
| Creatures | `app/src/main/assets/models/*.glb` | Registered in `Config.CREATURES`. Skinned GLBs with idle/walk/run/fly clip names are picked up automatically (`ClipSet.resolve`); first entry is the default. See `THIRD_PARTY.md` for licences/attribution. |
| Target image | `app/src/main/assets/images/target.png` | Physical width `Config.TARGET_IMAGE_WIDTH_M` (0.15 m). Print it at that size or tracking jitters. Used only in Image mode. |

## Tuning constants

All gameplay numbers live in `app/src/main/java/com/dragonsim/ar/Config.kt`:

| Constant | Default | Meaning |
|---|---|---|
| `DRAGON_SCALE_UNITS` | `0.25f` | Creature bbox fitted into this cube (m), before pinch-zoom. |
| `BODIES_PER_S` | `1.2f` | Walk speed in body lengths/s (×1.6 while flying). |
| `MAX_ROAM_M` | `5f` | Roam radius around the anchor (m). |
| `MIN_PLACE_M` / `MAX_PLACE_M` | `0.15` / `3` | Valid tap-to-place distances (m). |
| `SCAN_TARGET_M2` | `1.5f` | Plane area that counts as a complete scan. |
| `AUTO_PLACE` | `true` | Auto-place on the first good centred surface after the scan. |
| `JUMP_HEIGHT_BODIES` | `0.8f` | Jump apex in body sizes. |
| `CLIMB_SPEED_MPS` / `MAX_FLY_HEIGHT_M` | `0.25` / `1.2` | Fly-mode altitude controls. |
| `MIN_USER_SCALE` / `MAX_USER_SCALE` | `0.5` / `6` | Pinch-zoom range. |

## Architecture

```
MainActivity            edge-to-edge, hosts DragonSimApp
DragonSimApp.kt         AppRoot — HUD layout, gestures, prefs, capture share sheet
Config.kt               tuning constants + CreatureModel registry
ar/CreatureSim.kt       world state owner, stepped once per AR frame:
                        scan stats/minimap, placement (plane hit → createAnchor),
                        steering priority stick > tap > ball > Follow/Wander,
                        wall rays, ground rays + edge drops, flight, jump, ball,
                        screen projection (off-screen arrow, tap-to-pet)
ar/ARSceneScreen.kt     render-only: session config, image DB, point cloud,
                        AnchorNode → ContactShadow (ShadowReceiverPlane) + ModelNode
                        transform written imperatively from ModelNode.onFrame
ar/ClipBlender.kt       Filament animator driver: idle↔walk↔run↔fly crossfades,
                        mode fades, one-shot actions; ClipSet.resolve clip roles
ar/DragonMotion.kt      PURE logic (no Android deps): DragonPose, step(), seek(),
                        yawToward(), cameraRelative() — unit-tested
ar/Physics.kt           PURE logic: stepVertical (gravity/hover/jump), stepBall
ar/ArCapture.kt         photo/video via SceneView SurfaceMirrorer → MediaStore
ui/Joystick.kt          120dp base / 48dp knob, normalized [-1,1] output
ui/Controls.kt          RoundAction/HoldAction/HintPill/SettingsSheet/reactions
ui/ScanUi.kt            scan overlay + MiniMap canvas
ui/StatusOverlay.kt     full-screen ARCore error state
SceneViewerLauncher.kt  opens the creature in Google's Scene Viewer (web URL)
web/index.html          WebXR sibling build (three.js), web/models → assets symlink
```

## Manual device-test checklist

1. **Launch** — scan overlay appears; point cloud + plane outlines render as you
   sweep the room; mini-map grows.
2. **Spawn** — tap a floor/table (or Finish scan and let auto-place fire):
   exactly one dragon pops in facing you. Tapping a wall shows
   "That's a wall — tap the floor or a table".
3. **Joystick** — glides camera-relative, banks into turns, settles to rest on
   release; same idle/walk clip blends — no size pop.
4. **Tap-to-walk** — tap the surface: it walks there; taps beyond the roam
   circle clamp and it arrives instead of pinning at the boundary.
5. **Edge drop** — walk it off a table: falls, lands on the floor, re-anchors.
6. **Vertical blocking** — walls stop it.
7. **Fly/Land** — 🪽 toggles flight; ⬆️/⬇️ change altitude; clips crossfade.
8. **Tricks/ball/jump** — one-shots play on top; ball bounces, it fetches.
9. **Hide/show tracking** — cover the camera: creature holds last pose (PAUSED),
   hint appears, recovers.
10. **Reset** — despawns; in Image mode respawn re-arms only after the image is
    lost once.
11. **Unsupported device** — full-screen error, never a black view.

## Verified on-device (iQOO I2202, Android 14, ARCore)

- Session live: camera feed, scan overlay, point cloud, mini-map, debug HUD
  (`cam=TRACKING`, `spawned`, `pose=`, `v=`, `fly=`, `joy=`).
- Auto-place spawned the dragon on a detected surface; HUD reported a sane pose.
- Earlier flow (image target + joystick glide/bank/settle) verified live.

**Still to eyeball**: surface-mode joystick feel post wall-anchor fix, Dark
Dragon's look/scale/forward axis (`CreatureModel.yawOffsetDeg` if it walks
backwards), edge drops, occlusion on a depth phone.

## Assumptions & caveats

- `web/` is a local WebXR demo: serve the folder over HTTPS or localhost and open
  in Chrome on an ARCore phone (`models` is a symlink into the Android assets).
- `Dark Dragon` is CC-BY (see `THIRD_PARTY.md`) — attribution required if the
  app is distributed.
- Emulators can't run the AR pipeline (see above) — always verify on hardware.
