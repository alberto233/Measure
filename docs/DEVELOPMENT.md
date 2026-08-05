# Development

Everything needed to be productive on this repository, including the constraints that
are not obvious from the code and the reasons behind every version pin. Written so a
fresh session, or a new contributor, can start without re-deriving any of it.

## 1. Current status

| Area | State |
| --- | --- |
| Product plan, features, technical design, accuracy strategy | Written — see the other files in `docs/` |
| `:core:units`, `:core:geometry`, `:core:export` | Implemented, 239 tests passing, CI green |
| `:ar` | ARCore session, hit-test ranking, multi-frame sampling, GLES renderers |
| `:core:data` | Room database, repository. Autosave, project list queries |
| `:core:designsystem` | Palette and the shared plan renderer |
| `:feature:capture` | M1 capture, M3 room capture, M6 ceiling detection |
| `:feature:projects` | The home screen: saved plans with drawn thumbnails |
| `:feature:editor` | M5 plan editor, M6 openings and volume, M12 measuring on the plan |
| `:feature:export` | The share sheet and the FileProvider that serves the file |
| `:app` | Assembly. The capability report is now a screen reachable from home |
| CI | Green. Builds the APK and publishes it to a rolling prerelease |
| Next | M13 finding a plan — see §8 |

**M1 is validated on the A36.** Camera, planes, reticle, gating and point-to-point
measuring all work on hardware, and a short measurement matched a tape. The thresholds
were retuned off that session — see §8 for what is still a guess.

**M3 is validated on the A36.** Floor lock is immediate, the live plan matches the room,
and a closed four-corner capture came in at 0.4% misclosure. Per-wall accuracy against a
tape is still unmeasured — see `docs/ACCURACY.md` §4.

**M4 is implemented and compiles; the database has not been exercised on hardware.** Room
validates every query at compile time, so the SQL is known to be well formed, but nothing
has yet written a row on a phone.

**M5 is implemented and untested on hardware.** The plan editor pans, zooms, selects walls
and corners, moves corners, and locks a wall to a hand-measured length — which re-solves
the room around that one certain number, the payoff `docs/ACCURACY.md` M8 was built for.

**M6 is implemented. Field tested twice.** The first session found three faults, all
fixed: plumb needed a surface to hit and so failed on the white ceilings it exists to
measure (it now derives the point from gravity and the aim ray, hitting nothing); the
plane overlay drew every tracked plane outlined and buried the camera image in a
cluttered room; and the openings panel was translucent, uncapped and centred every new
opening on the same spot, so the same door got added four times without visible effect.
The second session confirmed all three, and found three more, now fixed:

- **The loop closed too easily.** A tap within 45 cm of the first corner ended the room,
  which is wide enough to swallow a real corner beside the doorway the walk began at.
  The rule now lives in `LoopClosure` with the rest of the capture rules, and the radius
  is 18 cm — a closing tap has to be a second reading of *the same corner*, because the
  gap between the two readings is the drift the compass rule distributes. Between 18 cm
  and 75 cm the tap is taken as a corner and the interface says so.
- **Measurements were unreadable on the plan.** A plumb measurement is almost all height,
  and a floor plan discards height, so two room heights drew as two bare dots. They now
  draw as a height symbol, carry their value as a label, and are listed with their values
  in the editor panel when nothing is selected.
- **Doors and windows were coloured stripes.** They are now drawn the way a plan draws
  them: the wall cut away, jambs across the opening, a leaf and swing arc for a door
  (swinging into the room — `Segments.inwardNormal` probes for which side that is, since
  a room walked clockwise and the same room walked anticlockwise have opposite windings),
  and a framed double line for a window.

**The second session's fixes are validated.** Tight loop closing, the measurement list
and height symbols, and the architectural door and window symbols all behaved. Two
interface faults came out of the same session and are fixed: the editor's text fields
were a shade off the panel behind them and read as gaps rather than inputs, and the
keyboard pushed the editing panel to the top of the screen because the window resized for
the IME *and* `safeDrawingPadding` subtracted it again. The activity no longer resizes;
Compose owns the inset.

The plumb measurement still breaks beyond about 2 m of range — see §8.

**M11 wall-face capture was built, field tested twice, and removed.** It is not in the
tree; this note is here so nobody rebuilds it without knowing what happened.

The idea was to point at each wall in turn and intersect consecutive pairs, so a corner
hidden behind furniture never has to be aimed at. The geometry worked and was tested. The
perception did not.

- **First session: nothing at all.** ARCore fits no vertical plane to a plain painted
  wall, because plane detection tracks visual features and a blank white wall has none.
  That is also the room whose corners are hidden, so the mechanism failed hardest exactly
  where the feature was needed.
- **Second session, with a depth fallback: spotty, and wrong when it did fire.** A grid of
  28 hit tests was fitted to a line on the floor plan by RANSAC — a genuine second
  mechanism, not a retuned threshold. It produced a wall sometimes, and the walls it
  produced were not where the walls were.

The second result is the one that settled it. A feature that silently fails is
disappointing; a feature that intermittently returns a *wrong* number is worse than not
having it, in an app whose entire positioning is that its numbers can be trusted
(`docs/PRODUCT_PLAN.md` §5). There was no threshold left to move: both mechanisms were
being asked for information the sensor does not have on a textureless surface at
domestic ranges.

What this rules out, so it does not get retried: ARCore vertical planes on painted walls,
and motion-derived depth on the same, on a mid-range handset with no time-of-flight
sensor. A device with a lidar-class sensor is a different experiment. So is a learned
plane-from-image model, which is a different project.

**M12 measuring on the plan is implemented and untested on hardware.** Tap **Measure**
in the editor, then tap two points; corners and walls pull the point onto them. Schema is
now v5, with `plan_measurements` added by `MIGRATION_4_5`.

Two design decisions carry the weight:

- **Ends are stored as anchors, not coordinates.** A corner reference, or a wall plus how
  far along it — the same choice `OpeningEntity` makes, for the same reason. Locking a
  wall or dragging a corner re-solves the whole polygon, and a measurement pinned to
  absolute coordinates would keep displaying a number while no longer pointing at what it
  measured. `PlanSnapper.resolve` places anchors against the plan as it currently is, and
  returns null when the geometry has gone, in which case the editor stops drawing it and
  says so rather than showing a stale line.
- **It is visibly not a measurement.** Drawn in amber where captured measurements are
  white, labelled with a leading `~`, and the panel says "off the plan, not measured in
  the room". The tolerance is real: a free end contributes `PLACEMENT_SIGMA` (5 cm),
  because a finger on a plan is a measurement of nothing, and no correlation discount is
  applied even between two corners of one room. When either end sits on a corner the
  rectilinear solve moved, the panel says that too.

**Field tested twice.** The second session reshaped the view around one principle: show
one number at a time, and only when asked. Dimension lines and ticks are always drawn
because they cost nothing to look past and are what there is to aim at; no number appears
until a run is tapped, and tapping one projects dashed guides back across the plan so it
is obvious which stretch of building it covers. Distances the user draws are visible only
in this view, not on the plan, and drawing a new one is a deliberate `+ Distance` step
that is refused until the previous one has been kept or discarded — the same fault as the
door added seven times, which was also a control that acted without asking whether the
last one was wanted.

**Field tested once before that, and it found the flaw in the original idea.** Tapping two points accurately
enough to get a *straight* line is beyond a finger on a phone-sized plan: measuring a bed
to a wall produced a line a few degrees off perpendicular, which is not a rougher version
of that distance but a measurement of something else, and it always reads long. Two
changes follow.

**Dimension strings.** Entering Measure now draws the plan's overall sizes around it, in
the notation a floor plan uses: an overall span with the runs between corners beneath it,
on witness lines outside the drawing. Most people open a plan to find out how wide the
room is, and that should not require a steady finger — the geometry already knows.
`DimensionChains` builds them along the plan's own dominant direction rather than the
screen's axes, because which way a room points depends only on which way the user was
facing when they started capturing. Angles are quadrupled before averaging and quartered
after, which folds away the four-fold symmetry of a grid; averaging raw angles would put
a square room's dominant direction at 45 degrees, exactly wrong.

**Straightening.** A free end is now pulled square to the wall the measurement started
from, or to the plan's grid, when it is within 12 degrees of it. This is the same argument
as `MeasurementMode` on the capture screen and not tidying: the wall's direction is known
exactly, so the error a finger contributes along the wall can simply be removed. A snap
that moves the point more than 15 cm says so. Ends that landed on a corner or a wall are
never straightened — those were aimed at something, and only the "somewhere over here" end
carries finger error worth removing.

The UX was built rather than retrofitted, since three of the last four rounds of field
faults were interaction faults rather than maths ones. Concretely: measuring is a mode
with a lit button and an on-screen banner, never an ambiguous tap; the first end is drawn
large the moment it lands and the banner names what it caught, because a touch screen has
no hover and the second tap is what commits; "Redo point" is offered before that second
tap; two taps in the same place are refused with a reason instead of saved as a dot; and
the end marks are three different shapes so a corner anchor, a wall anchor and a free
point cannot be confused for each other on the drawing.

**M7 export is implemented for the text formats and untested on hardware.** **Send** in
the plan editor offers SVG, DXF, CSV and the project's own JSON, writes the file into a
cache directory and hands it to the system share sheet.

All four exporters live in `:core:export`, which is pure Kotlin and has no Android
dependency at all. That is worth more here than elsewhere: every one of these formats is
text, so the whole of it is unit tested, and a single malformed character produces a file
that opens as an error dialogue in someone else's software rather than as a plan — which
no device test would catch either, since the failure happens in AutoCAD a week later.

Three decisions worth keeping:

- **The DXF is full size in metres, and says so.** CAD drawings are always full size;
  scale is applied at printing. `$INSUNITS` is set to metres because most readers assume
  millimetres when it is absent, which would make a five-metre room five millimetres
  across. The dialect is the minimal R12 ASCII form — every feature beyond it is another
  way for one reader in ten to reject the file.
- **The SVG states its scale on the drawing.** A plan whose relationship to reality is
  unstated is a picture; one that says 1:50 is something a person can measure off.
- **Every number is formatted in `Locale.ROOT`.** On a phone set to Spanish the default
  locale writes a decimal comma, and an SVG coordinate or a JSON number containing a comma
  is not the number it was meant to be. A test pins this by parsing the output under
  `es-ES`, because it is exactly the bug that would only ever appear on somebody else's
  device.

The `FileProvider` is scoped to one cache directory. Rooted any wider it would hand every
share target a readable path into private storage, database included.

**PDF and PNG are now there too**, rendered through Android's canvas by a single
`PlanDrawing` used by both — one implementation, because a plan drawn twice by two pieces
of code will eventually be drawn two different ways and the version checked against a tape
will be the wrong one. The PDF is a real A4 page in points, turning landscape when the plan
is wider than it is tall; a page that is not a paper size prints scaled by an unknown
amount, which for a floor plan is worse than useless. Both are drawn light: an export ends
up printed or on a laptop, and the app's dark palette would come out of a printer as a page
of toner.

**Field tested once.** SVG failed to share on the A36 — not a rendering fault but a MIME
one. `image/svg+xml` is correct and registered, and nothing on a typical handset claims it,
so the chooser had nothing to offer and the export read as broken. DXF was worse: it was
being sent as `application/dxf`, which is not a registered type at all (the real one is
`image/vnd.dxf`). Both are fixed, and the share now falls back to
`application/octet-stream` when nothing resolves the exact type — a file manager or a mail
client will always take that.

**Field tested twice.** PDF and PNG came out right. Three things followed:

- **Exports carried no dimensions and no drawn distances.** Both are now on every format.
  Unlike the app, an export shows *every* dimension run at once — on screen a number can
  be asked for and a plan carrying all of them is unreadable on a phone, but on paper
  there is nobody to ask, and a drawing that does not carry its dimensions is a picture of
  a room rather than a description of one. Distances drawn on the plan were reaching no
  format at all, which quietly made every file less than what the user had on screen. They
  are exported now, dashed and tilde-marked in the drawings and in their own table in the
  CSV and JSON, so the difference between observed and derived survives a format that
  strips every visual cue.
- **Editing gave no feedback.** Locking a wall, setting a ceiling height, resizing an
  opening and renaming a room all did their work silently, so the only way to know a
  button had worked was to notice a number change elsewhere. Every one confirms now, and
  a success is teal where a refusal is amber — confirming success in the colour reserved
  for problems teaches people to read every message as a problem, and then to stop
  reading them.
- **PNG was labelled "Image"** beside four formats that name themselves. It says PNG.

**Sharing an SVG still fails, and the cause is not known.** Recorded in §8 rather than
guessed at again.

`PlanGeometry` was also lifted out of `SvgExporter`, where "where does this door sit on
this wall" had started life as a private helper and then had to be reached by the canvas
renderer too.

Nothing substitutes a typical 2.4 m when no ceiling height is known — a guessed paint
estimate looks exactly like a measured one on screen, and the user would have no way to
tell them apart.

## Rooms captured on separate visits were drawn as if they had been surveyed together

Found by reading the code rather than by a field test, which is the only reason it is not
still there. It was silent, it was wrong, and it would have been wrong in the way that
looks most like being right.

`CaptureActivity` creates a `CaptureViewModel` per launch, which creates a
`MeasureArController`, which creates an ARCore `Session`. **A new session puts the world
origin wherever the phone happens to be and the axes wherever it happens to be pointing.**
So a room captured on Tuesday and a room captured on Wednesday are described in two
unrelated coordinate systems, and nothing between the capture and the plan re-centred
them. The editor drew both at their stored coordinates and produced a floor plan showing
the two rooms overlapping, or nine metres apart, depending on where the user was standing
when they opened the app. Every number *inside* each room was right. The relationship
between them was invented, and drawn with exactly the same confidence as the measured
parts.

Capturing two rooms without leaving the capture screen — "New room" — was always correct,
because that is one session. It is the second *visit* that breaks, which is precisely the
"capture a multi-room flat" case in `docs/PRODUCT_PLAN.md` §3.

There is no fix that recovers the true relationship: the phone genuinely does not know
where the second room is. So the app stops pretending it does, in four places:

- **`RoomEntity.captureSession`** records which ARCore world frame a room's corners are
  in (`MeasureArController.worldFrame`, regenerated in `createSession` and nowhere else).
  Schema version 6.
- **`RoomPlacement`** sets a room from a new frame down clear of everything already on the
  plan, in a row with a metre of air. Translation only, never rotation — the room's own
  geometry is real and must survive untouched, and a guessed rotation would be a second
  invented number stacked on the first. Nobody reads a room floating a metre off the
  others as a survey; an overlap, they would.
- **Long-press a room and drag** moves it (`RoomDao.translateCorners`, through
  `EditorViewModel.beginRoomMove`). Deliberately without snapping to neighbouring walls:
  the whole point is that a placement should be traceable to whoever made it, and helping
  the user snap rooms flush would make the app's guess look like a measurement again.
  Observations move with the solved corners, or the room would spring back to its capture
  coordinates the first time a wall was locked.
- **It is stated.** A note above the plan, and one sentence — `ExportablePlan.arrangementCaveat`,
  written once so six formats cannot make six different promises — carried into the SVG,
  the PDF, the PNG, the DXF (on its own `NOTES` layer) and the CSV, with a boolean
  `arrangementMeasured` in the JSON. An export is where every hint the screen gave is
  lost, and the file outlives the conversation that produced it.

One consequence is deliberately left alone: a dimension string spanning two rooms from
different captures reports the drawing's extent, which is not a measured distance until
the user has arranged the rooms. Suppressing it would be wrong the other way round the
moment they have, and the app cannot know when they are done. The note covers it.

**What is validated on the A36:** M1 point-to-point (matched a tape), M3 room capture
(0.4% misclosure on a closed loop), M4 persistence (plans survive, thumbnails correct),
M5 editing (corner drag, rename, re-solve), and the first round of M6 fixes — plumb
without a surface, the quietened plane overlay and the openings panel all behaved.
**What is not:** every migration, the locked-wall re-solve actually improving other
walls, and ceiling detection working at all.

**Confirmed on real hardware** (Samsung Galaxy A36 5G, Android 16 / API 36):
ARCore supported and installed, Depth API **yes**, Raw Depth API **yes**. No capability
tier is blocked, so the full plan is achievable on the target device.

## 2. The environment

**Android builds work locally. The egress restriction that shaped this repository has
been lifted.** `./gradlew :app:assembleDebug` completes in the container, from a clean
build, in about two minutes.

### What that changes

- Android code no longer has to be compiled in CI to find out whether it compiles. Six
  consecutive CI failures were once spent on build plumbing that a local compile catches
  instantly; that loop is gone.
- Compose and AndroidX are now usable, which is what made the M1 capture screen possible
  as designed rather than as a plain-views approximation.
- The "Surface compiler errors" step in CI is no longer load-bearing and may be removed
  whenever it stops paying for itself. It is kept for now because Actions logs are still
  served from a host the container cannot read, so only the *tail* of a job log is
  fetchable, and Kotlin prints diagnostics before the failure marker where the tail
  cannot reach.

### Reproducing this environment

Network policy is fixed when the VM starts, so a session that lacks this needs to be
**restarted** after changing it.

1. Go to **claude.ai/code**.
2. Click the **cloud icon** showing the environment name, in the row above the message
   box. (There is no settings page for personal environments.)
3. Hover the environment, click the **gear** icon.
4. Set **Network access** to **Custom**.
5. Under **Allowed domains**, add:
   ```
   dl.google.com
   maven.google.com
   ```
   Both are needed: Gradle connects to `maven.google.com`, which redirects to
   `dl.google.com`, and the redirect target must be allowed too.
6. **Tick "Also include default list of common package managers."** Leaving it unchecked
   restricts the session to *only* those two domains, breaking Maven Central and Gradle's
   own distribution.
7. Add the setup script in §6, which preinstalls the Android SDK.
8. Save, then start a **new** session.

`ANDROID_HOME` is written to `/etc/environment` by that script, which shells do not
always source. If Gradle cannot find the SDK, export it for the command:

```bash
export ANDROID_HOME=/opt/android-sdk
```

## 3. Build and test

```bash
# The fast loop. Seconds, no Android SDK needed, and where the measurement logic lives.
./gradlew :core:units:test :core:geometry:test

# The whole app.
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug
```

`org.gradle.configureondemand=true` in `gradle.properties` is what lets the first command
run without configuring the Android modules at all. Keep using it: the pure-Kotlin loop
being fast is what makes the accuracy work practical to iterate on.

## 4. Version pins, and why

Do not bump these casually. Each one is load-bearing.

| Pin | Value | Reason |
| --- | --- | --- |
| Kotlin | **2.3.21** | **Capped by AGP.** AGP 9.3 compiles Kotlin with its own bundled compiler on the 2.2 line, which reads class metadata up to 2.3.0. Kotlin 2.4.x emits 2.4.0 metadata, making the core modules unreadable from `:app`. This caps Kotlin for the *whole repo*, including modules that never touch Android |
| AGP | 9.3.0 | Current stable. Requires Gradle 9.5+ and JDK 17+ |
| Gradle | 9.5.0 | AGP 9.3's minimum |
| JDK / toolchain | 21 | Only 21 is installed in the container; satisfies AGP's 17 minimum |
| `compileSdk` / `targetSdk` | 36 | |
| `minSdk` | 26 | ARCore allows 24, but the practical ARCore population is 8.0+ |
| ARCore | 1.54.0 | |
| Compose compiler plugin | **2.2.10** | **Must equal the Kotlin compiler AGP embeds**, which is `kotlin-compiler-embeddable` **2.2.10** inside AGP 9.3.0 — *not* the `kotlin` version above. AGP 9 compiles Kotlin with its own compiler and a Compose plugin from another line will not load into it. AGP also *requires* the plugin: `buildFeatures.compose = true` alone fails the build |
| Compose BOM | 2026.06.01 | |
| `androidx.lifecycle` | **2.10.0** | **Capped by `compileSdk`.** 2.11.0 declares a minimum compileSdk of 37 and fails AAR metadata checking against 36. Bumping to 2.11 means bumping `compileSdk` to 37 in every module and installing that platform in the setup script |
| `androidx.activity` | 1.13.0 | |
| KSP | **2.2.10-2.0.2** | **Must match AGP's embedded Kotlin**, exactly as `composeCompiler` does, and for the same reason: it is a compiler plugin loading into AGP's own compiler |
| Room | 2.8.4 | Schemas exported to `core/data/schemas` and checked in |

### The root build declares no plugins

This is deliberate and was arrived at the hard way. Three arrangements were tried:

1. `kotlin.jvm` alone at the root — Kotlin's JVM and Android plugins ship in one
   artifact, so this put that jar on the classpath unversioned and `:app` requesting
   `kotlin.android` by version failed compatibility checking.
2. `kotlin.android` at the root with AGP in `:app` — the Kotlin plugin then loaded from
   the root classloader while AGP loaded from `:app`'s child classloader, and a parent
   cannot see a child's classes. Failed with `ClassNotFoundException: BaseVariant`.
3. **Bare root.** Each module resolves its own plugins in its own scope. Core modules
   resolve only `kotlin.jvm` from Maven Central; `:app` resolves only AGP.

Note that **AGP 9 compiles Kotlin itself** and *rejects* the standalone
`org.jetbrains.kotlin.android` plugin outright. There is no `kotlin-android` entry in the
version catalog for that reason. Configure Kotlin via a top-level `kotlin { compilerOptions { … } }`
block; `android { kotlinOptions { … } }` was removed in AGP 9.

The Compose compiler plugin is the one exception to "Android modules apply only AGP":
every module with Compose code applies `org.jetbrains.kotlin.plugin.compose` as well,
because AGP demands it. It loads cleanly because it is a *compiler* plugin — it hooks
into AGP's own Kotlin compiler rather than contributing a Gradle plugin that needs AGP's
classes — which is exactly why the version has to match that compiler and not the
`kotlin` pin.

> **Every module containing a `@Composable` needs the plugin and `buildFeatures.compose`,
> including one whose only Compose code is a single `setContent { }` call.**
>
> This cost an evening. `:app` had neither, on the reasoning that the composables all
> live in `:feature:capture`. But `setContent` takes a composable lambda, so `:app`
> contained Compose code after all. Without the plugin the Kotlin compiler still
> type-checks that lambda perfectly happily and then emits it **untransformed** — a plain
> `Function0` instead of the `Function2` the Compose runtime expects, since a composable
> lambda carries a `Composer` and a changed-flags int.
>
> Nothing fails at build time. It fails on the device, as
> `NoSuchMethodError: No static method setContent$default(…Function0…)`, the instant the
> activity starts. AGP only enforces the plugin when `buildFeatures.compose = true`, so a
> module that forgets both gets no warning at all.
>
> To check a suspect module: `javap -c` the class and look at the `setContent$default`
> descriptor. `Function2` is correct; `Function0` means the plugin did not run.

### KSP needs an opt-out under AGP 9

KSP registers its generated sources through the `kotlin.sourceSets` DSL, which AGP 9's
built-in Kotlin support rejects outright:

```
Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.
```

The fix is AGP's own documented escape hatch, in `gradle.properties`:

```properties
android.disallowKotlinSourceSets=false
```

Without it, no annotation processing works at all — Room included. Remove it once KSP
registers generated sources the way AGP 9 wants.

## 4a. Module map

```
:core:units        length/area formatting and imperial parsing — pure Kotlin
:core:geometry     snapping, loop closure, constraint solver, and the capture maths
                   (multi-frame sampling, uncertainty model, tracking assessment,
                   range gating, floor selection, measurement modes) — pure Kotlin
:core:data         Room database, entities, repository. The only module with SQL
:core:designsystem palette, and the one plan renderer everything draws plans with
:ar                the only module that imports com.google.ar. Session lifecycle,
                   hit-test ranking, frame sampling, and four small GLES renderers
:feature:capture   the Compose capture screen
:feature:projects  the home screen and project list
:feature:editor    the 2D plan editor
:app               assembly, and the device capability report
```

Two rules hold this together and are worth defending:

- **The maths is not in the AR module.** Everything in `:ar` that could be tested was
  moved into `:core:geometry`'s `capture` package instead — the median sampler, the sigma
  model, the quality thresholds, the mode constraints. `:ar` is left with ARCore glue and
  OpenGL, which are the parts a JVM test could not reach anyway.
- **Nothing outside `:ar` imports ARCore.** `:feature:capture` talks to `MeasureArController`
  and reads `ArUiState`; it has never heard of a `Frame`.
- **Nothing outside `:core:data` imports a Room entity.** The repository speaks in
  geometry and unit types, so a schema change is not a UI change.

There is no dependency injection yet. `MeasureData.repository(context)` is the single
place that knows how a repository is built, and is the seam Hilt slots into when there is
a graph worth wiring; introducing a framework to hand out one object would be ceremony
ahead of need.

### A solve is only repeatable from the observations

Corners store **two** positions: where the solve put them, which is what the plan draws,
and where they were observed, which is what every later solve starts from.

The distinction is not academic. Re-solving a *solution* is not a no-op — the direction
constraints never fully win against the position residuals, so each pass shifts every
corner a few millimetres further towards perfect right angles. Measured at **2.8 mm per
re-solve** on a four-corner room. Since the editor re-solves on every edit, that would
mean each edit silently moving walls the user never touched, and a plan that after enough
edits describes an idealised rectangle rather than the room.

Anchoring every solve to the same observations makes editing idempotent: lock a wall,
unlock it, and you get exactly the room you started with. `RoomSolverTest` asserts both
halves of this, including that re-solving a solution *does* drift — so if that ever stops
being true, the test says the editor can be simplified rather than quietly rotting.

### Plans are drawn, never stored as images

Project thumbnails render from the stored corners with the same composable as the live
capture minimap. No files to write, none to clean up on delete, nothing to go stale, and
a room cannot look like one shape while capturing and another in the list. This is the
same reasoning `TECHNICAL_DESIGN.md` §5 applies to PNG export.

### On SceneView

`docs/TECHNICAL_DESIGN.md` names SceneView as the AR renderer, with a note that plain
OpenGL along the lines of Google's `hello_ar` is a viable fallback. **The fallback was
taken as the first choice.** The whole visual requirement is a camera background,
translucent plane polygons, point markers and thick lines — four shaders and about 400
lines — and text labels are drawn in Compose over the top, projected by the render thread,
rather than as a font atlas in GL. Taking a community 3D engine for that would buy churn
risk in exchange for features we do not use. Revisit if the 3D view in M9 needs more.

## 5. Conventions

- **Store metres, format at display time.** Mixing storage units is the classic way to
  produce unit bugs in a measuring app.
- **Format in the reader's locale.** `LengthFormatter` and `AreaFormatter` default to
  `Locale.getDefault()`. Exporters must pass `Locale.ROOT` — DXF, SVG and CSV require a
  decimal point, and a comma silently produces unparseable files.
- **Keep measurement logic out of Android.** Anything that can live in `:core:geometry`
  should, because that is the code that can be tested quickly and exhaustively.
- **Report uncertainty.** See `docs/ACCURACY.md`. Values carry a sigma and are displayed
  as `3.42 m ±3 cm`.

## 6. Optional setup script

Preinstalls the Android SDK so a session can build Android locally. Requires the domain
allowlist in §2. Every line ends `|| true` deliberately: a setup script that exits
non-zero stops the session from starting at all, so a flaky download must not be fatal.

```bash
#!/bin/bash
export ANDROID_HOME=/opt/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
cd /tmp
curl -fsSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip || true
unzip -q clt.zip -d /tmp/clt && mv /tmp/clt/cmdline-tools $ANDROID_HOME/cmdline-tools/latest || true
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses >/dev/null 2>&1 || true
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null 2>&1 || true
echo "ANDROID_HOME=$ANDROID_HOME" >> /etc/environment
exit 0
```

## 7. Getting a build onto a phone

CI publishes every push to a rolling prerelease. The repository is public, so the asset
has a stable unauthenticated URL that installs directly from a phone browser:

```
https://github.com/alberto233/Measure/releases/download/dev/measure-dev.apk
```

Workflow artifacts are deliberately not the delivery mechanism — GitHub always zips them,
and Android cannot install an APK from inside a zip.

Builds are debug-signed, which is fine for sideloading but cannot go to the Play Store.
A release signing config is an M10 concern.

## 8. Open decisions

- **App name.** `Measure` is a working title and too generic for the Play Store.
- **Capture thresholds.** The sampling dispersion limit (3 cm) and the per-source sigmas
  and correlations in `HitSource` are still reasoned estimates rather than measurements.
  The feature-count bands in `TrackingAssessor` have had one pass against the A36. All of
  them want a recorded-session corpus behind them before they harden into promises.
- **Migrations are written but never run against real data.** The schema is at version 6
  with five hand-written migrations (walls table; measured corner positions; openings;
  plan measurements; capture session). All five are straightforward and Room validates them against the exported schemas at
  compile time,
  but no upgrade has been performed on a device holding actual plans. `fallbackToDestructiveMigration`
  is deliberately not used: re-measuring a room means walking it again with a tape, which
  is exactly the work this app exists to save.
- **Rooms from separate captures are placed, not measured.** See the section above. The
  app now says so everywhere and gives the user a way to arrange them, which is the honest
  answer rather than the good one. The good one is registering a new capture against an
  existing plan — walking through a doorway the app already knows about and matching the
  two — which is real work and belongs to M8 rather than to a bug fix. Two things are
  worth writing down before anyone starts: rooms captured in one visit without leaving the
  capture screen already share a frame and need none of this, and the placement must stay
  translation-only until there is a measurement to justify a rotation.
- **Rooms captured before version 6 have no frame recorded**, so the app cannot tell
  whether two of them were captured together. It assumes not, and leaves them exactly
  where they are — the alternative would be shuffling an existing plan on a guess.
- **Sharing an SVG fails on the A36, and the cause is not yet known.** Every other format
  shares. `image/svg+xml` is the correct registered type and almost nothing on a handset
  claims it, so the share falls back to `application/octet-stream` — and it still fails,
  which means the fallback is not the whole story. A missing `<queries>` element was found
  and fixed alongside (without it `resolveActivity` returns null on Android 11 and later
  whatever is installed, so the fallback was firing for the wrong reason rather than on
  evidence), but that alone does not explain it. The next step is the precise symptom: no
  chooser at all, a chooser whose targets then fail, or an error. Guessing again without
  that would be the third guess, and SVG is the format with the weakest claim to a place
  in the list now that PDF and PNG both work.
- **Corners hidden behind furniture are still unsolved, and there is now no candidate.**
  Four field sessions have named this as the app's real limitation. Wall-face capture was
  the answer and it did not survive contact with a white wall (see above). What remains is
  a workaround rather than a fix: capture the corners you *can* see, then drag the hidden
  one in the plan editor and lock a wall you measured with a tape, which pulls the whole
  room towards that one certain number. The trajectory guide helps aim past an obstruction
  while capturing. Neither is the same as being able to capture the corner.
- **Plumb fails on a white ceiling past about 2 m**, and it is the same root cause as the
  wall problem was: no features, so no surface. The depth trick that failed on walls would
  fail here for the same reason, so that avenue is closed too. The remaining option is
  ARCore's ceiling detection, which already exists and works when a ceiling has enough
  texture to be fitted.
  Three consecutive field-test sessions have ended with this feature as the answer: the corner behind a laundry pile, corners occluded by furniture generally, and
  cluttered rooms producing floor planes where there is no floor. Fitting the two adjacent
  wall planes and intersecting them needs no sight of the corner at all
  (docs/ACCURACY.md M10). Everything downstream — export, multi-room — operates on plans
  this would make substantially better.
- **Cluttered rooms defeat plane detection.** Field testing in a cluttered room produced
  planes stacked on planes, a floor plane extending over places with no floor, and a
  capture full of spurious short walls. Drawing fewer planes makes it *visible* rather
  than fixed. The floor-extension hits are the mechanism — `HitRanking.bestOnFloor`
  accepts a hit on the floor plane's infinite extension, which is what lets a corner land
  somewhere there is no floor. Removing that would make occluded corners impossible
  instead, so it is a real trade and wall-face capture is the way out of both.
- **Corners hidden behind clutter.** Room corners are now taken only from the floor
  plane, so a pile of laundry in front of a corner no longer drags the point to the front
  of the pile — but it does mean the shutter goes dead until the user aims somewhere the
  floor is actually visible. The real answer is wall-face capture (docs/ACCURACY.md M10):
  fit the two adjacent walls and intersect them, which needs no sight of the corner at
  all. Until M10 this is a gap, and it is the common case in an occupied room.
- **Floor selection when the floor is barely visible.** `FloorSelector` prefers a lower
  surface over a larger one, which handles the dining-table case. A mezzanine, a sunken
  living room or a staircase landing would defeat it, and none of those is handled.
- **Plumb breaks beyond about 2 m.** Field-tested twice; heights up to roughly 2 m come
  out, longer ones do not. `plumbPoint` allows up to 12 m, so the limit is not that gate
  — the likely cause is the denominator test rejecting near-vertical aim, or the sampling
  dispersion check failing once the point is far enough that small angular wobble moves
  it centimetres per frame. Deferred by the user, but it is the one remaining fault in a
  feature whose whole purpose is measuring to a ceiling.
- **Sloped and vaulted ceilings.** Wall area is perimeter times a single height, which is
  a decorator's estimate rather than a surveyor's. A bay window, a chimney breast or a
  sloped ceiling all make it wrong, and the app says nothing about that yet.
- **Wall thickness.** v1 assumes zero-thickness walls measured at interior faces. The
  editor now exists and assumes it too, so changing this is a migration plus an editor
  change rather than just a data-model decision.
- **A distance derived from a plan is not a measurement.** M12 adds a measuring tool to
  the plan editor, and the number it produces is a consequence of the solve rather than
  an observation of the room — worse, rectilinear snapping means a span across a squared
  room is partly modelled. `CornerEntity.isSnapped` records which corners moved. The tool
  has to carry its own tolerance and be visually distinct from a captured measurement, or
  it undermines the one thing the app claims over its competitors.
- **Folders or tags.** M14 groups plans; M13 exists partly to find out which shape people
  actually want before a schema commits to one.
- **Imperial fraction granularity.** Nearest 1/8" or 1/16"?
