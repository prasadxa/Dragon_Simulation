# Feature checklist

Sources: survey of AR creature apps (AR Dragon, Peridot, Pokémon GO AR+,
Jurassic World Alive, Monster Hunter Now, Google 3D Animals) + user requests.
`[x]` = implemented in code, `[~]` = implemented but not yet verified on-device,
`[ ]` = to do.

## Placement & tracking
- [x] Image-target spawn (`target.png`)
- [~] Surface mode: horizontal + vertical plane detection, tap a surface to place
- [~] "Place in front" — spawn without image or plane (instant placement / depth / fallback point)
- [~] World-anchored creature (stays put when image/plane leaves view)
- [~] Gravity-aligned anchor frame (creature stands upright even from a vertical image)
- [~] Tap-to-go: tap a surface and the creature walks there
- [~] Autofocus + EIS + 60 fps camera where supported
- [~] Depth API (AUTOMATIC) where supported → depth hit tests on any surface
- [~] Off-screen arrow pointing to the creature
- [~] Coaching hints (scan surfaces, tap to place, tracking-failure reasons)
- [~] Plane visualisation toggle
- [ ] Persistent / cloud anchors (creature still there next launch)

## Physics & world interaction
- [~] Gravity: walk off a table edge → fall to the floor/surface below and land
- [~] Ground following on any detected surface (plane or depth)
- [~] Jump (impulse + gravity)
- [~] Wall blocking against vertical planes / depth
- [~] Throwable ball with gravity + bounces + friction; creature fetches it
- [~] Landing squash-and-stretch
- [ ] Full mesh collision (SceneView DepthMeshCollision) for furniture edges

## Creature behaviour & interaction
- [x] Idle ↔ locomotion crossfade weighted by speed
- [~] Walk ↔ run 3-way blend (idle → walk → run) with phase-synced cycles
- [~] Fly mode for creatures with a fly clip (hover, altitude up/down, gravity off)
- [~] Tap the creature → reaction animation + haptic
- [~] Action menu: every extra clip in the GLB playable as a one-shot
- [~] Behaviours: Manual, Follow me (walks toward the phone), Wander (free roam)
- [~] Faces the camera when idle
- [x] Creature picker (Dragon, Classic Dragon, Fox)
- [~] Spawn pop-in animation
- [~] Happiness meter (pet / feed / tricks raise it, drains over ~10 min), persisted
- [~] Floating hearts / 😋 / ⭐ reactions at the creature
- [ ] Hunger meter
- [ ] Growth over days, cosmetics (hats), tricks training

## Visual realism
- [x] Environmental HDR light estimation (SceneView default)
- [~] Contact shadow under the creature (stays on the ground while jumping/flying)
- [~] Depth occlusion — real objects hide the creature (toggle)
- [x] Pinch-to-zoom 0.5×–6×
- [ ] Particle effects in 3D (fire breath)

## Capture & sharing
- [~] Photo capture (clean render, no UI) → Pictures/DragonAR
- [~] Video recording → Movies/DragonAR
- [~] Share sheet after capture
- [~] Hide-UI toggle for clean framing

## UX
- [~] Settings sheet (occlusion — default off, planes, shadow, face camera, HUD)
- [~] Remembers creature, mode, settings, happiness between launches
- [~] Mode switch Image / Surface
- [~] Haptics on place / tap / land
- [ ] Sound effects (need CC0 audio assets)
- [ ] Onboarding / hatching intro
- [ ] Battery/heat: pause AR when backgrounded (SceneView handles lifecycle)
