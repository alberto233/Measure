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

## 7. Feature graphic

1024 × 500. Ink ground, the plan mark from the launcher icon, and the short description as
the only text. Not yet made.

## 8. Before submission

Blocking, in order:

- [ ] **A release signing key.** `app/build.gradle.kts` has a `release` build type with no
      `signingConfig`, so release builds are unsigned. The debug key is committed
      deliberately (so sideloaded updates install over each other) and must never be the
      upload key.
- [ ] **A Play Console account** and the one-off registration fee.
- [ ] **Somewhere to host the privacy policy**, and a contact address for it.
- [ ] **A feature graphic** (§7).
- [ ] **Real screenshots** (§6).
- [ ] **`versionName` and `versionCode`** set deliberately for the first public build.
      Currently `0.1.5` / `6`, which is a dev sequence rather than a release one.

Not blocking, but worth having first:

- [ ] Localisation. The listing can be translated per-locale in the console independently
      of the app, so a translated listing with an English app is possible — and is usually
      a worse experience than an English listing, because it converts installs the app
      then disappoints.
