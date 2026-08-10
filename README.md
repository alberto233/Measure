# Traza

An Android AR measuring tape and floor-plan app. Point your phone at a room, tap the
corners, and get a dimensioned 2D plan you can edit and export — no LiDAR, no extra
hardware, no subscription.

**Status:** planning. No application code yet. This repository currently contains the
product and technical plan agreed before development starts.

## The short version

Existing Android options are weak. magicplan — the category leader — ships AR room
scanning on iOS only. CamToPlan and ARPlan 3D do run on Android, but their reviews
cluster around three complaints: measurements drift, the app crashes mid-scan, and the
subscription is hard to escape. None of them do serious post-capture correction; they
take whatever raw numbers ARCore hands back and draw them.

We can be meaningfully better without any hardware advantage, because the accuracy win
is in the maths, not the sensor. Rooms are overwhelmingly rectilinear and their walls
form a closed loop — two very strong constraints that let us fit a plausible plan to
noisy measurements. Surveyors have solved this problem since the 1800s (loop closure
adjustment); we apply the same idea to phone-grade data.

The second differentiator is honesty. This app knows it is not a laser, so it reports
uncertainty (`3.42 m ±3 cm`) instead of five fake decimal places, and it refuses to
record a point when tracking quality is too poor to trust.

## Documents

| Document | What's in it |
| --- | --- |
| [docs/PRODUCT_PLAN.md](docs/PRODUCT_PLAN.md) | Vision, target users, competitor teardown, positioning, monetization, risks, milestone roadmap |
| [docs/STORE_LISTING.md](docs/STORE_LISTING.md) | Play listing copy, data safety answers, privacy policy, and what is still blocking submission |
| [docs/FEATURES.md](docs/FEATURES.md) | The full feature list, prioritized and grouped by release |
| [docs/TECHNICAL_DESIGN.md](docs/TECHNICAL_DESIGN.md) | Stack choices, module layout, data model, AR pipeline, export formats, testing strategy |
| [docs/ACCURACY.md](docs/ACCURACY.md) | The core of the product: where error comes from and the twelve mitigations we apply |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | How to build and test, the environment constraints, and why every version is pinned where it is |

## Reading order

Start with `PRODUCT_PLAN.md` for the *why*, skim `FEATURES.md` for the *what*, then read
`ACCURACY.md` — it is the most important technical document here and the reason the app
has a right to exist.
