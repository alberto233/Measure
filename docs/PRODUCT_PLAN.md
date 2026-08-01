# Product Plan

Working title: **Measure**. The Play Store name is an open decision (see
[Open questions](#open-questions)).

## 1. Vision

A phone-only room measuring tool that produces a floor plan good enough to act on —
order flooring, check whether a sofa fits, sketch a renovation, list a rental — and that
is honest about how precise it is.

We are explicitly *not* competing with LiDAR-grade capture or professional survey
equipment. We are competing with a tape measure, a pencil, and the back of an envelope,
which is what most people actually use. That bar is low, and the phone clears it: a tape
measure needs two people and a ladder for a ceiling height, and the envelope sketch is
never to scale.

## 2. Target users

**Primary — the domestic measurer.** Someone measuring their own space for a practical
reason: buying furniture, laying flooring or carpet, quoting a paint job, planning a
move, checking whether a room matches a landlord's claim. They want the number and a
sketch, they want it in five minutes, and they will not calibrate anything.

**Secondary — the light professional.** Estate agents, letting agents, cleaners,
handypeople, small contractors, flooring and blind fitters. They measure other people's
rooms often, need something exportable (PDF for a client, DXF for the shop's CAD), and
currently pay a subscription or type numbers into a spreadsheet.

**Non-target.** Architects and surveyors needing sub-centimetre accuracy, BIM pipelines,
IFC/Revit interop, or as-built certification. We should be upfront that we are not that,
rather than half-serving them badly.

## 3. Core use cases

1. *"How big is this room?"* — Walk in, tap four corners, read the area. Under a minute.
2. *"Will this fit?"* — Measure a single distance, an alcove width, a doorway.
3. *"How much flooring do I need?"* — Area of a non-rectangular room, minus fixtures,
   plus a waste percentage.
4. *"How much paint?"* — Wall area = perimeter × ceiling height, minus doors and windows.
5. *"Send this to someone."* — Export a labelled plan as PDF or image, or the raw
   geometry as DXF.
6. *"Plan a layout."* — Capture a multi-room flat, see it as one plan, check circulation.

## 4. Competitive analysis

| App | Platform reality | Strengths | Weaknesses we exploit |
| --- | --- | --- | --- |
| **magicplan** | AR scanning is **iOS/LiDAR only**; Android gets no scanning | Best-in-class editing, fixtures, estimates, reports | Simply absent on Android — the largest opening in the market |
| **CamToPlan** | ARCore on Android | Fast, simple, DXF export, well known | Android build is the neglected sibling (no 3D, no PDF on Android); precision traded for speed; subscription complaints; struggles on patterned/reflective floors |
| **ARPlan 3D** | ARCore on Android, ~198k Play reviews | Broad feature set, big install base, strong marketing | Frequent crash reports during scan start and calibration; accuracy complaints in small/complex rooms |
| **RoomScan Pro** | iOS-centric | Wall-face mapping works when floor corners are blocked — genuinely clever | Not an Android answer; no cost estimating |
| **Google Measure** | Discontinued | — | Left a gap and a lot of users looking for a replacement |

**What the reviews tell us.** Across CamToPlan and ARPlan the recurring themes are:
measurements that drift over a room-sized walk, crashes at scan start, patterned or
glossy floors defeating tracking, and subscriptions users could not cancel. Every one of
those is addressable, and three of the four are engineering problems rather than
marketing ones.

## 5. Positioning

Three commitments, each aimed directly at a competitor weakness:

**1. Accurate *enough*, and honest about it.** We apply rectilinear constraints and loop
closure adjustment so plans look and measure like real rooms, and we display an
uncertainty figure with every number. A competitor showing `3.4187 m` is lying; we show
`3.42 m ±3 cm` and we mean it. See [ACCURACY.md](ACCURACY.md).

**2. It does not crash.** The AR session lifecycle is the single most crash-prone part of
an ARCore app and it is where our competitors visibly fail. We treat AR session
robustness as a headline feature: defensive lifecycle handling, capability gating before
we ever open a session, and a graceful non-AR fallback mode.

**3. One-time purchase, no subscription.** The clearest differentiator available and it
costs us nothing but revenue modelling. Given how much of the competitors' negative
review volume is subscription anger, "pay once" is a marketing weapon, not just a pricing
choice.

Supporting: everything stays on the device. No account, no upload, no camera frames
leaving the phone. Easy to promise, easy to keep, and it makes the Play Store data-safety
declaration trivial.

## 6. Monetization

Free tier — unlimited live measuring, one saved project, PNG export, watermark on
exports.

Pro, one-time purchase (indicative £8–12 / $10–15) — unlimited saved projects, PDF, SVG,
DXF and CSV export, multi-room assembly, no watermark, 3D view.

Deliberately excluded: subscriptions, ads in the AR view, any paywall on the act of
measuring itself. Someone should be able to answer "will this sofa fit" forever without
paying; we charge when the output becomes a deliverable.

## 7. Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Accuracy fails to impress despite the maths | High | Build the geometry core early (M2) and validate against tape-measured reference rooms before committing to the full UI; the constraint solver is testable headless |
| SceneView is a community library and may churn or stall | Medium | Isolate all of it behind our own `:ar` module interface; our AR rendering needs are modest (lines, spheres, labels) and a direct Filament or plain OpenGL renderer is a viable fallback |
| Device fragmentation; poor tracking on cheap hardware | Medium | Explicit capability tiers with degraded features rather than a broken experience; non-AR manual drawing mode as the floor |
| Users expect LiDAR precision and leave one-star reviews | Medium | Uncertainty display, an onboarding tutorial on getting good results, and store-listing copy that sets expectations honestly |
| Thermal throttling and battery drain in long sessions | Low | Pause the session when backgrounded or idle, cap render rate, warn on sustained capture |
| Play Store AR and camera-permission policy | Low | Camera used solely on-device for measurement; declare accordingly |

## 8. Milestone roadmap

Estimates assume one developer working steadily and are for sequencing, not for
promising dates.

| # | Milestone | Rough effort | Exit criteria |
| --- | --- | --- | --- |
| **M0** | Foundations | 1 wk | Gradle multi-module skeleton, CI, ARCore availability and permission gate, capability tiering |
| **M1** | AR tape measure | 2 wks | Reticle, plane visualisation, point-to-point distance, units, multi-sample confidence, undo, live tracking-quality HUD |
| **M2** | Geometry core | 1.5 wks | Pure-Kotlin snapping, loop closure, constraint solver, area/perimeter — all JVM unit tested, no device needed |
| **M3** | Room capture | 2.5 wks | Guided corner-by-corner flow, floor plane lock, rectilinear snapping, loop closure, live 2D minimap, room area and perimeter |
| **M4** | Persistence | 1 wk | Room DB, autosave, project list with thumbnails |
| **M5** | 2D plan editor | 3 wks | Compose Canvas plan, pan/zoom, drag corners, type an exact wall length and re-solve, room labels |
| **M6** | Openings and heights | 1.5 wks | Doors and windows on walls, ceiling height detection, wall area, volume |
| **M7** | Export | 2 wks | PNG, PDF, SVG, DXF, CSV, JSON project file |
| **M8** | Multi-room | 2 wks | Capture rooms separately, assemble, snap shared walls |
| **M9** | 3D view | 1.5 wks | Extruded walls, orbit camera |
| **M10** | Beta polish | 2 wks | Onboarding, accuracy tutorial, device calibration, localisation, crash reporting, store listing |

**Walking skeleton first.** Before M1 proper, get one vertical slice working end to end:
open an AR session, place two points, show a distance, save it, see it in a list. It
proves the whole stack — ARCore, rendering, geometry, persistence, UI — and every later
milestone is then a widening rather than a leap.

**M1 and M2 are the real proving ground.** M2 in particular can be developed and tested
entirely on the JVM, in parallel with the AR work, and it is where the product's claim to
accuracy actually lives. If the solver does not produce convincing plans from synthetic
noisy input, we find out in week three rather than month four.

## 9. Success measures

- A tape-measured reference room is reproduced with each wall within 2% and total area
  within 4%, in ordinary domestic lighting.
- A typical four-corner room is captured in under 60 seconds by someone who has used the
  app once before.
- Crash-free session rate above 99.5%.
- No review theme about billing.

## Open questions

- **App name.** "Measure" is the working title and the repository name; it is too generic
  for the Play Store. Needs a decision before M10.
- **Imperial fraction granularity.** Nearest 1/8" or 1/16"? Affects the formatter and the
  editor's input parsing.
- **Wall thickness.** v1 assumes zero-thickness walls and measures interior faces.
  Supporting real thickness affects the data model, so decide before M5 even if we do not
  implement it until later.
