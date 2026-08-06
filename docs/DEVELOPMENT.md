# Development

Everything needed to be productive on this repository, including the constraints that
are not obvious from the code and the reasons behind every version pin. Written so a
fresh session, or a new contributor, can start without re-deriving any of it.

## 1. Current status

| Area | State |
| --- | --- |
| Product plan, features, technical design, accuracy strategy | Written — see the other files in `docs/` |
| `:core:units`, `:core:geometry`, `:core:export` | Implemented, 236 tests, CI green |
| `:core:data` | Room database, repository, project search and sort. 23 tests |
| Interface tests | Robolectric-hosted Compose tests, 3, plus 19 Roborazzi screenshots across `:feature:editor`, `:feature:projects`, `:feature:capture` and `:app`. The first thing here that renders a screen and looks at it |
| `:ar` | ARCore session, hit-test ranking, multi-frame sampling, GLES renderers |
| `:core:designsystem` | Direction A: tokens, palette, shared controls, the plan renderer |
| `:feature:capture` | M1 capture, M3 room capture, M6 ceiling detection |
| `:feature:projects` | The home screen: saved plans with drawn thumbnails, M13 search and sort |
| `:feature:editor` | M5 plan editor, M6 openings and volume, M12 measuring on the plan, M10b quantities |
| `:feature:export` | The share sheet and the FileProvider that serves the file |
| `:app` | Assembly, and the device check — Compose now, with its verdict first |
| CI | Green. Builds the APK and publishes it to a rolling prerelease |
| Next | **Field test M10b's structure**, then M10c. See below |

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

### Two things the first version of this got wrong

Both came straight back from the field test, and both are worth recording because the
reasoning that produced them was plausible.

**Placement was translation-only, so rooms could not be turned.** "The app must not invent
a rotation" is correct and does not imply "no rotation exists" — the *user* setting one is
exactly as legitimate as the user setting a position, and a room arrives at whatever angle
the phone was facing when its capture began. Translation alone therefore left a plan whose
pieces slide but never turn: a puzzle with unturnable pieces, in the user's words. The room
panel now has **Square to plan** (line this room's walls up with the grid the rest of the
plan is built on — `RoomPlacement.squaringAngle`, folded into ±45° because a grid has
four-fold symmetry) and **⟲/⟳ 90°** for choosing which quarter turn is wanted, which is a
question about doors and daylight that geometry cannot answer.

Buttons, not a two-finger twist: the plan already pinches to zoom, and a gesture that
sometimes scales the view and sometimes rotates a room is the same ambiguity as a tap that
sometimes selects and sometimes places a point.

Rotation is safe against a later re-solve, which is not obvious. `AngleSnapper` estimates
each room's rectilinear frame from *that room's own walls* rather than from the world axes,
so a rotated room snaps to its new orientation instead of being spun back to where it was
captured. Corners are rewritten by `@Update` rather than delete-and-reinsert so their ids
survive anything holding one.

**Dimension strings were plan-wide, so they measured between rooms.** A shared chain is how
an architect's drawing does it, and it assumes a reader who is used to reading one. With
four rooms it produces a string of a dozen runs in which the one being looked for is buried,
and the user's point was that people annotate for furniture rather than for construction.

The argument that settled it is the other one: a run spanning two rooms **states a distance
between them**, and until captures are registered that distance is a layout somebody
arranged. Drawing it in the most authoritative notation on the page would undo exactly what
recording the capture frame was for. Chains are now built per room, on screen and in every
export. A single-room plan is unchanged, which is the common case.

The plan-wide overall can come back when it is earned — when two rooms share a wall the app
knows about, rather than one it drew them next to.

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

## M13: finding a plan

Nothing on the home screen distinguished "Plan 3" from "Plan 7". Schema v7 adds
`ProjectEntity.reference` — **one** free-text field rather than a `client` column, an
`address` column and a `notes` column, because which of those someone needs is not
knowable in advance and two of the three would sit empty for every user.

`ProjectCatalogue` holds the searching and ordering, pure and tested, because a search that
quietly fails to match is indistinguishable from a plan that is not there, and the user's
conclusion is that the app lost their work. Three things in it are deliberate:

- **Every word must match, in any order, across both fields.** "ash 3" finds "Plan 3" at
  "14 Ash Road" — a string that appears in neither field, so substring matching would find
  nothing while the user watched a plan they could see fail to be found.
- **Search folds accents.** This app is built and tested in Spanish, where somebody will
  type "Ático" into the reference and later search for "atico".
- **Name ordering counts.** `Plan 1, Plan 10, Plan 2` is correct alphabetically and looks
  like a bug to everyone who sees it — and since the app names plans "Plan N" itself, that
  is the ordering most users would get.

The controls appear only past five plans. Below that, finding one is not a problem, and two
rows of furniture above a readable list is the cost of solving it anyway.

"No plans match" is a different empty state from "nothing measured yet", and says the plans
are still there. Telling somebody who has measured thirty rooms that they have measured
nothing is the worst thing a list screen can imply.

`ProjectsActivity` also got `SOFT_INPUT_ADJUST_NOTHING`. It had the same
`enableEdgeToEdge` + `safeDrawingPadding` pairing that threw the editor's panel to the top,
and search gave it its first text field — so it was one step from reproducing a fault
already fixed once. Applied before it could happen rather than after.

**`:core:data`'s tests now run.** They are JVM tests in an Android library, so they need the
SDK and could not run in the JVM job — which meant `ProjectNamingTest` and
`SavedMeasurementTest` had been in the tree for weeks without CI ever executing them. They
run in the Android job now, where the SDK is.

## Touch targets

Every tappable control in the app was under the 48 dp minimum: pills at 37, the capture
mode buttons at 33, the sort chips at 32, the project card's actions at 29. `touchTarget()`
in `:core:designsystem` is the one definition, applied at every `clickable` site.

**It grows the controls visibly rather than hiding a larger invisible target behind a small
button.** Compose clips pointer input to a node's bounds, so a genuinely larger touch area
means a genuinely larger node either way — and given that, a control that looks the size it
responds to is the better of the two. An invisible margin would also steal taps from
whatever sits beside it.

The mechanism is `sizeIn` placed between `clickable` and `padding`, so the click and the
background both take the grown size, with the content in a `Box` that centres it — padding
places its content at the top-left of whatever space it is given, so a bare `Text` would
sit at the top of the grown control rather than in the middle. That `Box` is the only
fiddly part and the reason each site changed shape rather than gaining one line.

Two visible consequences, both accepted: the project cards are about 24 dp taller, because
two stacked 29 dp actions become two 48 dp ones and that column is now taller than the
thumbnail beside it; and the editor panels have more in them, which they absorb by
scrolling — they were already capped at `PANEL_MAX_HEIGHT` with `verticalScroll`.

Not covered here, and left for M10: `Role.Button` semantics on the clickable `Text`s. Every
control in this app is a text label, so TalkBack does read them — the gap is that it
announces them as text rather than as buttons.

## What the design pass inherits

Written down so whoever defines the design system in Figma (M10a) is working from the real
state of the code rather than from a screenshot.

| | Today |
| --- | --- |
| `:core:designsystem` | Two files: a colour palette and the plan renderer |
| Type | 12 distinct sizes, bare literals, 87 usages. `12.sp` appears 29 times |
| Spacing | No scale. 16/14/12/10/8 dp chosen per site |
| Fields | Two implementations — `Field` (editor), `DialogField` (projects) |
| Chips | Three — `Pill` (editor), `PillButton` (capture), `SortChip`/`CardAction` (projects) |
| Top bars | Two, unrelated |
| Touch targets | Fixed: all now ≥48 dp via `touchTarget()` |
| Semantics | None. Controls are text labels, so TalkBack reads them, but as text rather than as buttons |
| `MainActivity` | 442 lines of plain Android views while everything else is Compose |

Two things in the palette are **not** free for a designer to reassign:

- `Ready`, `Sampling` and `Warning` encode tracking quality and measurement confidence.
  They come from `ACCURACY.md` and appear in the AR overlay, the reticle, the plan and the
  panels. Reusing them decoratively breaks the one thing this app claims (§5 of the product
  plan). `Ready` currently doubles as "selected" and "interactive" — that overload is the
  thing to untangle, by giving the interactive role its own colour.
- The **exports** are light-on-white by design and share nothing with the app's dark
  palette. They are printed, attached to quotes, and opened on laptops; a dark drawing comes
  out of a printer as a page of toner. `PlanDrawing` and `SvgExporter` own that palette
  separately and should stay that way.

## Interface tests, and three wrong theories

`:feature:editor` now has Robolectric-hosted Compose tests: a real in-memory database, the
editor rendered, a button pressed, the panel asserted. They exist because a panel that
silently stopped reflecting the model reached the user three times and 236 geometry tests
would all have passed. The gap was never the maths — nothing had ever rendered a screen.

`MeasureData.useForTesting` is the seam. One settable instance rather than Hilt, because one
substitution point is not a graph either.

**Two things make these tests work, and both cost a wrong guess first.**

- **Room's executors run inline.** Compose's test rule drives a *virtual* clock, so
  `waitUntil` can spend its whole timeout in a few milliseconds of real time. Room on its
  own executor delivers from a real background thread, on real time the test never spends,
  and whether the flow arrives first is a race. Raising the timeout to a minute made it
  *worse* — two failures instead of one — which is what ruled the theory out. A timeout
  cannot fix a race against a clock that is not real.
- **The database is never closed.** A JUnit rule wraps `@Before`/`@Test`/`@After`, so the
  Compose rule tears the composition down after `@After` — closing there pulls the database
  out from under a live view model whose init block collects for its whole life. This was
  also, wrongly, blamed for the timeouts; removing it moved which test failed rather than
  fixing anything.

Both wrong theories were only visible as wrong because CI prints failures.
`.github/scripts/print-test-failures.py` reads the JUnit XML in both jobs, because Gradle
puts the reason in an HTML report and on CI that is a file nobody opens. Before it, two
rounds were spent learning one sentence. Verified against a synthetic failing XML rather
than only a green run: a reporter that prints nothing when everything passes and nothing
when something fails looks identical from the outside.

**Roborazzi renders the editor to PNG** on the same harness — `recordRoborazziDebug`,
published by CI as the `editor-screenshots` artifact. Recording, not verifying: failing a
build on a pixel diff would lock the current appearance in as correct, and M10a exists
because it is about to change. Once the design system lands, `verifyRoborazziDebug` turns
these into regression tests without a line of them changing.

They exist because this project's screens had never been *seen*. All three interface faults
that reached a user on hardware — text fields the same colour as the panel behind them, the
keyboard throwing the panel off screen, every control under the minimum touch size — are
obvious in a picture and invisible in a diff of Kotlin.

## The colour rule, for the rest of the migration

Direction A separated two families that `MeasureColours` had tangled, and the rest of M10b
is largely applying this one rule. When in doubt, ask what the colour is *claiming*:

| Role | Colour | Means |
| --- | --- | --- |
| Interaction | `Accent` | you can act on this — selected, focused, primary, the cursor |
| Measurement state | `Ready` / `Sampling` / `Warning` / `Blocked` / `Idle` | how good the measurement is, per `docs/ACCURACY.md` |
| Certainty in the data | `Ready` | a wall locked to a tape length — the one number in a plan that is not a camera estimate |
| Everything else | `OnScrim` / `OnScrimMuted` / `Line` | structure and text |

Two consequences that are easy to get wrong:

- **The shutter keeps the state colours.** Its face is not saying "press me", it is saying
  whether a capture would be accepted and whether a burst is in flight. That is the most
  useful thing on the screen at the moment of pressing, and it is the one control where
  state beats interaction.
- **Confirmations are not green.** Success feedback is ordinary text; only warnings take a
  colour. A confirmation tinted with a measurement-quality colour would be claiming
  something about the measurement that it does not know.

Still carrying the old styling at the time of writing: the editor (the bulk of it — its
`Pill` and `Field` are the last duplicates of the shared controls), the capture screen's own
layout as distinct from its controls, and the device check, which needs porting out of plain
Android views rather than restyling.

## Uppercase button labels, and one hazard they carry

`MeasureButton` uppercases its label. That is the direction A look, and it is done in the
component so that no call site has to remember it and a translated string is uppercased by
the same rule as an English one.

It broke three interface tests the moment it landed — they matched on `"Send"` while the
screen now says `"SEND"` — which is the tests doing their job: a visual change the compiler
could not see, caught before a device did. They match case-insensitively now, because a test
pinned to the source casing is testing the design rather than the behaviour.

**The hazard to remember at M10c:** `String.uppercase()` uses the default locale, and in
Turkish that turns `i` into `İ`. Spanish is unaffected, so this is not urgent, but the
moment the app ships a language list this needs deciding — either a locale-safe cast or
dropping the transform and setting the labels uppercase in the string resources.

## Where M10b actually stands

The style landed first and the structure second, and for a while the branch had only the
first — which is worth recording, because "restyled" reads as "redesigned" in a commit log
and it is not the same thing.

Done, and field-tested on an A36:

- `MeasureType`, `MeasureSpace`, `MeasureShape`, the direction A palette, and one set of
  controls in `:core:designsystem`. Zero hardcoded colours and zero hardcoded font sizes
  remain in any feature module.
- `Accent` untangled from the measurement-state colours — see the colour rule above.
- Plans, the export sheet, the capture controls, the editor's controls, and the device
  check all carry the new palette and type.

Done, and **not** yet field-tested — the structural half of M10a:

1. **The Quantities view exists.** `QuantitiesContent` in `EditorScreen.kt`, on
   `Takeoff`/`Flooring`/`Painting` in `:core:geometry`. Use cases 3 and 4 in
   `docs/PRODUCT_PLAN.md` §3 — how much flooring, how much paint — now have a surface that
   adds the whole plan up rather than making the user total a room at a time. Waste
   percentage and coat count are controls beside the answer rather than settings, because
   they are part of the question. A room with no ceiling height is counted for its floor,
   excluded from its walls, and **named on screen** — an amber subtotal that says which
   rooms are missing, rather than a total that looks complete and is not.
2. **The editor's top bar is one row.** Back, title, Undo, Send. Add moved into the plan
   view's own panel: adding a room is something you do to a plan rather than to the editor.
3. **Plan / Measure / Quantities is a segmented control** — `MeasureSegmented`. This is
   what freed the second row, and it is also what made the quantities view reachable: a
   toggle button can only say "on", so a third mode had nowhere to be named.
4. **The panel is a draggable sheet** — `MeasureSheet`, peek and expanded, two anchors and
   no free height. `PANEL_MAX_HEIGHT` is gone. Expanded is 62% of the screen against the
   old 340 dp cap, and the editor expands it whenever something is selected, because a
   panel of text fields under a keyboard needs the room.

Three faults the first field test found, now fixed, as a warning about what this kind of
migration misses: the units toggle was labelled `"m"` and the uppercase transform turned it
into a lone letter in a box; the slabs over the camera were still consumer-rounded while
every control around them had gone hard-edged; and the device check was a bare uppercase
label that read as a heading, because a label carries no affordance at all.

**The device check is Compose now**, and the six duplicated `Color.parseColor` constants in
`:app` are gone with it. It was a log — everything the checks produced in one monospaced
`TextView`, with the answer somewhere around line fourteen — and it is now a verdict at the
top, the checks it came from underneath, and the crash trace above everything because on a
sideloaded build there is nowhere else a stack trace can be read.

`DeviceCheck` is separated from `MainActivity` on purpose. Four of that screen's five
states — no ARCore, an ARCore too old, an unsupported device, a session that will not open
— cannot be produced on the A36 or on any build machine, so they were written blind and
would have stayed that way. As a pure function from an availability value to a report, every
one of them is a screenshot.

**Screenshot coverage now runs to nineteen pictures across four modules**: the editor, the
plan list, the capture overlays, and the device check. That is not for pixel regressions — `recordRoborazzi`, not
`verify` — it is so the screens can be *looked at*. The first picture ever taken of the plan
list found a fault that had been shipping: with six plans the "Device check" button at the
foot of the list came to rest underneath the floating "New measurement" bar and the two
overlapped, because the bar had no background and the list had too little bottom padding to
scroll clear of it.

**Capture is covered too, as parts rather than as a screen.** `CaptureScreen`'s bars are
driven by a live `ArUiState` from an ARCore session and cannot be stood up on the JVM, so
laying them out again in a test would produce a picture that looks like the app and tests a
layout the app does not use. Instead every overlay — chip, aim advice, labels, reticle,
minimap, the selectors and the shutter — is rendered over three stand-in camera images:
near-black, near-white, and a gradient that changes underneath a single control.

That found a fault the app was shipping. `MeasureButton`'s unselected state filled with
`Color.Transparent` and wrote near-white text on it, which is fine on a panel and invisible
over a sunlit wall — on the capture screen "DISTANCE", "FREE" and "PLUMB" simply were not
there. The fill is `Panel` now, which changes nothing on a panel and everything over a
camera. It is the clearest case yet for these pictures: three field sessions looked straight
past it, because the screen is used indoors in the evening.

## 8. Open decisions

- **The device check stays**, and is ported. It answers one question a supported device can
  still get wrong — whether Depth is available — and it is now Compose like everything
  else. What is still unverified is its four failure states: they render, but no phone that
  actually produces one has ever run this app.
- **AR is required in release and optional in debug.** `app/src/release/AndroidManifest.xml`
  overrides both the feature and the ARCore metadata. Revisit if a non-AR drawing mode ever
  lands, because that override forecloses it.
- **App name.** `Measure` is a working title and too generic for the Play Store.
- **Capture thresholds.** The sampling dispersion limit (3 cm) and the per-source sigmas
  and correlations in `HitSource` are still reasoned estimates rather than measurements.
  The feature-count bands in `TrackingAssessor` have had one pass against the A36. All of
  them want a recorded-session corpus behind them before they harden into promises.
- **Migrations are written but never run against real data.** The schema is at version 7
  with six hand-written migrations (walls table; measured corner positions; openings;
  plan measurements; capture session; project reference). All six are straightforward and Room validates them against the exported schemas at
  compile time,
  but no upgrade has been performed on a device holding actual plans. `fallbackToDestructiveMigration`
  is deliberately not used: re-measuring a room means walking it again with a tape, which
  is exactly the work this app exists to save.

  Two gaps in the exported schemas, found while committing `7.json`: **`6.json` was never
  committed** — the export writes only the current version, so a schema is lost unless
  somebody commits it in the same change that bumped the version — and no test opens a
  version-1 database and migrates it forward. The second is the one that matters; a
  migration test with a seeded old database is a day's work and is the only thing that
  would turn "Room validated the SQL" into "the upgrade works".
- **The plan-wide overall dimension is gone, deliberately**, along with plan-wide chains.
  It is the number people want for "how wide is the flat", and it comes back the moment
  the app knows a shared wall rather than inferring one from where it drew two rooms.
  Until then the custom measure tool gives an anchored, honest version of the same thing.
- **Rooms from separate captures are placed, not measured.** See the section above. The
  app now says so everywhere and gives the user a way to arrange them — move *and* turn —
  which is the honest answer rather than the good one. The good one is registering a new capture against an
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
