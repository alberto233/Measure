# Accuracy Strategy

This is the most important technical document in the repository. Without a depth sensor
we cannot out-measure anyone on raw sensing, so the product's quality has to come from
what we do with noisy measurements after we take them. Fortunately that is a solved
problem in a neighbouring field — land surveying has adjusted noisy traverses since long
before anyone had a phone — and rooms give us two exceptionally strong priors: they are
almost always rectilinear, and their walls form a closed loop.

## 1. Where the error comes from

**Depth and hit-test error.** ARCore estimates depth from motion parallax, comparing
frames as the phone moves. It is metrically scaled (the IMU provides absolute scale) but
noisy, and the noise grows with distance. Hit-testing against a well-established plane is
substantially better than hit-testing against the depth map, because the plane is a
model fitted over many observations rather than a per-pixel estimate. Practical figure:
a couple of centimetres at 1–3 m, degrading beyond that.

**Tracking drift.** Visual-inertial odometry accumulates error as you walk. This is the
dominant error for room capture, precisely because room capture requires walking the
perimeter. In good conditions expect on the order of 1% of path travelled; in a dark
room with blank white walls and a glossy floor it can be far worse, and this is exactly
the condition under which competitors' plans come out visibly skewed.

**Targeting error.** Hand shake, and the reticle not being quite on the corner. One to
three centimetres, and it is uncorrelated between points, which makes it the easiest of
the four to attack statistically.

**Semantic error.** The user taps the baseboard rather than the wall–floor junction, or
the corner is behind a radiator and they guess. This one is not a maths problem; it is a
UX problem, and it is why wall-face capture exists as an alternative.

## 2. The twelve mitigations

### M1 — Prefer planes over depth

Rank every `HitResult` and take the best available rather than the first: a hit on a
tracked plane *within its polygon* beats a hit on the plane's infinite extension, which
beats a depth hit, which beats an instant-placement estimate. For structural points
(room corners) we can be stricter still and require a real plane hit, because a corner
that is not on the detected floor is almost certainly a mis-tap.

### M2 — Project everything onto one floor plane

At the start of a room capture, establish the dominant floor: the largest upward-facing
horizontal plane, breaking ties by lowest Y. ARCore habitually splits a single physical
floor into several plane instances as it explores, so merge them — follow
`Plane.getSubsumedBy()` and additionally merge planes whose normals agree and whose
heights are within a few centimetres.

Then project every captured corner onto that one plane. This does three good things at
once: it eliminates all vertical jitter from the plan, it guarantees the polygon is
genuinely planar so area is well-defined, and it converts a 3D estimation problem into a
2D one, which is what makes the constraint solver in M8 tractable.

### M3 — Multi-frame median sampling

Never trust a single frame. When the user taps, sample hit results across roughly 15
frames (about half a second), discard any frame where tracking degraded, and take the
**median** position — median rather than mean, because the failure mode is an occasional
wild outlier when the reticle crosses an edge, and the mean is not robust to that.

The dispersion of those samples is a free, per-point uncertainty estimate. Keep it: it
feeds the display in M12 and the solver weights in M8. If dispersion exceeds a threshold
(around 3 cm), reject the point and tell the user to hold steadier or step closer.

### M4 — Range gating

Error scales with distance to the target, so ask the user to stand 1–3 m away. Warn
above 4 m, warn harder above 8 m. A long room should be captured by walking it, not by
standing in the doorway and pointing at the far wall — which is exactly what an
uninstructed user does, and exactly why their first scan disappoints them.

### M5 — Tracking-quality gating

Block capture entirely when the camera is not in `TRACKING`, or when
`TrackingFailureReason` reports insufficient features, excessive motion or insufficient
light. Surface a live quality indicator built from tracking state, failure reason,
tracked feature count and plane coverage, and — critically — say *which* problem it is,
because "too dark" and "moving too fast" have opposite remedies.

Refusing to measure is better than measuring badly. Competitors record the point anyway
and the user discovers the problem when the plan comes out wrong, with no idea why.

### M6 — Rectilinear snapping

The highest-leverage correction available. Domestic rooms are overwhelmingly built to
right angles, so a wall that comes out at 87.4° is almost certainly a 90° wall plus
2.6° of error.

Establish a room axis frame from the first captured wall (or, better, the longest one).
As each new corner arrives, compute the segment's bearing relative to that frame; if it
falls within tolerance — default 6°, user-adjustable, with an off switch for genuinely
irregular rooms — snap it to the nearest multiple of 90°, optionally 45°. Show the snap
happening, and allow it to be released per corner.

This is what turns a wonky polygon into something that reads as a floor plan. It also
does real work rather than cosmetic work: along the dominant axes, snapping drives the
angular error to zero rather than merely hiding it.

### M7 — Loop closure adjustment

When the perimeter closes, the last corner will not land exactly on the first. That gap
*is* the accumulated drift, handed to us as a directly measured quantity — which is a
gift, because it means we know the total error even though we never knew the individual
errors.

Distribute it using the compass (Bowditch) rule: correct each vertex in proportion to the
cumulative length of the traverse up to it, so long walls absorb proportionally more
correction than short ones. That is the standard surveying adjustment and it applies
essentially unchanged here.

If the misclosure is small relative to the perimeter (say under 2%), apply it silently.
If it is large, show it and offer a choice: adjust anyway, or re-measure the room. A 15%
misclosure means something went wrong and quietly smearing it away would be dishonest.

### M8 — Least-squares constraint solve

Snapping and closure adjustment applied in sequence fight each other — snapping breaks
closure, and closing breaks the right angles. The correct move is to solve for all corner
positions at once against all constraints simultaneously.

Formulate it as non-linear least squares (Gauss–Newton, or Levenberg–Marquardt for
robustness) over the 2D corner positions, with four residual families:

| Residual | Meaning | Weight |
| --- | --- | --- |
| Position | corner near where it was measured | 1/σ² from the M3 dispersion |
| Direction | wall parallel to its snapped axis | high where snapped, zero where not |
| Closure | polygon closes exactly | very high |
| Locked dimension | wall matches a length the user typed | effectively infinite |

Rooms have well under twenty corners, so this is a small dense problem — a few hundred
lines of pure Kotlin, no linear-algebra dependency required, and it converges in a
handful of iterations. It is also completely testable on the JVM against synthetic rooms
with injected noise, which is why the geometry core is scheduled as its own milestone
ahead of the capture UI.

The locked-dimension residual is what makes the plan editor powerful: the user tape
measures one wall, types the true figure, and the whole plan tightens around that single
piece of certainty.

### M9 — Known-reference calibration

ARCore's scale should be metrically correct, but device-specific bias exists. Offer an
optional calibration: measure something of known length — a door, a sheet of A4, a credit
card, a real tape measure — enter the true value, and store the resulting scale factor
per device. Optional, never blocking, and probably a settings-screen affair rather than
part of onboarding.

### M10 — Two ways to capture a corner

*Corner mode* — tap the floor where two walls meet. Fast and intuitive, but baseboards,
furniture, radiators and pipework routinely hide the actual junction.

*Wall-face mode* — point at each wall in turn and let ARCore's vertical plane detection
fit it, then intersect adjacent wall planes to derive the corner. This works when the
corner itself is invisible, which is common in occupied rooms and near-universal on
building sites. RoomScan Pro gets real praise for this and nothing on Android offers it.

Let the two be mixed within a single room, per wall. Note that wall-face mode measures
interior faces, consistent with the zero-thickness wall assumption in the v1 data model.

### M11 — Heights

Prefer the detected ceiling: the highest downward-facing horizontal plane, giving
height = ceiling Y − floor Y. Fall back to a plumb-constrained measure — tap the floor,
raise the reticle up the wall — which is also the answer for rooms with sloped or vaulted
ceilings where a single height is meaningless anyway.

### M12 — Report uncertainty honestly

Every measurement carries a σ derived from sampling dispersion, range, tracking quality
and, after the solve, the residuals. Display it: `3.42 m ±3 cm`. Room area gets a range.

This is the mitigation most likely to be argued about, because it looks like admitting
weakness. It is the opposite. A competitor rendering `3.4187 m` from phone-grade data is
making a claim it cannot support, and users work that out the first time they check with
a tape. Showing the tolerance tells the user when to trust the number and when to go get
the tape — which is exactly the judgement they need in order to use the app for anything
that matters.

## 3. What accuracy to expect, and to promise

| Condition | Realistic expectation |
| --- | --- |
| Single segment, 1–4 m, decent light, textured surfaces | ±1–2% (roughly 2–8 cm) |
| Room perimeter after closure and constraint solve | ±1–3% per wall, area within about 3–5% |
| Long segment beyond 8 m, single vantage point | ±5% or worse — warn and suggest walking it |
| Dark room, blank walls, glossy or heavily patterned floor | Degrade visibly, warn loudly, and refuse rather than invent |

The store listing and onboarding should state something close to the first two rows.
Under-promising here is strategically correct: this category's one-star reviews are
overwhelmingly from users who expected a laser.

## 4. Validating it

**Synthetic tests, on the JVM.** Generate known rooms, inject realistic noise and drift,
run the pipeline, and assert that the recovered geometry is within tolerance. This is
where the majority of accuracy testing should live, because it is fast, deterministic and
requires no hardware. The whole geometry core is designed as pure Kotlin for exactly this
reason.

*First measured result.* On a 5 × 4 m room corrupted with 2.5 cm per-corner targeting
noise and 15 cm of accumulated drift, averaged over 40 seeded runs, the pipeline reduces
mean wall error from **3.88 cm to 1.95 cm** — a 50% reduction, and comfortably inside the
±2% per-wall target. Right angles come back to within 1°, and a single locked wall
dimension pulls to within 5 mm. See `RoomSolverTest` in `:core:geometry`. These figures
are synthetic and therefore optimistic about tracking behaviour; they establish that the
maths works, not that the phone does. Real-device numbers replace them once the
recorded-session corpus exists.

**Recorded-session regression tests.** ARCore has a Recording and Playback API: capture a
real AR session once, complete with camera and sensor data, then replay it deterministically
on-device. That gives repeatable end-to-end accuracy tests over genuine data — the same
room, the same walk, every build. Assemble a corpus covering the awkward cases (dim
lighting, patterned floor, glass, cluttered corners, a long corridor) and treat a
regression in measured error as a build failure.

**Physical reference rooms.** A handful of rooms measured properly with a tape or a laser,
recorded as ground truth, re-measured each release. Track per-wall error and area error
in a checked-in results file so the trend over releases is visible rather than anecdotal.
