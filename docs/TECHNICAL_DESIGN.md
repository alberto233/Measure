# Technical Design

Companion to [ACCURACY.md](ACCURACY.md), which covers the measurement mathematics. This
document covers everything else: stack, structure, data, and how we test it.

## 1. Platform baseline

| Choice | Value | Why |
| --- | --- | --- |
| Language | Kotlin | |
| UI | Jetpack Compose | Including the 2D plan editor, drawn on `Canvas` — no third-party graphing library needed |
| `minSdk` | 26 (Android 8.0) | ARCore itself allows 24, but the practical ARCore device population is 8.0+ and 26 avoids a tail of workarounds |
| `targetSdk` | Current | |
| AR perception | ARCore via Google Play Services for AR | Actively maintained; last updated mid-2026, and Depth API reaches roughly 88% of active devices as of May 2026 |
| AR rendering | SceneView (Compose-native, Filament-backed) | The only actively developed Compose-native option. See the risk note below |
| Persistence | Room | Small relational model; migrations matter once users have saved projects |
| DI | Hilt | |
| Async | Coroutines and Flow | |
| Serialization | kotlinx.serialization | Project JSON export/import |
| Build | Gradle with version catalogs, convention plugins | |

**On SceneView.** It is a community project rather than a Google one, so it carries churn
and abandonment risk. The mitigation is architectural: everything ARCore- and
rendering-related lives behind a narrow interface in `:ar`, and the rest of the app never
imports SceneView types. Our rendering needs are genuinely modest — lines, spheres,
billboarded text labels and a shaded plane overlay — so if SceneView becomes a problem, a
direct Filament renderer or even plain OpenGL along the lines of Google's `hello_ar`
sample is a realistic fallback rather than a rewrite.

## 2. Module structure

```
:app                     assembly, navigation, DI wiring
:core:model              geometry primitives and the project model — pure Kotlin
:core:geometry           snapping, loop closure, the constraint solver, area — pure Kotlin
:core:units              length/area formatting and imperial parsing — pure Kotlin
:core:data               Room database, repositories
:core:designsystem       theme, shared composables
:ar                      ARCore session, hit-test ranking, sampling, plane management
:feature:capture         the AR capture screens
:feature:editor          2D plan editor
:feature:projects        project list, project detail
:feature:export          PNG, PDF, SVG, DXF, CSV, JSON writers
```

The four pure-Kotlin modules are the point of this structure. They hold the logic most
likely to be wrong — the solver, the closure adjustment, imperial fraction parsing, DXF
generation — and they run on the JVM in milliseconds with no emulator and no device. That
is what makes it practical to develop the accuracy work (M2) in parallel with, and ahead
of, the AR capture UI.

`:ar` depends on `:core:model` and nothing else in the app. `:feature:*` modules do not
depend on each other.

## 3. Data model

Everything is stored in **metres as `Double`** and formatted only at display time. Mixing
storage units is the most common source of unit bugs in this kind of app, and the
formatter is cheap to call.

```kotlin
Project(id, name, createdAt, updatedAt, unitPreference)
  └── Level(id, projectId, name, elevation)
        └── Room(id, levelId, name, roomType, ceilingHeight, transform)
              ├── Corner(id, roomId, index, x, y, sigma, isSnapped, isLocked)
              ├── Wall(id, roomId, fromCornerId, toCornerId, lockedLength?, captureMethod)
              │     └── Opening(id, wallId, kind, offsetAlongWall, width, height, sillHeight)
              └── Annotation(id, roomId, kind, x, y, text, photoUri?)

Measurement(id, projectId, kind, points, value, sigma, label, createdAt)
```

Notes on the shape of this:

- Room geometry is a **2D polygon plus a height**, not a 3D mesh. Every corner has already
  been projected onto the floor plane (ACCURACY M2), so the third dimension carries no
  information and storing it would only invite inconsistency.
- `Room.transform` is the room's placement within the project — position and rotation in
  the project's coordinate frame. Rooms are captured in their own frames and assembled
  later (M8); keeping the transform separate means assembly never touches the captured
  geometry.
- `sigma` on each corner is the per-point uncertainty from multi-frame sampling. It is
  not decoration: it becomes the position-residual weight in the constraint solver.
- `isLocked` and `Wall.lockedLength` are how user knowledge enters the solve. A locked
  wall is a hard constraint.
- `Measurement` is deliberately independent of `Room`, because the "will this fit" use
  case is a one-off distance with no room attached and should not require creating one.
- Walls are zero-thickness and represent interior faces in v1. Flagged as an open question
  in the product plan because changing it later touches this model.

## 4. AR pipeline

Per frame, in `:ar`:

1. Acquire the ARCore frame; read camera tracking state and failure reason.
2. Update the plane registry — merge subsumed planes, maintain the dominant floor
   (ACCURACY M2).
3. Hit-test the screen centre; rank the results plane-first (M1).
4. Compute a quality score from tracking state, failure reason, feature count and plane
   coverage; publish it as state for the HUD (M5).
5. Emit the reticle pose and quality as Compose state.

On tap, a sampling coroutine collects hit results across ~15 frames, discards frames where
tracking degraded, takes the median, computes dispersion, and either creates an ARCore
`Anchor` or rejects the point with a reason (M3).

Anchors matter: holding an `Anchor` rather than a bare pose lets ARCore correct the point
retroactively as its understanding of the scene improves, which is free accuracy. Anchors
are a limited resource, so release them when a room capture completes and the geometry has
been solved into the room's own frame.

**Session lifecycle is a first-class concern.** It is the single most crash-prone part of
an ARCore app and the place our competitors visibly fail. Explicit handling for: pause and
resume, backgrounding, permission revoked mid-session, the camera being taken by another
app, ARCore updating itself underneath us, and `UnavailableException` in all its variants.
Capability checks happen *before* a session is ever opened.

**Capability tiers**, decided at M0 and surfaced honestly to the user:

| Tier | Requirement | What the user gets |
| --- | --- | --- |
| Full | ARCore + Depth API | Everything |
| Basic | ARCore, no Depth | Plane-based measuring; occlusion and some hit-test quality lost |
| Manual | No ARCore | Manual plan drawing and editing only, with the AR features clearly explained as unavailable |

## 5. Export formats

All writers live in `:feature:export` and are pure functions from the project model to
bytes, which makes them golden-file testable.

**DXF** is the one that matters commercially — it is CamToPlan's headline feature and the
reason light professionals pay. DXF R12 is plain ASCII with a simple section structure;
`LINE`, `LWPOLYLINE` and `TEXT` entities on named layers (walls, dimensions, openings,
labels, annotations) cover everything we produce. Hand-written, no dependency.

**PDF** via Android's built-in `PdfDocument`: a plan page with title block and scale bar,
plus a measurement schedule page. **SVG** is hand-written XML. **PNG** renders the same
Compose `Canvas` drawing code as the on-screen plan to an offscreen bitmap, so the export
and the editor cannot drift apart. **CSV** is wall lengths, room areas and perimeters,
for spreadsheets and quotes. **JSON** is the full project via kotlinx.serialization, for
backup and transfer, and it is the natural basis for any future sync.

Sharing goes through the Android share sheet with a `FileProvider`.

## 6. Testing

**JVM unit tests** carry the weight. The constraint solver, loop closure, snapping, area
computation, imperial parsing and formatting, and all export writers are pure Kotlin and
must be thoroughly tested there. Synthetic rooms with injected noise and drift, asserted
against known ground truth (ACCURACY §4).

**Golden-file tests** for DXF, SVG and CSV output — these formats are exactly the kind of
thing that breaks silently.

**Recorded-session tests** using ARCore's Recording and Playback API, replaying real
captures deterministically so end-to-end accuracy can regress-test on genuine data.

**Compose UI tests** for the editor and project list. The AR view itself is not
meaningfully unit-testable; keep logic out of it and in `:ar` and `:core:geometry` where
it can be tested.

**CI** on GitHub Actions: assemble, lint, JVM unit tests on every push. Instrumented tests
are a later addition once there is something worth running on an emulator — and the AR
paths cannot run on one anyway.

## 7. Sequencing note

The dependency that shapes the schedule: `:core:geometry` needs nothing from ARCore, so it
can be built and validated against synthetic data before the AR capture flow exists. Doing
it in that order means the product's central technical claim — that constraint solving
turns phone-grade measurements into credible floor plans — is proven or disproven in week
three rather than month four.
