# Design system: Midnight Glass

AnProx has a **bespoke** look (Q29). The chosen direction is **Midnight Glass** (Q37): a deep
navy-charcoal background, frosted translucent cards, soft gradient glows, and luminous status
colours. It should feel premium and calm, and stay dense enough for professionals (Q8).

Code: [`core/designsystem`](../core/designsystem/src/main/kotlin/io/github/lucabarbalata/anprox/core/designsystem).

## Principles

1. **Data first, chrome last.** Numbers and status carry the most visual weight; decoration
   (glows, gradients) stays in the background.
2. **Calm by default, loud when it matters.** Most of the UI is neutral. Colour is reserved for
   status (running, warning, error) and for the primary action.
3. **Never colour alone.** Every status colour is paired with text or an icon (WCAG 1.4.1).
4. **Dense but touchable.** Information density suits sysadmins, but every touch target is at
   least 48 dp.
5. **One look everywhere.** Phones, tablets, light, dark and AMOLED all come from the same tokens.

## Colour

Screens use **roles** from `AnProxTheme.colors`, never raw hex values. Material 3 components get a
matching `ColorScheme` generated from these roles (`Theme.kt`), so dialogs, text fields and
switches match automatically. Material You dynamic colour is deliberately **not** used: the
palette is the identity ([ADR 0010](adr/0010-bespoke-design.md)).

| Role | Dark | Light | AMOLED | Used for |
|---|---|---|---|---|
| `backgroundTop` → `backgroundBottom` | `#0B0F1A` → `#111827` | `#F6F8FC` → `#E2E8F3` | `#000000` | Page gradient |
| `glowPrimary` | indigo 16 % | indigo 22 % | indigo 8 % | Radial glow, top-left |
| `glowSecondary` | teal 8 % | teal 14 % | none | Radial glow, bottom-right |
| `glass` | white 6 % | white 66 % | white 5 % | Card fill |
| `glassStrong` | `#161D2E` 90 % | white 95 % | `#0A0D14` 94 % | Floating bars, selected rows |
| `surfaceSolid` | `#161D2E` | `#FFFFFF` | `#0D1117` | Dialogs and menus (must be opaque) |
| `hairline` | white 10 % | ink 8 % | white 10 % | 1 dp borders, tracks |
| `highlight` | white 5 % | white 55 % | white 5 % | Top-edge light on cards |
| `textPrimary` | `#E6EAF2` | `#0F1729` | = dark | Titles, values |
| `textSecondary` | `#9AA4B8` | `#4A5568` | = dark | Labels, subtitles |
| `textTertiary` | `#6B7488` | `#718096` | = dark | Hints, timestamps |
| `accent` | `#7C9CFF` indigo | `#4458E0` | = dark | Primary actions, selection, gauges |
| `accentAlt` | `#A78BFA` violet | `#7C4DDB` | = dark | Gradient partner of accent |
| `success` | `#34D399` | `#047857` | = dark | Running, online, OK |
| `warning` | `#FBBF24` | `#B45309` | = dark | ≥ 75 % usage, expired session, warnings |
| `danger` | `#F87171` | `#C81E1E` | = dark | ≥ 90 % usage, offline, failed, destructive |
| `info` | `#60A5FA` | `#1D5FD1` | = dark | Running tasks, informational |
| `paused` | `#A78BFA` | `#7C4DDB` | = dark | Paused/suspended guests |

The light palette uses darker status shades so that text keeps ≥ 4.5:1 contrast on light glass.

### Status mapping

| Proxmox state | Tone | Label |
|---|---|---|
| guest `running`, node `online`, storage `available`, task `OK` | SUCCESS | Running / Online / Available / Finished |
| guest `stopped` | NEUTRAL | Stopped |
| guest `paused`/`suspended` | PAUSED | Paused |
| node `offline`, task failed, no quorum | DANGER | Offline / Failed / No quorum |
| unknown, task `WARNINGS: n`, session expired | WARNING | Unknown / Warnings / Session expired |
| task running | INFO | Running |

Usage gauges: **accent** below 75 %, **warning** from 75 %, **danger** from 90 % (`toneForUsage`).

## Typography

Fonts are bundled (no Google Play Services needed, so F-Droid is happy): **Inter** for the UI
and **JetBrains Mono** for IDs, UPIDs and logs. Both are variable fonts under the SIL OFL
([licenses](third-party/)). Numbers use **tabular figures** (`tnum`), so live values don't
jitter as they change.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| displaySmall | 34 / 40 | SemiBold | Hero numbers |
| headlineSmall | 24 / 30 | SemiBold | Metric tiles, server name on login |
| titleLarge | 20 / 26 | SemiBold | Top bar titles |
| titleMedium | 16 / 22 | SemiBold | Card titles |
| titleSmall | 14 / 20 | Medium | Row titles, nav labels |
| bodyMedium | 14 / 20 | Regular | Body text |
| bodySmall | 12 / 16 | Regular | Subtitles, help text |
| labelMedium | 12 / 16 | Medium | Pills, section headers (uppercase) |
| labelSmall | 11 / 14 | Medium | Captions under gauges |
| `MonoStyle` | 13 / 18 | Regular | VMIDs, UPIDs, task logs |

## Shape, spacing, elevation

- **Radii:** cards 24 dp, inner elements 16 dp, chips 8–12 dp, buttons and pills fully rounded.
- **Spacing:** a 4 dp grid. Screen gutters are 16 dp, the gap between cards is 12 dp, and card
  padding is 16 dp (14 dp for list rows).
- **Elevation through translucency, not shadows.** A glass card is:
  1. a `glass` fill (translucent),
  2. a vertical `highlight` → transparent gradient over the top 35 % (light catching the edge),
  3. a 1 dp `hairline` border.

  Depth comes from the glows in `GlassBackground` showing through. **Real backdrop blur** needs
  API 31+ and costs GPU time every frame, so it is not used. The layered look works the same on
  every supported device (API 26+). If blur is added later (for example with the Haze library),
  it must degrade to this recipe below API 31.

## Motion

- Gauges and bars animate to new values with a soft spring (damping 0.85, low stiffness), so live
  data glides rather than jumps.
- Navigation: forward is a fade plus a short slide (1/12 of the width, 260 ms); back is the
  reverse.
- Selection (nav items, segments) cross-fades colour.
- Respect the system "remove animations" setting. Compose's animations follow the animator duration scale.

## Components

| Component | File | Notes |
|---|---|---|
| `GlassBackground` | `Glass.kt` | Page backdrop. Put it once at the root (`MainActivity`). |
| `GlassCard` | `Glass.kt` | `onClick` makes the whole card a button. `strong = true` marks selection. |
| `UsageRing` | `Gauges.kt` | Circular gauge with glow. Includes a TalkBack description and progress semantics. |
| `ChartCard`, `ChartLine` | `Charts.kt` | Glass card with a line chart: gridlines labelled at 0, half and the top; a gradient fill under each line; gaps where data is missing; tap or drag to read a moment (tap again for the latest values). The readout above the chart doubles as the legend (colour dot + label). TalkBack reads a summary (now, average, peak) instead of the drawing. Callers pick round axis tops with `ChartScale` (`:core:model`). |
| `UsageBar` | `Gauges.kt` | Thin gradient bar. |
| `StatusDot`, `StatusPill`, `TagChip` | `Status.kt` | Status always has a text label nearby. |
| `MetricTile` | `Common.kt` | Big value plus caption, optional icon and click. |
| `SectionHeader`, `EmptyState`, `ErrorBanner`, `InfoRow` | `Common.kt` | Section headers carry heading semantics. |
| `AnProxButton` | `Buttons.kt` | PRIMARY (gradient), SECONDARY (glass), DANGER (red tint). ≥ 48 dp. Loading state. |
| `GlassTextField`, `SwitchRow`, `SegmentedPicker` | `Inputs.kt` | `SwitchRow` toggles from anywhere on the row. |
| `AdaptiveNavScaffold` | `AdaptiveNav.kt` | Floating glass bottom bar below 600 dp width, glass side rail above. Exposes `LocalBottomBarPadding`. |

App-level helpers in `:app/ui/common`: `AnProxScaffold` (transparent scaffold whose top bar
becomes glass on scroll), `ConfirmActionDialog` (tiered confirmation, Q12), `statusAppearance`,
and `UiText`.

## Layout and adaptivity (Q3, Q31)

| Width | Navigation | Layout |
|---|---|---|
| < 600 dp (phones) | Floating glass bottom bar | Single pane, detail screens push |
| ≥ 600 dp (tablets, unfolded, landscape) | Glass side rail | Dashboard grid (`Adaptive(340dp)`), resources list + detail |

## Accessibility checklist (WCAG 2.1 AA target)

- [x] Text contrast ≥ 4.5:1 for body text in all three themes (status shades chosen for this).
- [x] Status never conveyed by colour alone (pills and dots always come with a label).
- [x] Touch targets ≥ 48 dp (buttons, nav items, rows).
- [x] Gauges expose `contentDescription` and progress semantics to TalkBack.
- [x] Section headers are marked as headings, and nav items as tabs in a selectable group.
- [x] All text comes from string resources and respects the system font size.
- [ ] Full TalkBack audit on device (M9).

## Iconography and brand

- Icons are Material Symbols, Outlined by default and Filled for the selected nav item.
- **Launcher icon** (original artwork, Q32): an "A" drawn as an indigo→violet peak with a teal
  "status LED" dot where the crossbar would be, on the midnight gradient. Adaptive and themed
  (monochrome) layers are in `app/src/main/res/drawable/ic_launcher_*.xml`. It deliberately
  doesn't resemble the Proxmox logo, because the app is unofficial.
