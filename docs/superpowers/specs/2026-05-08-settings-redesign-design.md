# Settings UI Redesign — Design Spec

**Author:** Indigo (settings lane)
**Date:** 2026-05-08
**Status:** Draft, awaiting JP review — **no implementation until approved**
**Branch:** `dream/indigo/settings-redesign`
**Issues touched:** [#84](https://github.com/jphein/storyvox/issues/84) (buffer slider), [#85](https://github.com/jphein/storyvox/issues/85) (Azure HD voices, future), [#90](https://github.com/jphein/storyvox/issues/90) (punctuation pause), [#91](https://github.com/jphein/storyvox/issues/91) (GitHub login, future), [#98](https://github.com/jphein/storyvox/issues/98) (Performance & buffering, in-flight by Bryn)

## Recommendation (TL;DR)

- **Six grouped-card sections** replace the current flat-on-surface scroll: **Voice & Playback / Reading / Performance & Buffering / Library & Sync / Account / About**. Each section is a `surfaceContainerHigh` card with `shapes.large`, matching the existing `LibraryScreen.ResumeCard` brass idiom.
- **Five reusable row composables** (`SettingsRow`, `SettingsSwitchRow`, `SettingsSliderBlock`, `SettingsSegmentedBlock`, `SettingsLinkRow`) replace the current ad-hoc per-row layouts. Each row title gets a current-value subtitle ("Voice — Andrew · en-US", "Theme — System") so the screen is scannable at a glance.
- **One `SettingsGroupCard` container** wraps each section. Brass section header sits above the card (not inside it). Internal row separation is a thin `outlineVariant` rule, not a full-width divider.
- **`AdvancedExpander` row** for the buffer slider's experimental zone, future decoder choice, and future PCM cache eviction. Hides until ≥3 advanced knobs exist (Android guidance); for v1 of the redesign it stays collapsed by default with the buffer slider's past-the-tick territory inside it.
- **No new dependencies.** Hand-rolled equivalents of M3 Expressive's `ListItemDefaults.segmentedShapes()` (which ships in `material3:1.4+`; storyvox is on `1.3.1` via BOM `2024.12.01`). Same visual idiom, no version bump.
- **Coordinates with Bryn (#98).** The Performance & Buffering section is the structural slot her Mode A / Mode B toggles fill. The redesign adds the slot; Bryn (or whoever lands first) fills it. Buffer Headroom + Punctuation Cadence migrate into P&B per #98.
- **Two-PR sequence:** this spec PR first, implementation PR second after JP sign-off. Implementation is structural-only — every existing knob's behavior is preserved bit-for-bit; only the visual scaffold changes.

## Why this redesign now

1. **Settings has 11 controls today and is flat-scroll.** The Reading section absorbs 3 unrelated controls (speed, pitch, punctuation cadence). The Audio Buffer section is one slider drowning in 3 paragraphs of explainer copy. The Theme picker has no caption explaining what System/Dark/Light mean. The Wi-Fi-only switch sits naked on the surface with no group framing. Coherence is uneven.
2. **Settings is about to land 4–7 more controls in the next month** — Bryn's Modes A/B (#98), Aurora's PCM cache toggles (#86 → PRs C-H), Thalia's VoxSherpa knobs (research), Azure HD's BYOK key entry (#85), GitHub login (#91). Without structure, the screen sprawls. With structure, each new contributor knows where their knob goes.
3. **The brass aesthetic is currently confined to BrassButton and Section headers.** Settings is the longest screen in the app and has the least brass personality. Library Nocturne's grouped-card idiom (the `ResumeCard` pattern) is exactly the right vehicle to extend brass into Settings.
4. **The flat layout doesn't scale on the target hardware.** Tab A7 Lite is 800×1340 px — Settings already requires scrolling past a fold to reach Theme. Grouped cards both compress vertical real estate (no inter-section divider whitespace) AND make the screen scannable at any scroll position.

## Non-goals

- **No new behavior.** Every existing knob's range, default, persistence, and effect is identical post-redesign. This PR is structural visual refactor only. Bryn's #98 adds new toggles in a follow-up.
- **No search bar.** Material guidance: search makes sense ≥15 settings. We'll be at 13–15 post-#98; revisit at the 20-knob mark (probably when Azure HD's API key entry + diagnostics drawer ships).
- **No deep subscreens beyond Voice Library.** Voice Library already exists as a destination. The redesign keeps that. Don't introduce per-section "open subscreen" paths until row count justifies it.
- **No dependency on `material3:1.4+`** / Compose Settings library. Hand-rolled, brass-tuned.
- **No version bump or tablet install.** Per Iron Rules.

## Current state inventory

`feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt` — 333 lines, single `Column(verticalScroll, spacedBy(spacing.md))`. Sections separated by `Divider()` + `SectionHeader(label)`. No surface containment.

| # | Section | Row | Pattern today | Issue |
|---|---|---|---|---|
| 1 | Voices | Voice library link | Caption + `BrassButton(Primary)` | — |
| 2 | Reading | Speed slider | Naked slider + below-readout | — |
| 3 | Reading | Pitch slider | Naked slider + below-readout | — |
| 4 | Reading | Punctuation Pause | Caption + Row of `BrassButton(Primary/Secondary)` | #90 |
| 5 | Audio buffer | Buffer slider | Custom slider + tick + 3-paragraph explainer + amber/red past-tick state | #84 / #95 |
| 6 | Theme | Theme override | Row of `BrassButton(Primary/Secondary)` | — |
| 7 | Downloads | Wi-Fi only | Naked Switch + label | — |
| 8 | Downloads | Poll interval | Label + naked stepped slider | — |
| 9 | Account | Sign in/out | `BrassButton` + caption (signed-out only) | — |
| 10 | About | Version | Static text in brass | — |
| 11 | About | Sigil + branch | Static text | — |

### Coherence audit

| Aspect | Verdict |
|---|---|
| BrassButton segmented selectors (PunctuationPause, Theme) | Coherent. Keep, formalize as `SettingsSegmentedBlock`. |
| Section header (brass `titleMedium`) | Coherent. Keep, lift outside group cards. |
| Slider readout placement | Ad-hoc — Speed/Pitch below, Buffer above, Poll above. Unify: title row with right-aligned brass-colored value. |
| Caption text for explainers | Detached — captions float beneath their controls without binding. Bind via `SettingsSliderBlock(caption: ...)` and the row's supporting-text slot. |
| Switch styling | M3 default — visually a stranger to the brass screen. Tint `checkedThumbColor`/`checkedTrackColor` to brass primary. |
| Group framing | Absent. Add `SettingsGroupCard`. |
| Subtitles showing current value | Absent everywhere. Add per Android guidance. |

### Brass language inventory (already shipped, this redesign extends)

- `BrassButton(Primary | Secondary | Text)` — segmented selector vocabulary.
- `BrassProgressTrack` — custom drawn slider with brass thumb; not used in Settings, lives in reader. Reference for "what brass slider could feel like" if we ever extend.
- `LibraryNocturneTheme` — `colorScheme.primary` = `BrassRamp.Brass500` (dark) / `Brass550` (light). Surfaces are warm-dark / paper-cream. `surfaceContainerHigh` is the established "card" surface (per `ResumeCard`).
- `LocalSpacing` — 4dp grid (`xxs=4 xs=8 sm=12 md=16 lg=24 xl=32`).
- `MaterialTheme.shapes.large` — already used by `ResumeCard`. Reuse for `SettingsGroupCard`.

## Best-in-class survey (research summary)

Full notes in `~/.claude/projects/-home-jp/scratch/loose-ends-round2-2026-05-08/indigo-settings/research-notes.md`. Highlights:

- **Apple Books (Mac):** four-bucket settings — *General / Reading / Playback / Advanced*. Cleanest reference taxonomy. Source: [Apple Support](https://support.apple.com/guide/books/change-books-settings-ibksa12a5a23/mac).
- **Pocket Casts:** sections (Default Settings / Player Settings / Appearance) with **selective subtitle density** — only ~3 of 8 player settings have descriptions; the interesting ones (Intelligent Playback Resumption) get multi-line subtitles, basic toggles don't. Density is information-driven, not uniform. Source: [Pocket Casts Support](https://support.pocketcasts.com/knowledge-base/general-settings/).
- **Voice Dream Reader:** voice management is its own subscreen (Voice Settings / Manage My Voices); per-voice fine-tune (rate, pitch, volume) lives in the voice's detail. Per-language preferred voice. Source: [Voice Dream](https://www.voicedream.com/support/reader-help/). Validates storyvox's existing Voice Library subscreen pattern.
- **Smart Audiobook Player:** kitchen-sink reference; an Advanced/Troubleshooting drawer hides escape-hatch knobs (Decoder choice, button-rail customization). Source: [audiobooksgeek.com](https://www.audiobooksgeek.com/how-to-use-smart-audiobook-player/). Validates an Advanced drawer for storyvox's experimental knobs.
- **Material 3 Expressive (`material3:1.4+`)** ships `ListItemDefaults.segmentedShapes(index, count)` + `segmentedColors()` for connected rounded grouped lists. We can't use it (BOM 2024.12.01 → 1.3.1), but the visual idiom is the right target. Hand-roll with `Card` + first/middle/last shape. Source: [composables.com](https://composables.com/material3/listitem).
- **Android Settings guidelines** (`source.android.com/docs/core/settings/settings-guidelines`):
  - "If the settings in a group are closely related, you can add a group heading. If you use a group heading, you should always include a divider."
  - "Below the title, show the status to highlight the value of the setting. Show the specific details instead of just describing the title."
  - "Settings that are not frequently used should be hidden. Use 'Advanced' only when there are at least 3 items to hide."
  - "Place frequently used settings at the top of the screen."
  - "For 15 or more settings, group related settings under a subscreen."
- **`alorma/Compose-Settings` library** — row vocabulary: `SettingsMenuLink / SettingsCheckbox / SettingsSwitch / SettingsSlider / SettingsSegmented / SettingsGroup`. Validates the row taxonomy we're proposing. Not depended on. Source: [GitHub](https://github.com/alorma/Compose-Settings).

## Proposed taxonomy

Six top-level sections, ordered by frequency-of-touch (Android guidance: most-used first):

```
┌─────────────────────────────────────────────┐
│ Settings                                    │  ← top app bar (existing)
├─────────────────────────────────────────────┤
│                                             │
│ VOICE & PLAYBACK                            │  ← brass section header
│ ┌─────────────────────────────────────────┐ │
│ │ Voice                                   │ │
│ │ Andrew · en-US · 12 installed         › │ │  ← link row → VoiceLibrary
│ ├─────────────────────────────────────────┤ │
│ │ Speed                          1.20× ●─ │ │  ← slider block, readout brass
│ ├─────────────────────────────────────────┤ │
│ │ Pitch                          1.00  ●─ │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ READING                                     │
│ ┌─────────────────────────────────────────┐ │
│ │ Theme                                   │ │
│ │ Match the device's day/night.           │ │  ← optional supporting line
│ │ [System] [Dark]  [Light]                │ │  ← BrassButton segmented
│ └─────────────────────────────────────────┘ │
│                                             │
│ PERFORMANCE & BUFFERING                     │  ← Bryn's section (#98)
│ ┌─────────────────────────────────────────┐ │
│ │ Warm-up Wait                       [✓]  │ │  ← Bryn lands
│ │ Hold playback until the voice's…        │ │
│ ├─────────────────────────────────────────┤ │
│ │ Catch-up Pause                     [✓]  │ │  ← Bryn lands
│ │ Show "Buffering…" if synthesis falls…   │ │
│ ├─────────────────────────────────────────┤ │
│ │ Punctuation Cadence                     │ │  ← migrated from Reading
│ │ Pause length between sentences.         │ │
│ │ [Off] [Normal] [Long]                   │ │
│ ├─────────────────────────────────────────┤ │
│ │ Buffer Headroom            8 chunks ●── │ │  ← migrated from own section
│ │ Pre-synthesizes audio ahead. Useful…    │ │
│ ├─────────────────────────────────────────┤ │
│ │ ▾ Advanced (1)                          │ │  ← AdvancedExpander, collapsed
│ └─────────────────────────────────────────┘ │
│                                             │
│ LIBRARY & SYNC                              │
│ ┌─────────────────────────────────────────┐ │
│ │ Wi-Fi only                         [✓]  │ │
│ │ Don't poll on cellular.                 │ │
│ ├─────────────────────────────────────────┤ │
│ │ Poll interval                  every 6h │ │  ← slider block 1–24h
│ ├─────────────────────────────────────────┤ │
│ │ Sources                            (2) ›│ │  ← future: RR + GitHub + Azure
│ └─────────────────────────────────────────┘ │
│                                             │
│ ACCOUNT                                     │
│ ┌─────────────────────────────────────────┐ │
│ │ Sign in                                 │ │
│ │ Unlock Premium chapters and Follows.    │ │
│ │              [ Sign in ]                │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ ABOUT                                       │
│ ┌─────────────────────────────────────────┐ │
│ │ storyvox v0.4.30                        │ │
│ │ Blazing Crown                           │ │  ← brass sigil name
│ │ main · built 2026-05-08                 │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### Section rationale (each section's purpose, what stays, what moves)

#### 1. Voice & Playback
**Holds:** Voice picker (link row to VoiceLibraryScreen), Speed slider, Pitch slider.
**Moves out:** Punctuation Cadence → Performance & Buffering (per Bryn's #98).
**Why:** These are the three knobs a listener touches *for this story, this listening session*. They're the "feel" controls — what the narrator sounds like and how fast they read. Punctuation cadence belongs with the buffering family because the rhythm of underrun-vs-no-underrun shares an audio-engine-level concern with cadence.
**Future home for:** Thalia's VoxSherpa knobs (loudness normalization, breath pause) live here unless they're engine-level — then they move to P&B's Advanced.

#### 2. Reading
**Holds:** Theme override.
**Why so small?** Today this section also held speed/pitch/punctuation, but those are voice/playback concerns; Reading is *visual*. With one row it looks sparse, but it gives Theme the dignity it deserves and reserves the section for future visual-reading additions: font size override, sentence highlight intensity, paper-cream/midnight-indigo wallpaper toggle, page-turn animation. (Thalia's spec might propose any of these.)
**Alternative considered:** merge Theme into Voice & Playback as a one-off. Rejected — different mental model. Voice & Playback is auditory; Reading is visual. Even with one row, Reading is a coherent slot.

#### 3. Performance & Buffering (Bryn's section, #98)
**Holds (post-#98):** Warm-up Wait toggle, Catch-up Pause toggle, Punctuation Cadence (migrated from Reading), Buffer Headroom (migrated from own section), Advanced expander.
**Future home for:** Mode C — Full Pre-render (Aurora's PCM cache PR-D), Decoder choice (Azure HD-related), PCM cache eviction policy.
**Why:** The escalating-strategies framing in #98 makes this a natural cluster. A user on slow hardware (Tab A7 Lite, JP) tweaks these together; a user on fast hardware never opens this section. The Advanced expander hides the buffer probe's experimental zone (past `BUFFER_RECOMMENDED_MAX_CHUNKS`) by default — when collapsed, the buffer slider clamps to the recommended range; when expanded, it shows the full mechanical max (1500 chunks). See "Buffer slider integration" below.

#### 4. Library & Sync
**Holds:** Wi-Fi only switch, Poll interval slider.
**Future home for:** Sources subscreen link (Royal Road / GitHub / Azure config), Cache size readout + clear button, GitHub PAT entry (per #91).
**Why:** "Library" framing matches storyvox's existing language (the bottom tab is "Library"). "Sync" reads as "what storyvox does in the background to keep the library current." It's the obvious destination for source-level network preferences.

#### 5. Account
**Holds:** Sign in/out CTA + caption.
**Future home for:** Email display, change password, sign-out confirmation, future GitHub OAuth (#91), future Azure BYOK key entry (#85). Note: Azure BYOK might end up here OR in Library & Sync → Sources → Azure; we'll decide when #85 implementation lands. The redesign reserves space in both.
**Why:** Already cohesive in current design. Symmetric caption (signed-in shows email, signed-out shows the unlock pitch).

#### 6. About
**Holds:** Version, sigil name, branch · dirty · built.
**Future home for:** Open-source licenses link, support contact link, "Tap 7 times to enable diagnostics" easter egg (Android dev-options idiom).
**Why:** Trailing metadata, deliberately last. Brass sigil name is the visual sign-off — the one place where the realm-sigil gets to be the protagonist of the section.

### What changed vs. today

| Today | Redesign | Why |
|---|---|---|
| 7 sections (Voices, Reading, Audio buffer, Theme, Downloads, Account, About) | 6 sections (Voice & Playback, Reading, Performance & Buffering, Library & Sync, Account, About) | Voices folds into Voice & Playback (with speed/pitch joining). Audio buffer folds into P&B. Theme stands alone in Reading. Downloads renames to Library & Sync. |
| Voice library is the only thing in Voices | Voice link row + Speed + Pitch grouped in V&P | Speed and Pitch are voice-shaping; group with voice. |
| Punctuation Cadence in Reading | Punctuation Cadence in P&B | Per #98 — cadence is rhythm of synthesis, not visual reading. |
| Buffer slider is its own section | Buffer Headroom is one row in P&B | Per #98 — it's the cushion for Catch-up Pause. |
| Theme in its own section | Theme is the only row in Reading | Theme is visual; Reading is the home for visual-reading knobs (and eventually font size, etc.). |
| Wi-Fi only + Poll interval = Downloads | Same controls = Library & Sync | Renaming for future Sources subscreen alignment. |
| Sigil text floats in About section | Sigil text in About `SettingsGroupCard` | Same content, framed. |

## Row patterns

Five composables in a new file `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsComposables.kt`. All take `LocalSpacing.current` and use `MaterialTheme.colorScheme` tokens.

### `SettingsGroupCard`

```kotlin
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
)
```

Wraps a section's rows. `Card(containerColor = surfaceContainerHigh, shape = shapes.large)`. Inside: `Column(verticalArrangement = spacedBy(0.dp))`. Children render with internal thin `outlineVariant` rules between rows (the row composables draw their own top-rule when not first; see implementation note below).

### `SettingsRow`

```kotlin
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

The base row. Two-line layout: `bodyLarge` title + optional `bodySmall onSurfaceVariant` subtitle. Optional leading icon slot, optional trailing slot (for chevron, value text, or any other end-content). `onClick` makes the whole row tappable with ripple. Padding: `spacing.md` horizontal, `spacing.sm` vertical, `64.dp` minHeight (M3 list-item one-line standard).

### `SettingsSwitchRow`

```kotlin
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
)
```

Wraps `SettingsRow` with a brass-tinted `Switch` in the trailing slot. `onClick` toggles the switch (full-row tap target). Switch colors: `checkedThumbColor = primary`, `checkedTrackColor = primaryContainer`, default `uncheckedThumbColor = outline`. This gives Switch the brass personality that's missing today.

### `SettingsSliderBlock`

```kotlin
@Composable
fun SettingsSliderBlock(
    title: String,
    valueLabel: String,            // brass-colored, right-aligned ("1.20×", "8 chunks")
    subtitle: String? = null,      // single-line under title
    caption: String? = null,       // multi-paragraph block under slider
    slider: @Composable () -> Unit, // caller passes Slider (gets full track customization)
)
```

Top row: title (left, `bodyLarge`) + value label (right, `bodyLarge` in `colorScheme.primary`). Optional subtitle below. Slider full-width below the header. Optional caption below slider for explainer-heavy controls (BufferSlider's three paragraphs collapse into this one slot). Sliders that need warning-state coloring (BufferSlider amber/red) pass their own `Slider(colors = …)` — the block doesn't impose track styling.

### `SettingsSegmentedBlock`

```kotlin
@Composable
fun SettingsSegmentedBlock(
    title: String,
    subtitle: String? = null,
    options: List<String>,         // labels in order
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
)
```

Title + optional one-line subtitle + `Row` of `BrassButton`s with `Primary` for selected, `Secondary` for unselected. Replaces the current ad-hoc Punctuation Pause and Theme rows. Generic over enum types — caller maps `enum.entries` to labels.

### `SettingsLinkRow`

```kotlin
@Composable
fun SettingsLinkRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
)
```

Same as `SettingsRow` with a chevron-right (`Icons.Outlined.ChevronRight` in `colorScheme.onSurfaceVariant`) in the trailing slot. Used for "Voice library", future "Sources", future "Open-source licenses".

### `AdvancedExpander`

```kotlin
@Composable
fun AdvancedExpander(
    titlesPreview: List<String>,   // ["Buffer probe", "Decoder", …]
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
)
```

Per Android guidance: "subtext reveals hidden setting titles in a single line with ellipsis truncation." Closed-state shows `▸ Advanced (3)` with subtitle = first 3 titles joined by `·` + ellipsis. Open-state flips chevron to `▾` and reveals `content`. Animation: M3 `expandVertically()` / `shrinkVertically()` with `Motion`-token duration, gated by `LocalReducedMotion`. Only render if `titlesPreview.size >= 3` (Android guidance — fewer than 3 hidden items means use a regular row).

### Implementation note: internal row separators inside `SettingsGroupCard`

Two equally-clean options:

- **(A) Each row composable draws its own 1dp `outlineVariant` top-rule when it's not the first child of the card.** Requires a `firstInGroup: Boolean` flag (or a `LocalIsFirstInGroup` composition local set by the card). Self-contained, but a row used outside a card needs to hide the rule.
- **(B) `SettingsGroupCard` interleaves its children with `Divider(color = outlineVariant.copy(alpha=0.5f))`.** Cleaner caller API; the rule is the card's concern, not the row's. Implementation: use `Column` with `Modifier.drawBehind { … }` or interleave at composition (`children = listOf(…)`), or just use `Arrangement.spacedBy(1.dp)` with the card's `surfaceContainerHigh` peeking through as the rule.

Recommended: **(B)** with the `Arrangement.spacedBy(1.dp)` peek trick — gives a 1px brass-tinted ghost rule that reads as "rows belong to the same surface" without an explicit Divider call. Simpler to reason about, and adapts cleanly when rows are reordered or conditionally hidden.

## Visual hierarchy

Top-to-bottom typography rhythm inside Settings:

| Element | Style | Color |
|---|---|---|
| Top app bar title "Settings" | `titleLarge` | `onSurface` |
| Section header (above each card) | `labelLarge` | `colorScheme.primary` (brass) |
| Card surface | `surfaceContainerHigh` + `shapes.large` | — |
| Row title | `bodyLarge` | `onSurface` |
| Row subtitle / current-value | `bodySmall` | `onSurfaceVariant` |
| Row value label (slider readouts) | `bodyLarge` (right-aligned) | `colorScheme.primary` (brass) |
| Caption paragraphs (BufferSlider explainers, etc.) | `bodySmall` | `onSurfaceVariant` |
| About sigil name | `bodyMedium` | `colorScheme.primary` (brass) |
| Slider track / thumb | M3 default (already brass via `colorScheme.primary`) | brass |
| Switch checked colors | brass primary thumb, primaryContainer track | brass |

Spacing rhythm:

- Between sections (header + card pair): `spacing.lg` (24dp)
- Section header → its card: `spacing.xs` (8dp)
- Card padding: `spacing.md` (16dp) horizontal, `spacing.sm` (12dp) vertical per row
- Inside `SettingsSliderBlock` between header row and slider: `spacing.xs`
- Inside `SettingsSliderBlock` between slider and caption: `spacing.sm`
- Top of screen → first section header: `spacing.md`
- Bottom of screen after last card: `spacing.xl` (32dp) bottom-pad for FAB-clear

## Buffer slider integration (special-case)

Today the BufferSlider has 3 paragraphs of explainer + intensified warning copy past the recommended-max tick. Plus the slider itself ranges to `BUFFER_MAX_CHUNKS=1500`, well past where we believe LMK kills the app. This is by design — JP's #84 wants a probe.

Post-redesign: BufferSlider becomes a `SettingsSliderBlock` whose `caption` is the multi-paragraph copy. Two refinements:

1. **Default-collapsed mode (above-Advanced row).** Slider clamps to `BUFFER_MIN_CHUNKS..BUFFER_RECOMMENDED_MAX_CHUNKS` (2..64). The user gets the safe, useful range. The first explainer paragraph stays. The other two ("Why we have a recommended max" / "Why we can't just fix the delay") become a "Read more" expandable inside the caption — saves 6 lines of vertical space at the cost of one tap.

2. **Inside the Advanced expander.** A second `SettingsSliderBlock` titled "Buffer probe (experimental)" exposes the full 2..1500 range with the amber/red intensified-warning copy. This is opt-in. Users who care about helping measure the LMK threshold can find it; users who don't never see it.

This split honors #84's probe intent (the experimental zone is reachable) while keeping the default Settings screen calm.

**Migration path:** the implementation PR keeps the current single-slider behavior (full 2..1500 range, amber/red past tick) for v1 of the redesign and defers the split into "Advanced" until either (a) Bryn's #98 lands and we know exactly what else lives in P&B, or (b) JP requests it explicitly. Implementing the split in v1 is a behavior change beyond "structural visual refactor", which crosses the redesign's non-goal line.

## Search vs. linear scroll

**Skip search.** Material guidance flags ≥15 settings as the threshold; we'll be at ~13 post-#98. Linear scroll over six grouped cards on a 1340px tablet is fast — there's never more than 1 scroll page between any two settings. Re-evaluate when:
- Post-Azure HD lands its BYOK + voice list filter knobs (≥3 new)
- Post-Aurora PCM cache lands its cache controls (≥2 new)
- Post-Thalia VoxSherpa proposes new voice-quality knobs (unknown count)
- Or row count ≥20 in any single section.

If we add search, M3's `SearchBar` slot at the top of the screen is the canonical move. Filter logic: substring match across row title + subtitle + section name.

## Brass aesthetic budget

The redesign is **aesthetic-extending**, not aesthetic-redesigning. The existing brass language stays; new patterns extend it consistently.

| Element | Brass? | Notes |
|---|---|---|
| `BrassButton` segmented selectors | Yes | Existing, reused unchanged. |
| Section header text | Yes (`colorScheme.primary`) | Existing pattern, lifted outside cards. |
| Row title | No (`onSurface`) | Body text — reads as content, not decoration. |
| Row value label (slider readout) | Yes (`colorScheme.primary`) | New pattern — gives slider blocks a brass anchor on the right. |
| Card surface | Indirect (`surfaceContainerHigh`) | Same as `ResumeCard`; warm-dark or paper-cream depending on theme. The brass shows up as the subtle `surfaceTint` baked into M3 elevation. |
| Card shape | `shapes.large` | Same as `ResumeCard`; 16dp corner radius. |
| `Switch` | Yes (brass primary thumb on `primaryContainer` track) | New tinting; replaces M3 default grey. |
| `Slider` | Yes (already, via `colorScheme.primary`) | No change. |
| `LinearProgressIndicator` (download progress, future) | Yes (already, in `LibraryScreen`) | Pattern reused. |
| Inter-row peek rule | Indirect (`outlineVariant`) | 1px ghost rule, brass-warm in dark theme due to outline color. |
| About sigil name | Yes (`colorScheme.primary`) | Existing, kept. |
| `Divider` between section header and card | Removed | Cards do the grouping now. |
| `Divider` between sections | Removed | Whitespace + cards do the separation. |

**Net brass change vs today:** Switch gains brass, slider readout label gains brass, cards gain brass-warm tinting via M3 elevation. Headers lose nothing. The screen feels *more* brass, not less, while M3 components carry the body weight.

## Empty states & edge cases

- **No installed voices** — Voice link row's subtitle reads "No voices installed · tap to add" instead of "Andrew · en-US · 12 installed". Tap routes to VoiceLibraryScreen.
- **Signed in** — Account section subtitle shows email; trailing CTA flips to "Sign out" (Secondary BrassButton).
- **Signed out** — Caption text + "Sign in" CTA (Primary BrassButton, full-width inside the card).
- **Build is dirty** — About row's third line shows "main · dirty · built 2026-05-08" with `dirty` in `colorScheme.error` (warm tone via `StatusTokens.Error*`).
- **Pre-1.0 / dev build** — Sigil name reads "Unsigned · dev"; brass-colored as usual. No special UI.
- **Reduced motion** — `LocalReducedMotion`-aware: AdvancedExpander's expand/collapse skips animation if reduced.
- **Large fonts / accessibility scaling** — All `Settings*` composables use `Modifier.heightIn(min = 64.dp)` so a single-line row never collapses below the M3 list-item touch target. Subtitles wrap, not truncate, for ≥18sp accessibility setting.

## Open questions for JP

1. **Section name "Voice & Playback"** — alternative is just "Playback" (matches Apple Books' fourth tab). Voice picker + speed + pitch are all *voice-shaping*, but "Playback" is the audiobook-ear convention. Mild preference for "Voice & Playback" because storyvox's Voice picker is more prominent than a typical audiobook app's. **Defer to JP.**

2. **Theme alone in Reading section** — looks sparse (one row). Acceptable because Reading is the slot for future visual knobs (font size, sentence highlight intensity), but if JP wants Reading to feel populated *today*, options are: (a) move Theme up to be the last row of Voice & Playback, (b) move Theme down to be its own one-row Appearance section, (c) keep as proposed and accept a sparse section as future-reservation. **Mild preference for (c)** — sparse-but-named beats overstuffed, and Bryn's #98 + Thalia's pending knobs will likely populate things faster than we expect.

3. **Buffer slider Advanced split (above)** — keep the full 2..1500 range visible in v1, or split into safe-default + Advanced-probe immediately. The non-goal section says "no behavior change", so my proposal is: keep the full range visible in v1 of the redesign; do the Advanced split in a follow-up after Bryn's #98 lands. **JP confirm or override.**

4. **Card peek-rule technique** — `Arrangement.spacedBy(1.dp)` with surface peeking through (option B above) vs. explicit `Divider` between rows. Both look identical; Option B is less code. I'll go with B unless JP prefers explicit dividers.

5. **GitHub login (#91) placement** — Account section, or Library & Sync → Sources → GitHub? Today there's no Sources subscreen. **Deferred until #91 is scoped.** The redesign reserves space in both.

6. **Sigil click → commit URL** — `UiSigil.commitUrl` exists today and is unused in the UI. Optional: make the sigil name tappable, opening the commit URL in a browser. Tiny touch, fits the brass-curated aesthetic. **JP's call — yes / no / defer.**

## Compatibility with in-flight work

### Bryn (#98) — Performance & Buffering

Bryn's PR adds Modes A/B (Warm-up Wait, Catch-up Pause) and migrates Buffer Headroom + Punctuation Cadence into the new "Performance & buffering" section. This redesign **creates the slot** Bryn fills.

Two scenarios for the implementation PR:

- **If Bryn merges first:** The redesign's implementation absorbs her structure as-is. The "Performance & Buffering" section already has Mode A, Mode B, Buffer Headroom, Punctuation Cadence rows; the redesign re-skins them with the new `SettingsSwitchRow` / `SettingsSliderBlock` / `SettingsSegmentedBlock` composables.
- **If the redesign merges first:** The redesign creates the empty `SettingsGroupCard` with just Buffer Headroom + Punctuation Cadence (migrated). Bryn's PR adds two `SettingsSwitchRow`s for Mode A / Mode B at the top of the section.

Either order works. Coordination is via this spec being merged before her PR (or her reading the spec and using the row composables when she lands).

### Aurora (#86 → PCM cache PRs C-H) — future P&B rows

Mode C (Full Pre-render toggle) lands when Aurora's PR-D auto-populate logic is ready. The redesign's P&B section has space at the top of its rows; Mode C goes there. Cache size readout + clear button live in Library & Sync (or P&B if engine-level — TBD).

### Thalia (VoxSherpa knobs) — future V&P / P&B rows

Thalia's research will produce a spec proposing new voice-quality knobs (loudness normalization, breath pause, pitch envelope?). High-level voice-quality knobs go in Voice & Playback; engine-level / experimental knobs go in P&B (or P&B → Advanced). The redesign's row vocabulary (`SettingsSliderBlock`, `SettingsSwitchRow`, `SettingsSegmentedBlock`) covers all anticipated patterns.

### Solara (#85) — Azure HD voices, future

Azure BYOK key entry needs a sensitive-input row pattern (masked text field) not in the current redesign vocabulary. When #85 implements, add a `SettingsSecretRow(title, subtitle, value, onValueChange, masked = true)` — same shape as `SettingsRow` with a trailing `OutlinedTextField` in password-mode, brass-cursor-tinted. Place under Account or under a new Library & Sync → Sources → Azure subscreen.

### Phoenix (v0.4.30 playback bug) — no Settings UI impact

Phoenix's investigation may push EnginePlayer changes; doesn't touch SettingsScreen.kt. Independent.

## Implementation plan (Phase 3 — separate PR after sign-off)

Two-step within the implementation PR:

### Step 1: Add row composables (additive, no caller changes yet)

New file `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsComposables.kt`:
- `SettingsGroupCard`
- `SettingsRow`
- `SettingsSwitchRow`
- `SettingsSliderBlock`
- `SettingsSegmentedBlock`
- `SettingsLinkRow`
- `AdvancedExpander` (stub — only rendered when content is provided)
- `SettingsSectionHeader` (lifted from SettingsScreen.kt's local helper)

Each composable gets a `@Preview` annotation with `LibraryNocturneTheme` wrapping for visual verification.

### Step 2: Refactor `SettingsScreen.kt`

Replace the single-Column scaffold with the six-section layout. Each section is `SettingsSectionHeader` + `SettingsGroupCard { rows }`. Existing knobs map to:

| Today | Refactor target |
|---|---|
| `BrassButton("Voice library", …)` + caption | `SettingsLinkRow("Voice", subtitle = currentVoiceLabel, onClick = onOpenVoiceLibrary)` |
| Speed `Slider` + below-readout | `SettingsSliderBlock("Speed", "${"%.2f".format(s.defaultSpeed)}×", slider = { Slider(...) })` |
| Pitch `Slider` + below-readout | Same pattern, "Pitch" |
| Punctuation `Row` of `BrassButton`s + caption | `SettingsSegmentedBlock("Punctuation Cadence", subtitle = "Pause length…", options, selectedIndex)` — moved into P&B section |
| BufferSlider with 3 paragraphs | `SettingsSliderBlock("Buffer Headroom", "$chunks chunks (~${approxSeconds}s, ~${approxMb} MB)", slider = { Slider(colors = …) }, caption = currentLongCopy)` |
| Theme `Row` of `BrassButton`s | `SettingsSegmentedBlock("Theme", options = ["System", "Dark", "Light"], …)` |
| Wi-Fi `Switch` row | `SettingsSwitchRow("Wi-Fi only", subtitle = "Don't poll on cellular.", checked, onCheckedChange)` |
| Poll interval `Text` + naked slider | `SettingsSliderBlock("Poll interval", "every ${s.pollIntervalHours}h", slider = { Slider(steps=22, …) })` |
| Sign-in `BrassButton` + caption | `SettingsRow` with caption subtitle + trailing `BrassButton` (custom layout — fits the SettingsRow API) |
| About text trio | Three `SettingsRow`s inside an About card, OR a custom Box with the three lines (closer to current). Probably keep as Box for the version+sigil+branch trio — it's intentionally not interactive. |

`SettingsScreen.kt` shrinks from 333 lines to roughly 150–180. The 150 lines saved live in `SettingsComposables.kt` plus the simplified caller.

### Tests

- `SettingsViewModelBufferTest` — already exists; should not change behavior. Run.
- New unit tests aren't really a fit here — these are visual composables. The closest is screenshot tests. **Check** if storyvox has any (`./gradlew tasks | grep -i screenshot`); if not, **skip** — we're not adding a test framework unprompted (per CLAUDE.md).
- Preview composables in `SettingsComposables.kt` cover visual verification interactively via Android Studio.
- Run `./gradlew :feature:test :app:test` after refactor — ensure no compile/runtime breakage from import or dependency changes.

### Migration risk

Low. No behavior change. No API change to `SettingsViewModel` or `SettingsRepositoryUi`. No persistence change. The diff is mostly Composable-shape rearrangement plus the new file. If the refactor breaks anything, git history per-section makes revert trivial.

## Acceptance criteria (for the implementation PR, future)

- [ ] All 11 current controls function identically (speed, pitch, voice link, punctuation, buffer slider including amber/red past-tick state, theme, wi-fi only, poll interval, sign-in/out, about display).
- [ ] Six grouped-card sections render correctly in dark and light theme.
- [ ] Switch is brass-tinted (primary thumb on primaryContainer track) when checked.
- [ ] Slider readouts are right-aligned, brass-colored, in `bodyLarge`.
- [ ] BufferSlider's 3-paragraph caption renders inside the `SettingsSliderBlock`'s caption slot.
- [ ] AdvancedExpander, if used in v1, animates open/close (or skips animation when `LocalReducedMotion`).
- [ ] No new dependencies in `gradle/libs.versions.toml`.
- [ ] `:feature:test` and `:app:test` green.
- [ ] No version bump.
- [ ] Verified on Tab A7 Lite (per Bryn / future agent post-merge — Iron Rule says no install in this PR sequence).

## References

- Current Settings: `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/SettingsScreen.kt`
- Voice picker subscreen: `feature/src/main/kotlin/in/jphe/storyvox/feature/settings/VoicePickerScreen.kt`
- Voice Library destination: `feature/src/main/kotlin/in/jphe/storyvox/feature/voicelibrary/VoiceLibraryScreen.kt`
- Brass button: `core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/BrassButton.kt`
- Card pattern reference: `feature/src/main/kotlin/in/jphe/storyvox/feature/library/LibraryScreen.kt` (`ResumeCard`)
- Brass progress track (out-of-scope for Settings): `core-ui/src/main/kotlin/in/jphe/storyvox/ui/component/BrassProgressTrack.kt`
- Theme: `core-ui/src/main/kotlin/in/jphe/storyvox/ui/theme/LibraryNocturneTheme.kt`
- Spacing tokens: `core-ui/src/main/kotlin/in/jphe/storyvox/ui/theme/Spacing.kt`
- Brass / Plum / Surface tokens: `core-ui/src/main/kotlin/in/jphe/storyvox/ui/theme/Color.kt`

External:
- [Android Settings design guidelines](https://source.android.com/docs/core/settings/settings-guidelines)
- [Android settings patterns (developer.android.com)](https://developer.android.com/design/ui/mobile/guides/patterns/settings)
- [Material 3 ListItem](https://composables.com/material3/listitem) (segmentedShapes — 1.4+)
- [alorma/Compose-Settings](https://github.com/alorma/Compose-Settings) (row vocabulary reference, not a dependency)
- [Pocket Casts Settings](https://support.pocketcasts.com/knowledge-base/general-settings/)
- [Apple Books Settings](https://support.apple.com/guide/books/change-books-settings-ibksa12a5a23/mac)
- [Voice Dream Reader help](https://www.voicedream.com/support/reader-help/)
