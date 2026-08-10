# Store Listing

Everything Play asks for, written down before anyone is sitting in front of the console
with a text box and a deadline. Copy here is the product's marketing, and under the
monetisation decision in `PRODUCT_PLAN.md` §6 — ship free, optimise for installs and
rating — it is the *only* marketing there is.

Three rules hold throughout, and they are the same three the app is built on:

1. **Never claim precision we do not have.** The listing says ±2–3 cm because the app says
   ±2–3 cm. A listing that promises laser accuracy buys installs and pays for them in
   one-star reviews, which is the exact trade `PRODUCT_PLAN.md` §7 lists as the highest
   remaining risk.
2. **Lead with what the competitors get wrong.** Crashes at scan start, drift over a
   room-sized walk, and subscriptions people could not cancel. All three are in their
   review pages; two of them we have engineered against and one we simply do not do.
3. **Say what it does not do.** Cheaper to set expectations here than to answer them in
   review replies.

## 1. Identity

| Field | Value | Limit |
| --- | --- | --- |
| App name (launcher) | `Measure` | — |
| Store title | `Measure: Floor Plans & Area` | 30 chars (27 used) |
| Package | `com.measure.app` | — |
| Category | Tools | — |
| Tags | House & Home, Productivity | — |
| Content rating | Everyone | — |

The store title differs from the launcher label on purpose. "Measure" alone is
unfindable — it is a common word competing with every measuring app there is — while the
launcher wants the short one because it sits under an icon. "Floor Plans" and "Area" are
the two phrases people actually search.

## 2. Short description

80 characters. This is the line that appears in search results, and it does more work than
the full description ever will.

```
Measure rooms with your camera. Free, private, and honest about accuracy.
```

72 characters. Every word is load-bearing: **rooms** not objects, **camera** so nobody
arrives expecting LiDAR, **free** because it is the clearest differentiator against
subscription-fatigued competitors, **private** because nothing leaves the device, and
**honest about accuracy** because that is the whole positioning.

## 3. Full description

```
Measure a room by walking it. Point your phone at each corner, tap, and get a floor
plan with the area, the perimeter and every wall length — usually in under a minute.

HONEST ABOUT ACCURACY
Your phone measures with its camera, not a laser. Done well that is accurate to about
2–3 cm, which is enough to order flooring, check a sofa fits, or see whether a room
matches the size you were told. Every measurement is shown with its own tolerance, so
you can always see how much to trust it. We would rather tell you that up front than
have you find out afterwards.

WHAT IT DOES
• Room capture — walk the room, tap the corners, get a closed plan
• Floor area, perimeter, wall area and volume
• Tape-measure mode for one-off distances, with true horizontal and vertical
• Doors and windows, deducted from wall area automatically
• Type in a wall you have measured with a tape, and the plan tightens around it
• Measure between any two points on a saved plan
• Flooring and paint quantities, with waste and coat count
• Export as PDF, PNG, SVG, DXF or CSV

FREE, AND NOT A TRIAL
No subscription. No advertising. No paywall on measuring. Nothing is locked, nothing
expires, and nothing asks you to upgrade halfway through a room.

PRIVATE BY DEFAULT
No account, no sign-in, no upload. Your plans are stored on your phone and nowhere
else. The camera is used to measure and the frames never leave the device.

BUILT TO STAY UP
Losing a capture halfway through a room is the most common complaint about apps like
this. The AR session is treated as the feature it is: the app checks your device
before it opens the camera, tells you plainly when tracking is poor, and refuses a
measurement rather than inventing one.

WHAT IT IS NOT
It is not a substitute for a tape measure where a millimetre matters. It needs a
reasonably lit room with some visible detail — a pitch-dark room with blank white
walls will defeat it, and it will say so rather than guess. It needs a device that
supports Google Play Services for AR.
```

## 4. Data safety

The declaration is short because the answers are all the same, and they are the same
because of a decision made in `TECHNICAL_DESIGN.md` §5 rather than for the form.

| Question | Answer |
| --- | --- |
| Does the app collect or share any user data? | **No** |
| Is data encrypted in transit? | N/A — no data leaves the device |
| Can users request data deletion? | N/A — uninstalling removes everything |
| Camera | Used on device only, never transmitted or stored as imagery |
| Location | Not requested |
| Advertising ID | Not used |

The only permission in the manifest is `CAMERA`. There is no analytics SDK, no crash
reporting SDK, and no network code in the app at all — crash reports are written to a file
on the device and shown on the device check screen (`CrashLog`).

## 5. Privacy policy

Play requires a hosted URL. The text is short enough to sit on a single page; it needs
somewhere to live before submission.

```
Measure does not collect, transmit, or share any personal data.

Everything you measure is stored on your device and nowhere else. There is no
account, no sign-in, and no server. We cannot see your plans because they are never
sent anywhere.

The app requests camera access for the sole purpose of measuring, on the device, in
real time. Camera images are not recorded, not stored, and not transmitted.

If the app crashes, it writes a diagnostic file to its own private storage so you can
read it on the device check screen. That file is never sent anywhere unless you
choose to share it yourself.

Uninstalling the app deletes everything it holds.

Contact: <email address needed>
```

## 6. Screenshots

Play wants a minimum of two phone screenshots; four to eight is the practical range. The
app already renders its screens to PNG in CI (`recordRoborazziDebug`), and those are the
honest starting point — but they are renders of an empty-ish test fixture, and a listing
wants a real captured room.

Suggested order, because the first two are the only ones most people see:

1. **A finished plan** with dimensions — the thing being sold
2. **Capture in progress** — the reticle on a corner, tracking chip green
3. **The accuracy guidance card** — sets the expectation before install, not after
4. **Quantities** — flooring and paint, the "how much do I need" use case
5. **Export sheet** — PDF/DXF/CSV, which is what a trade user is looking for

Take 1, 2 and 4 from a real room on a real phone. 3 and 5 can come from the Roborazzi
renders, which are pixel-accurate to the shipped build.

## 7. Icons

Two assets, same motif, different detail budgets — which is what apps with detailed icons
generally do.

| Asset | Source | Rendered by |
| --- | --- | --- |
| Launcher | `app/src/main/res/drawable/ic_launcher_foreground.xml` | `LauncherIconTest` at 512/192/48 |
| Play listing (512²) | `app/src/main/res/drawable/ic_store_foreground.xml` | `LauncherIconTest` → `store-icon-512.png` |

Both are a floor plan with a door swing arc. The arc is the whole reason they read as
architecture rather than as a box, and it is the thing no competitor icon in this category
has — they lean on rulers, tape measures and phones, which say "measures a line" rather
than "produces a plan".

They differ in how they say *measured*, and the difference is a size argument:

- **Launcher — a dimension arrow.** A double-headed arrow is the mark that still resolves
  at 48 px. It also carries no window, because two glazing lines merge into one floating
  bar long before that size.
- **Listing — a graduated ruler.** A store search grid is a findability problem before it
  is a differentiation one, and a ruler is the strongest pre-learned "measuring" signal
  available. At 512 the graduations and the window are affordable, and both help.

Neither carries a camera or a phone. It is the most cloned element in the category, and a
phone silhouette eats the tile and leaves the plan too small to read. That message belongs
in the screenshots and the feature graphic, which have room to make it properly.

Chosen by rendering candidates into a mock store shelf — ours interleaved with genre
decoys at real listing size — rather than by argument. The launcher artwork is sized to a
circle of radius 36 about the canvas centre, not to the nominal 72 safe square, because a
round launcher mask clips that square's corners.

## 8. Feature graphic

1024 × 500. Ink ground, the plan mark from the listing icon, and the short description as
the only text.

| Asset | Source | Rendered by |
| --- | --- | --- |
| Feature graphic | `app/src/test/kotlin/com/measure/app/FeatureGraphicTest.kt` | → `feature-graphic-1024x500.png` |

Regenerate with `./gradlew :app:recordRoborazziDebug --tests '*FeatureGraphicTest'`.

Two decisions worth keeping. **The text is §2 verbatim**, split at its full stop, rather than
a banner headline of its own — the short description sits directly under this image in the
listing, and a banner that says something different is a second promise to keep. **The mark
carries no tile**: its ground and the banner's are the same ink, so a tile would be an
invisible rectangle that only ever showed up as a seam.

Everything sits inside a 76 px margin because Play crops this image differently in different
placements, and the one thing it must never crop is a word. It also has its own type scale —
a poster is not a screen — while taking its weights, spacing and colours from the same
tokens as the app.

## 9. Before submission

Blocking, in order:

- [ ] **A release signing key.** The build is ready for one; the key itself does not exist.
      Make it, keep it somewhere that is not this repository, and never lose it — Play ties
      the app to it permanently.

      ```
      keytool -genkeypair -v -keystore measure-upload.jks -storetype PKCS12 \
        -keyalg RSA -keysize 2048 -validity 10000 -alias upload
      ```

      Then either write a `keystore.properties` at the repository root (gitignored):

      ```
      storeFile=/absolute/path/to/measure-upload.jks
      storePassword=…
      keyAlias=upload
      keyPassword=…
      ```

      or set `MEASURE_KEYSTORE`, `MEASURE_KEYSTORE_PASSWORD`, `MEASURE_KEY_ALIAS` and
      `MEASURE_KEY_PASSWORD` in the environment, which is the shape CI wants. With none of
      them present `assembleRelease` still succeeds and emits `app-release-unsigned.apk` —
      **deliberately unsigned rather than debug-signed**, because a debug-signed release
      installs perfectly, runs perfectly, and is rejected by Play long after anyone who
      sideloaded it is locked to a key that can never be used again.
- [ ] **A Play Console account** and the one-off registration fee.
- [ ] **Somewhere to host the privacy policy**, and a contact address for it.
- [ ] **Real screenshots** (§6). The 512 listing icon is done — regenerate it with
      `./gradlew :app:testDebugUnitTest --tests '*LauncherIconTest'` — and so is the feature
      graphic (§8). Screenshots are the only image asset still outstanding, and they are the
      one that needs a real room.
- [ ] **`versionName`** set deliberately for the first public build. Currently `0.1.6`,
      which is a dev sequence rather than a release one. `versionCode` keeps climbing from
      `7` rather than restarting at `1`: a lower code will not install over the builds
      already on test phones, and Play only requires that it increase.

Not blocking, and now done:

- [x] **Localisation.** The app ships in English and Spanish. That makes the Spanish listing
      worth writing — the console translates a listing per locale independently of the app,
      and the failure mode it warns against (a translated listing over an English app, which
      converts an install and then disappoints) no longer applies. §2 and §3 need a Spanish
      pass before the Spanish listing goes up, held to the same rule as the English: never
      claim precision we do not have.
