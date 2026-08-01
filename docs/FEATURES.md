# Feature List

Priorities use MoSCoW: **M**ust, **S**hould, **C**ould, **W**on't (this version). The
"Milestone" column maps to the roadmap in [PRODUCT_PLAN.md](PRODUCT_PLAN.md#8-milestone-roadmap).

---

## 1. Measurement modes

The user asked specifically for vertical and horizontal measurement, so those are split
out as first-class modes rather than left as a side effect of free 3D measuring.

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| **Free measure** — any two points in 3D | M | M1 | The baseline mode. Distance between two anchored points regardless of orientation |
| **Horizontal measure** — locked to a horizontal plane | M | M1 | Both points projected onto the same horizontal plane, so the reading is a true plan distance with no vertical component leaking in. This is what you want for room widths |
| **Vertical measure** — plumb line | M | M1 | First point on the floor, second raised up a wall; the segment is constrained to true vertical. Removes the classic error where a "height" is actually a diagonal |
| **Ceiling height** | M | M6 | Automatic where a ceiling plane is detected, plumb-line fallback otherwise |
| **Polyline / running measure** | S | M3 | Chained segments with a running total; how you measure round an alcove |
| **Area of a polygon** | M | M3 | Tap a closed loop of points on a plane, get m² / ft² |
| **Volume** | S | M6 | Floor area × height |
| **Angle between segments** | C | M6 | Useful for non-square rooms and for verifying a corner really is 90° |
| **Room capture (guided)** | M | M3 | The flagship flow: walk the room, tap each floor corner, get a closed plan |
| **Wall-face capture** | S | M8 | Point at each wall and let plane detection fit it; intersect adjacent walls to derive corners. Works when the floor corner is hidden behind furniture or a radiator — RoomScan Pro's trick, and nothing on Android does it |
| **Instant placement fallback** | C | M3 | Let a point be placed before planes are fully resolved, refined once tracking catches up |

## 2. Capture experience

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| Centre reticle with tap-to-place | M | M1 | Reticle at screen centre beats tap-anywhere: two hands on the phone, no parallax from an off-centre touch, and the optical centre is the least distorted part of the frame |
| Live tracking-quality indicator | M | M1 | Traffic light driven by ARCore tracking state, failure reason, feature count and plane coverage. Tells the user *why* it is unhappy: too dark, move slower, surface too plain |
| Point-capture gating | M | M1 | Refuse to record a point when quality is insufficient, with a plain-language reason. Competitors silently record garbage; this is the single biggest trust win available |
| Confidence radius per point | M | M1 | Multi-frame sampling produces a dispersion figure; show it |
| Plane visualisation | M | M1 | Detected floor and walls shaded so the user can see what the app understands |
| Undo / redo | M | M1 | Non-negotiable during capture |
| Live 2D minimap during capture | S | M3 | Corner-of-screen plan building up as you walk; enormously reassuring and catches mistakes immediately |
| Snap feedback | M | M3 | When a wall snaps to 90°, say so visibly and allow per-corner unsnapping |
| Fine-adjust / magnifier | C | M5 | Nudge a placed point with a zoomed inset |
| Scan coaching overlay | S | M10 | First-run guidance: sweep the floor, keep 1–3 m away, avoid glossy surfaces |
| Haptic feedback on placement | C | M3 | Cheap, and makes the tap feel authoritative |
| Audio cue / voice prompts | W | — | Later, if hands-free capture proves desirable |

## 3. Accuracy features

Detailed in [ACCURACY.md](ACCURACY.md); listed here for completeness.

| Feature | Priority | Milestone |
| --- | --- | --- |
| Multi-frame median sampling with dispersion | M | M1 |
| Plane-preferred hit-test ranking | M | M1 |
| Single dominant floor-plane projection | M | M3 |
| Rectilinear (90°/45°) snapping, adjustable strength | M | M3 |
| Loop closure adjustment | M | M3 |
| Least-squares constraint solve | M | M3 |
| Distance-based error warnings | S | M1 |
| Uncertainty display (`±`) on every measurement | M | M1 |
| Known-reference device calibration | S | M10 |
| Per-device calibration profile storage | C | M10 |

## 4. Plan editing

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| 2D plan view with pan and zoom | M | M5 | Compose Canvas; no third-party charting needed |
| Drag a corner | M | M5 | Re-runs the constraint solve, respecting locked walls |
| Type an exact wall length | M | M5 | The killer editing feature: you tape-measure one wall, type it, and the whole plan tightens around that certainty |
| Lock / unlock a wall dimension | M | M5 | Locked walls become hard constraints in the solver |
| Add, delete, split a wall | S | M5 | For alcoves and bays missed during capture |
| Room name and type | M | M5 | Drives labels and any later reporting |
| Doors and windows on walls | M | M6 | Position along the wall, width, height, sill height; subtracted from wall area |
| Fixtures and furniture symbols | C | M8 | Simple 2D symbol library |
| Notes and photos pinned to a location | S | M8 | Big for the light-professional user |
| Dimension line styling and placement | S | M7 | Matters a lot for how professional an export looks |
| Grid and snapping in the editor | S | M5 | |
| Manual (non-AR) drawing mode | S | M5 | Draw walls on a grid and type dimensions. Serves unsupported devices, hopeless lighting, and users who just want to sketch |

## 5. Multi-room and projects

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| Project = multiple rooms | M | M4 | |
| Project list with thumbnails | M | M4 | |
| Autosave during capture | M | M4 | Never lose a scan to a phone call |
| Assemble rooms into a floor plan | S | M8 | Capture separately, then position and snap shared walls |
| Continuous multi-room capture | C | — | Walking between rooms in one session is where drift is worst; separate capture plus assembly is more reliable and probably the right permanent answer |
| Multiple floors / levels | C | M8 | |
| Duplicate a room | C | M5 | |
| Total floor area across a project | S | M8 | |

## 6. Units and formatting

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| Metric: m, cm, mm | M | M1 | Store everything internally in metres as `Double`; format at display time only |
| Imperial: feet-inches with fractions | M | M1 | `12' 4 3/8"`. Parsing typed imperial input is fiddly and deserves its own tested parser |
| Yards, decimal feet | S | M1 | |
| Area units incl. sq ft, sq m | M | M3 | |
| Per-project unit preference | S | M4 | |
| Precision / rounding preference | S | M5 | |

## 7. Export and sharing

DXF is CamToPlan's headline feature and the main reason light professionals pay; it is
also easy, because DXF R12 is plain ASCII we can write by hand.

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| PNG / JPEG of the plan | M | M7 | With scale bar, dimensions and title block |
| PDF | M | M7 | Plan page plus a measurement schedule; Android's own `PdfDocument` is sufficient |
| DXF (R12) | M | M7 | `LINE`, `LWPOLYLINE`, `TEXT` on named layers; hand-written, no library |
| SVG | S | M7 | Vector, opens anywhere, trivial to generate |
| CSV of measurements | S | M7 | Wall lengths, areas, perimeters — straight into a spreadsheet or a quote |
| JSON project file | M | M7 | Our own format, for backup and transfer; also the basis of any future sync |
| glTF / OBJ extruded 3D | C | M9 | |
| Android share sheet | M | M7 | |
| Copy dimensions as text | C | M7 | Surprisingly handy for pasting into a message |

## 8. 3D

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| Extruded 3D view of the plan | C | M9 | Walls pulled up to ceiling height, orbit camera. Visually impressive, low information value — hence Could, not Must |
| Textured / furnished 3D | W | — | Out of scope |
| Point-cloud or mesh capture | W | — | Without LiDAR this produces disappointing results; not our fight |

## 9. Platform, privacy, quality

| Feature | Priority | Milestone | Notes |
| --- | --- | --- | --- |
| ARCore availability and capability gate | M | M0 | Check before opening a session; explain clearly if the device cannot do it |
| Capability tiers with graceful degradation | M | M0 | Full AR / AR without Depth / manual only |
| Fully offline, on-device | M | M0 | No account, no upload, no camera frames leaving the phone |
| Robust AR session lifecycle | M | M1 | Pause, resume, backgrounding, permission revocation mid-session, camera stolen by another app — the crash surface competitors fall through |
| Crash reporting | S | M10 | |
| Dark theme | S | M5 | |
| Localisation | S | M10 | English first; metric-first languages matter for this category |
| Accessibility: large text, contrast, TalkBack on non-AR screens | S | M10 | The AR view is inherently visual, but the project list and editor need not be |
| Tablet / landscape layout | C | M10 | |

---

## What version 1.0 actually is

Cutting to the smallest thing worth shipping: **M0 through M7**. That is a phone-only AR
measuring tape with true horizontal and vertical modes, guided room capture with
constraint-solved plans, an editable 2D plan with doors and windows, and export to PNG,
PDF, DXF and CSV.

Multi-room assembly (M8) and 3D (M9) are the 1.1 story. They are the features that look
best in a store screenshot, which is a real argument for pulling M9 forward — but neither
changes whether the app is useful, and both cost more than they return before the core
is trusted.
