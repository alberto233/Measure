# Design — direction 01, "Drafting"

The visual specification the app is built to. Written down because the alternative is a
link to a picture, and a picture cannot say *why* a decision was made or what it forbids.

Chosen after two rounds. The first round went technical and dark, in the manner of Teenage
Engineering; it was built, field tested, and rejected — "I don't think this style is
working, we might want something cleaner, white mode more Apple-like, rounded corners,
refine and not technical." This is the replacement.

**The cost of that reversal was hours, not weeks**, because the style lives in three files
rather than in 87 call sites. That is the whole return on doing M10a before M10b, and it is
worth remembering the next time a design system looks like overhead.

## 1. The idea in one line

White, structured by hairlines rather than by fills or shadows, with a black primary action
so that blue is free to mean one thing only: *this is the option you chose*.

The quietest of the three directions considered, and picked for the reason that matters
here — the app's actual content is a drawing, and every gram of interface weight is taken
out of the drawing's budget.

## 2. Colour

Surfaces, light:

| Token | Value | Job |
| --- | --- | --- |
| `Surface` | `#FFFFFF` | The ground everything sits on |
| `Panel` | `#FFFFFF` | A raised card. Same value as the ground — separation is the hairline, not the fill |
| `Sunk` | `#F6F7F8` | A recess: text fields, secondary buttons, thumbnails |
| `Line` | `#E4E5E9` | Hairlines. This direction's only structural device |

Ink:

| Token | Value | Job |
| --- | --- | --- |
| `Ink` | `#101114` | Anything that is read first |
| `InkMuted` | `#6B6D75` | Supporting text |
| `InkFaint` | `#9A9CA3` | Eyebrows, units, placeholder |

Action:

| Token | Value | Job |
| --- | --- | --- |
| `Primary` / `OnPrimary` | `#101114` / `#FFFFFF` | The one filled button on a screen |
| `Accent` / `OnAccent` | `#2F6BFF` / `#FFFFFF` | **Chosen.** A selected chip, a selected segment, a focused field |
| `AccentWash` | `#EAF1FF` | The fill behind a selected chip |

**The rule that makes this direction work:** the primary action is black and the accent is
blue, and they never swap. A black fill means *do this*; a blue fill or wash means *this is
what is currently selected*. Collapsing them — which the dark direction did, with one orange
meaning both — is what left the app with no way to say "this is a button" that did not also
say "and it is turned on".

## 3. The camera overlay stays dark

Non-negotiable, and the one place the light palette does not reach.

| Token | Value | Job |
| --- | --- | --- |
| `Scrim` | `#E6101114` | The slab behind anything textual over the camera |
| `ScrimSoft` | `#C2101114` | A lighter slab where the image should still read through |
| `OnScrim` | `#F4F5F7` | Text on that slab |
| `OnScrimMuted` | `#B9BCC4` | Supporting text on that slab |

The capture screen sits over a live camera image that can be any colour and any brightness.
A light interface against a sunlit wall is not merely low-contrast, it is *absent* — the dark
build shipped exactly that fault, with three of its mode buttons invisible over white, and
three field sessions walked past it because rooms get measured in the evening.

Apple's own camera is dark in light mode for the same reason. Only the shutter carries the
direction's accent.

The slabs are nearly opaque, and that is load-bearing rather than cautious. At 72% over a
sunlit wall the scrim composites to a mid grey, and amber advice text on mid grey is the
same fault as amber on white. The slab is what makes the overlay legible, so it cannot be
the thing that is subtle.

`OnScrim` and `OnScrimMuted` are therefore **capture-only tokens**. Anything on a white
surface uses `Ink` / `InkMuted`. Reaching for `OnScrim` on a light screen is the mistake this
split exists to make impossible.

## 4. Measurement state is not brand colour

Carried over unchanged in intent from `docs/ACCURACY.md`, retuned so each value is legible
on white *and* on the dark scrim, since several appear in both places.

| Token | Value | Meaning |
| --- | --- | --- |
| `Ready` | `#12A06F` | A surface is acquired; a capture would be accepted |
| `Sampling` | `#E08A0B` | A sample burst is in flight |
| `Warning` | `#C2700D` | Stated caution — a subtotal, a derived distance, an unmeasured arrangement |
| `Blocked` | `#E0483C` | Capture is gated |
| `Idle` | `#F4F5F7` | Nothing under the reticle. **Overlay only** — it is near-white by design |

Nothing decorative may borrow these. They are the one thing this app claims.

## 5. Type

System face throughout. For an Apple-like direction the system stack *is* the correct
choice, and it costs nothing in APK size. Numbers use tabular figures (`tnum`) rather than a
monospaced face — monospace was the technical direction's signature, and a proportional face
with tabular figures gives the column alignment without the typewriter.

| Token | Size / weight | Job |
| --- | --- | --- |
| `Display` | 28 semibold, −0.028em | One screen title, at the top, nowhere else |
| `Title` | 17 semibold, −0.02em | Section and sheet headings |
| `Body` | 15 regular | Anything read as a sentence |
| `Label` | 14.5 medium | Secondary controls and list entries |
| `Small` | 12.5 regular | Supporting detail |
| `Tag` | 10.5 semibold, +0.1em, **uppercase** | The micro-eyebrow over a value |
| `Reading` | 34 semibold, tabular, −0.035em | The measurement being taken |
| `Value` | 17 semibold, tabular | A measurement in a list |
| `ValueSmall` | 13 medium, tabular | A measurement inline |

**Sentence case everywhere except `Tag`.** The blanket `uppercase()` transform was the
loudest single thing making the app read as an instrument rather than as something refined,
and it also produced two shipped bugs on its own: a units toggle labelled `"m"` became a
lone capital letter in a box, and `String.uppercase()` follows the default locale, which
turns `i` into `İ` in Turkish. Uppercasing one small semibold letterspaced eyebrow is a
typographic device; uppercasing every button label is a voice.

## 6. Shape

| Token | Value | Job |
| --- | --- | --- |
| `Edge` | 10dp | Buttons, fields, thumbnails |
| `Panel` | 14dp | Cards, sheets, banners |
| `Pill` | 999dp | Chips and segmented controls |

## 7. The button ladder

The complaint that produced this section: *"the hierarchy in the buttons is way off, the new
measurement CTA and the filters have the same size in font and button height, makes no
sense."* Correct, and the cause was structural rather than a matter of arrangement — there
was exactly **one** button component at **one** height with **one** type size in the entire
app. A primary action and a sort filter were the same object.

Three rungs, differing in height *and* in type size:

| Rung | Height | Label | Fill | Used for |
| --- | --- | --- | --- | --- |
| Primary | 52dp | 17 semibold | `Primary` black, full width | The one thing a screen is for. **One per screen** |
| Secondary | 38dp | 14.5 medium | `Sunk`, or `Primary` when filled | Lock, Share, Set, Remove |
| Chip | 29dp visible | 12.5 medium | Transparent, or `AccentWash` when selected | Filters, waste percentage, coats, units |

**Chips keep a 48dp hit area behind a 29dp pill.** The visible control shrinks; the touch
target does not. This is how iOS sizes its small controls, and it is not a compromise — the
48dp minimum here was never a generic accessibility rule, it came from watching this app be
used standing up, one-handed, in someone else's hallway, with a tape in the other hand.
Hierarchy and target size are not actually in conflict; only hierarchy and *visible* size
are.

## 8. The sheet

The editor's bottom sheet was called out specifically: *"the cards don't work properly."*

- Rounded on its **top corners only**, at `Panel` (14dp). A card rounded on all four corners
  that is flush to the bottom of the screen reads as a floating box that has been cropped.
- A **handle** that reads as a grip — 34 × 4dp, `Line`, centred, with real padding above and
  below it. It is the only affordance saying the sheet moves.
- A **genuine edge** against the drawing: a hairline in this direction, not a colour
  difference. `Panel` and `Surface` are the same white here, so without the line the sheet
  has no top edge at all — which is exactly what the dark build looked like.
- Two anchors, peek and expanded. Never free height.
- **Both anchors are capped by what the content needs.** The sheet measures its content and
  never grows past it. Before this it opened at 62% of the screen whatever was in it, so a
  two-line readout spent most of that on white space *while covering the drawing the user
  was reading it about*. On this screen an oversized sheet is not a neutral choice: the plan
  is the thing it hides. Short content collapses both anchors onto each other, and the sheet
  correctly stops being draggable — there is nothing to expand to.

## 9. Cards need an edge, not a fill

`Panel` and `Surface` are the same white, so a card that sets only a background has no
boundary at all. On hardware the editor's status banners had their text sitting directly on
the plan with the drawing's own lines running behind it, which reads as a rendering fault
rather than as a card.

`MeasureCard` is therefore hairline-bordered and opaque, and it is what every banner, note
and floating message uses. Nothing may reach for `Panel` as a background without also taking
a `Line` border.

The same change retired the last dark surface outside the camera: the editor's transient
message was a black slab, which was right while the whole app was dark and became the only
black thing on a white screen — a leftover rather than a message. It is a light card with a
state-coloured edge now.

## 10. What this does not cover

- **The exports** are light-on-white already and own their own palette, in `PlanDrawing` and
  `SvgExporter`. They get printed. They are not this.
- **The app icon and name**, which remain the open branding question in
  `docs/PRODUCT_PLAN.md`.
- **Motion.** Nothing here specifies it, and the sheet's two-anchor settle is currently the
  only animation in the app worth the name. That is a gap, not a decision.
