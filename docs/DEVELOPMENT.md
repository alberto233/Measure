# Development

Everything needed to be productive on this repository, including the constraints that
are not obvious from the code and the reasons behind every version pin. Written so a
fresh session, or a new contributor, can start without re-deriving any of it.

## 1. Current status

| Area | State |
| --- | --- |
| Product plan, features, technical design, accuracy strategy | Written — see the other files in `docs/` |
| `:core:units`, `:core:geometry` | Implemented, 95 tests passing, CI green |
| `:ar` | ARCore session, hit-test ranking, multi-frame sampling, GLES renderers |
| `:core:data` | Room database, repository. Autosave, project list queries |
| `:core:designsystem` | Palette and the shared plan renderer |
| `:feature:capture` | M1 capture screen, plus M3 room capture — minimap, closure, solved plan |
| `:feature:projects` | The home screen: saved plans with drawn thumbnails |
| `:feature:editor` | M5 plan editor — pan/zoom, drag corners, lock a wall to a taped length |
| `:app` | Assembly. The capability report is now a screen reachable from home |
| CI | Green. Builds the APK and publishes it to a rolling prerelease |
| Next | M4 and M5 field testing, then M6 openings and heights |

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
- **Migrations are written but never run against real data.** The schema is at version 3
  with two hand-written migrations (walls table; measured corner positions). Both are
  straightforward and Room validates them against the exported schemas at compile time,
  but no upgrade has been performed on a device holding actual plans. `fallbackToDestructiveMigration`
  is deliberately not used: re-measuring a room means walking it again with a tape, which
  is exactly the work this app exists to save.
- **Corners hidden behind clutter.** Room corners are now taken only from the floor
  plane, so a pile of laundry in front of a corner no longer drags the point to the front
  of the pile — but it does mean the shutter goes dead until the user aims somewhere the
  floor is actually visible. The real answer is wall-face capture (docs/ACCURACY.md M10):
  fit the two adjacent walls and intersect them, which needs no sight of the corner at
  all. Until M10 this is a gap, and it is the common case in an occupied room.
- **Floor selection when the floor is barely visible.** `FloorSelector` prefers a lower
  surface over a larger one, which handles the dining-table case. A mezzanine, a sunken
  living room or a staircase landing would defeat it, and none of those is handled.
- **Wall thickness.** v1 assumes zero-thickness walls measured at interior faces.
  Changing this touches the data model, so decide before the editor work in M5.
- **Imperial fraction granularity.** Nearest 1/8" or 1/16"?
