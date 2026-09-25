# Handoff — Dragon AR App (read this before doing anything)

> Working context for continuing development. Written after a long debug/build
> session. The **active bug and its confirmed fix** are at the top; background
> and hard-won findings below. Don't skip the "Do NOT regress" section.

> **2026-09-26 update — RESOLVED.** The animationSpeed-restart bug below is moot:
> `ClipBlender` now drives the Filament animator directly (`autoAnimate = false`,
> `applyAnimation`/`applyCrossFade` per frame — the recommended approach). Also
> fixed since: **surface mode anchored on vertical planes** (wall anchor rotated
> the whole sim frame — joystick slid the dragon ~5 m up the wall, invisible).
> Placement/walk/auto-place are now `HORIZONTAL_UPWARD_FACING`-only; unreachable
> targets clamp + stall-clear; scan-phase taps place immediately. New default
> creature: `dragon_dark.glb` (Tarisland, CC-BY, 21 clips — see THIRD_PARTY.md).

## Project

- Repo: `/Users/apple/Project/Dragon_Simulation` (git, branch `main`, HEAD `18dae4f`)
- Native Android (no Unity): **Kotlin + Jetpack Compose + ARCore + SceneView/Filament (glTF)**
- App: when the camera sees `target.png` (physical width 0.15 m), one animated
  dragon spawns anchored to the image; an on-screen joystick (bottom-left) flies
  it on the image plane. Reset button bottom-right. Error screen for non-ARCore
  devices.

### Toolchain

| Component | Version |
|---|---|
| AGP | 9.4.1 |
| Kotlin | 2.4.20 |
| Gradle wrapper | 9.8.0 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| JVM target | 21 |
| SceneView + ArSceneView | 4.39.0 (`io.github.sceneview`) |
| Compose BOM | 2026.09.00 |

### Build/test/verify

```bash
cd /Users/apple/Project/Dragon_Simulation
./gradlew testDebugUnitTest          # currently 11/11 pass — must stay green
./gradlew assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk (~26 MB, arm64-only)
adb -s 10BD1C1C7T000HX install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10BD1C1C7T000HX shell am start -n com.dragonsim.ar/.MainActivity
adb logcat -s ARSceneScreen          # clip resolution + session failures
```

- **Test device**: iQOO I2202, Android 14, ARCore 1.56 preinstalled,
  adb serial `10BD1C1C7T000HX`.
- **Target image for testing**: `open app/src/main/assets/images/target.png`
  on the Mac, point the phone at the screen. All four corners must be in frame.
- **Emulators DO NOT WORK for AR** (arm64 API-34: scene camera registers as
  camera device 10, ARCore's emulator profile wants id 0 → native VIO abort in
  libarcore_c.so). Exhaustively tried: environment/virtualscene/imagefile/
  webcam modes, front-camera trick, API 33 image. Don't retry — use the phone.
- **Debug HUD** (green text, top-left, debug builds only) shows live state:
  `img=<name> m=<trackingMethod> s=<trackingState> | model=ok/loading |
  spawned=<bool> arm=<bool> | pose=(x,z) yaw=<deg> p=<pitch> r=<roll>
  v=<m/s> | joy=(x,y)` — one screenshot diagnoses everything. Keep it.

---

## ACTIVE BUG — "dragon changes size when I touch the joystick"

### Confirmed root cause (bytecode-verified in SceneView 4.39.0)

1. `SceneScope.ModelNode` wraps `playAnimation(name, speed, loop)` in a
   `DisposableEffect` keyed on a lambda that captures **all three params**.
2. `ModelNode.playAnimation(index, speed, loop)` always creates a fresh
   `PlayingAnimation(elapsed = 0)` — i.e. it **restarts the clip at frame 0**.
3. `ARSceneScreen.kt` currently passes `animationSpeed = 0.9f + speedNorm*1.2f`
   which changes every frame while dragging → the DisposableEffect lambda is
   recreated every frame → `playAnimation` re-fires → **the clip restarts at
   frame 0 every frame** → frozen/jerking pose, reads as a size/shape anomaly.
4. `ModelNode.setAnimationSpeed(index, speed)` only calls
   `PlayingAnimation.setSpeed()` — mutates speed **without restarting**. This
   is the correct API for per-frame speed changes.

(The earlier cause — hard clip cut `Flying_Idle`→`Fast_Flying`, whose torso
poses differ by ~0.66 model units — was already removed in commit `18dae4f`:
`pickClip(Walk)` returns null for this flyer model so `animationName` is
always `Flying_Idle`. The remaining anomaly is the `animationSpeed` restart.)

### Fix to implement

In `app/src/main/java/com/dragonsim/ar/ar/ARSceneScreen.kt`:

1. Pass a **constant** `animationSpeed = 1f` to the `ModelNode` composable so
   the DisposableEffect never re-fires from speed changes.
2. Get a reference to the `ModelNode` — the composable has an
   `apply: (ModelNode) -> Unit` param; use it to stash the node:
   `var dragonNode by remember { mutableStateOf<ModelNode?>(null) }`.
   (Import: `io.github.sceneview.node.ModelNode`.)
3. Drive flap speed **imperatively**, never via composition params. On each
   frame where speed changes:
   - `speedNorm = hypot(pose.velX, pose.velZ) / Config.MOVE_SPEED_MPS` coerced 0..1
   - find clip index: `node.animator.animationCount` + `getAnimationName(i)`
     matching `idleClip`, or use the `setAnimationSpeed(String, Float)`
     overload which also exists
   - call `dragonNode?.setAnimationSpeed(idleClip, 0.9f + speedNorm * 1.2f)`
   - a `LaunchedEffect(speedNorm)` or a small effect reading pose each frame
     is fine — just not inside the composable's params.
4. Keep `Rotation(pose.pitchDeg, pose.yawDeg + MODEL_YAW_OFFSET_DEG,
   pose.rollDeg)` — verified working on-device.
5. Keep `animationName = idleClip` constant for this model (the
   `walkClip?.let{...} ?: idleClip` logic may stay — walkClip is null here;
   a dropped-in GLB with a real "walk"/"run" clip auto-gets clip-switching).

### If it STILL looks wrong after the fix, next suspects (in order)

a. **Plain perspective**: dragon moves up to 8 cm across the card; at ~30 cm
   viewing distance that's a real ~20% size change. Correct AR behavior —
   explain to user, don't "fix".
b. **Pivot swing**: `centerOrigin = Position(0f,-1f,0f)` bottom-aligns the
   bbox, so pitch/roll rotate around the feet → body swings. If it looks
   wrong, either lower `MAX_PITCH_DEG`/`MAX_BANK_DEG` in DragonMotion.kt or
   rotate around the center (`Position(0f,0f,0f)`).
c. Per-frame recomposition of `ModelNode` params (position/rotation) is fine —
   those are direct setters, not DisposableEffect.

---

## LATEST (2026-09-25 evening) — AR layer rebuilt on SceneView's official sample pattern

User rejected hand-rolled anchoring/depth/voxel code (drift, jumps, invisible
model). Now: scan phase = `PointCloudNode` + plane renderer, progress = tracked
plane area; placement = `findAutoPlacementSurface` → `Plane.createAnchor`
(AUTO_PLACE) or tap a plane; `AnchorNode(visibleTrackingStates = TRACKING+PAUSED)`;
`ShadowReceiverPlane` shadows; creature walks inside the plane polygon and drops
to the plane below at edges. No depth points / instant placement / RoomMap.
Model transform is written in `ModelNode.onFrame` (a wrapper `Node` did not move
the model on-device). Occlusion default off (it hid the model).

## Current architecture notes (2026-09-25) — partly superseded above

- `ar/CreatureSim.kt` owns the world, stepped per AR frame from `onSessionUpdated`:
  placement (Surface tap / "Place in front" via plane→depth→instant placement→
  fallback point / Image target), gravity-aligned anchor (identity rotation),
  steering priority stick > tap-to-go > ball fetch > Follow/Roam, wall rays,
  ground rays (gravity, falls off table edges), flight, jump, ball.
- `ar/Physics.kt` (pure): `stepVertical`, `stepBall`. `DragonMotion` (pure):
  horizontal step, `seek`, `yawToward`, `cameraRelative`. 22 JVM tests.
- `ar/ClipBlender.kt`: `ClipSet.resolve(names)` → idle/walk/run/fly/flyIdle/actions;
  blender drives the Filament Animator from `node.onFrame` (idle↔walk↔run,
  fly mode fade, one-shot actions). Never use SceneView `animationName`.
- `ar/ARSceneScreen.kt` is render-only: AnchorNode → ContactShadow + Node(pose,
  zoom, squash) → ModelNode; SphereNode ball. depthMode AUTOMATIC, instant
  placement LOCAL_Y_UP, occlusion toggle, 60 fps camera if offered.
- `ar/ArCapture.kt`: photo/video via SceneView `SurfaceMirrorer` → MediaStore.
- OPPO CPH2573 (wireless serial `adb-689a084b-N7eCzC._adb-tls-connect._tcp`):
  30 fps max, no EIS, no depth sensor.
- Feature checklist: FEATURES.md.

## Motion model (DragonMotion.kt — pure Kotlin, unit-tested)

`DragonPose(x, z, yawDeg, pitchDeg, rollDeg, velX, velZ)` — velocity carried
between frames so the dragon glides in/out.

`step(pose, input, dt, speedMps, maxRadius)`:
- velocity eases toward `stick × speedMps` (ACCEL_RATE=10 → ~0.1 s constant)
- yaw eases toward `atan2(dx,dz)` (YAW_SMOOTH_RATE=12) only while input held —
  flyer keeps heading during glide-out
- roll = −yawRate × 0.045, clamped ±22° (banks into turns)
- pitch = −speedNorm × 10° (dips with speed)
- position clamped to CLAMP_RADIUS_M circle; velocity recomputed from actual
  displacement at the boundary (no wall-pushing)
- `settle()` snaps residuals <0.01 to exact 0 → parked dragon emits an equal
  pose → zero recomposition churn (data-class equality). Keep this.

`pickClip(names, kind)`:
- Idle → prefers "idle", else index 0
- Walk → **only** literal "walk"/"run" match, else **null** (flying creatures
  have no walk clip; null → caller modulates animation speed instead of
  hard-cutting poses)

Frame loop in `DragonSimApp.kt` calls `step()` **every frame** while spawned
(gating on isMoving would freeze glide-out mid-flight). Joystick semantics:
normalized [-1,1], screen +Y down; push up → image-local −z = "up" the image.

## Config (Config.kt)

```
TARGET_IMAGE_ASSET     images/target.png      DRAGON_SCALE_UNITS  0.1f
TARGET_IMAGE_NAME      target                 DRAGON_Y_OFFSET     0f
TARGET_IMAGE_WIDTH_M   0.15f                  MODEL_YAW_OFFSET_DEG 0f
MOVE_SPEED_MPS         0.1f                   CLAMP_RADIUS_M      0.08f
EMULATOR_POSTER_*      debug-only, in src/debug/assets
```

`CLAMP_RADIUS_M` was 0.3 → dragon could hover 2 card-widths off a 15 cm card
(reported as "vanishing"). 0.08 keeps its centre over the card. **Don't raise it.**

## Asset

`app/src/main/assets/models/dragon.glb` — Quaternius **"Dragon Evolved"**, CC0.
Clips: `CharacterArmature|Flying_Idle`, `Fast_Flying`, `Death`, `Headbutt`,
`HitReact`, `No`, `Punch`, `Yes`. **No walk clip exists in any free Quaternius
dragon** (checked all on poly.pizza — the walking candidates are Birb, Dino,
Blue Demon, Big arm — none are dragons). No scale channels, no root motion —
animations only move Torso/Body1 bones. To swap: drop a GLB with `idle` +
`walk`/`run` clips at the same path; `pickClip` finds them automatically.
Unity Asset Store dragons (e.g. "Dragon for Boss Monster") are FBX+Unity —
not usable without conversion.

## Do NOT regress (all verified on-device)

- **Gate everything on `trackingMethod == FULL_TRACKING`**, never
  `trackingState` — ARCore keeps TRACKING via LAST_KNOWN_POSE after the image
  leaves the frame; gating on trackingState broke "image lost" and the reset
  latch (commit 6a1bade).
- **Superseded (user request, 2026-09-25):** the creature is no longer hidden
  when the image leaves view. On spawn an `Anchor` is created from the image
  (`image.createAnchor(centerPose)`) and the creature renders under
  `AnchorNode` — world tracking keeps it steady. Spawn still gates on
  FULL_TRACKING; reset detaches the anchor.
- Reset latch: `spawnAllowed` re-arms only after the image is lost once → no
  instant respawn while still pointing at the image.
- Joystick renders only after spawn; status chip hides while tracking.
- Debug-only emulator poster stays in `src/debug/assets` — never in release.
- ABI filter: arm64 only.
- Commit trailer: `Generated with [Devin](https://devin.ai)` +
  `Co-Authored-By: Devin <158243242+devin-ai-integration[bot]@users.noreply.github.com>`,
  commit via `git commit -F -` heredoc.

## Verified live on the iQOO

- Image detection → exactly one dragon spawned anchored to `target.png`
- Live joystick drag (via HUD): pose→(0.03,0.00), yaw→−106°, roll→+14.7°,
  pitch dip, all settled to exact 0 on release (glide-out works)
- `m=LAST_KNOWN_POSE` → dragon hid + "Image lost — point back at it" banner
- Error screen path verified on emulator ("AR failed to start: FatalException")

## Not yet verified by eye

- Whether the size-change complaint is fully gone after the
  `setAnimationSpeed` fix — test on device with a drag.
- Banking/pitch looking natural from a normal viewing angle.
- Reset → move-away → re-approach respawn latch end-to-end.

## Commit history

```
18dae4f  Rework dragon motion: smooth flight, no clip-switch size pop (HEAD — has the animationSpeed restart bug described above)
a2fa2fa  Debug HUD reporting live AR state per frame
5ee8c07  README tracking-method fix + radius change
6a1bade  Fix dragon vanishing + reset latch (FULL_TRACKING gating)
235fc86  Debug-only emulator poster
63e2b18  Initial commit: full app
```
