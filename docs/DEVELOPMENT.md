# Development

Everything needed to be productive on this repository, including the constraints that
are not obvious from the code and the reasons behind every version pin. Written so a
fresh session, or a new contributor, can start without re-deriving any of it.

## 1. Current status

| Area | State |
| --- | --- |
| Product plan, features, technical design, accuracy strategy | Written — see the other files in `docs/` |
| `:core:units`, `:core:geometry` | Implemented, 55 tests passing, CI green |
| `:app` | ARCore capability gate only. Installs, runs, reports device support |
| CI | Green. Builds the APK and publishes it to a rolling prerelease |
| Next | M1: AR capture — reticle, plane visualisation, point-to-point measuring |

**Confirmed on real hardware** (Samsung Galaxy A36 5G, Android 16 / API 36):
ARCore supported and installed, Depth API **yes**, Raw Depth API **yes**. No capability
tier is blocked, so the full plan is achievable on the target device.

## 2. The environment constraint that shapes everything

**`dl.google.com` is blocked by the cloud environment's egress policy**, and
`maven.google.com` 301-redirects to it. Consequently *no* Google Maven artifact — AGP,
AndroidX, ARCore — resolves inside the development container.

What follows from that:

- The pure-Kotlin modules build and test locally, in seconds. They deliberately depend
  on nothing from Google, which is what keeps the measurement work fast to iterate on.
- **Android code can only be compiled in CI.** Six consecutive CI failures were spent on
  build plumbing that a local compile would have caught instantly.
- Actions logs are served from `productionresultssa3.blob.core.windows.net`, also
  blocked, so only the *tail* of a job log is readable. Kotlin prints diagnostics
  *before* the failure marker, where the tail cannot reach — hence the "Surface compiler
  errors" CI step, which reprints them last. Do not remove it while this constraint holds.

### Lifting the constraint

Worth doing before any significant Android work. It requires a **new session**, because
network policy is fixed when the VM starts.

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
7. Optionally add the setup script in §6 to preinstall the Android SDK.
8. Save, then start a **new** session.

## 3. Build and test

```bash
# The fast loop. Works in the container today; no Android SDK needed.
./gradlew :core:units:test :core:geometry:test

# Android. Only works where Google's Maven host is reachable.
./gradlew :app:assembleDebug
```

`org.gradle.configureondemand=true` in `gradle.properties` is what lets the first command
run without configuring `:app` — without it, Gradle would try to resolve AGP and fail.

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
- **Wall thickness.** v1 assumes zero-thickness walls measured at interior faces.
  Changing this touches the data model, so decide before the editor work in M5.
- **Imperial fraction granularity.** Nearest 1/8" or 1/16"?
