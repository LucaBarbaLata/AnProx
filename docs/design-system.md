# Design system: Midnight, calm

AnProx has a **bespoke** look (Q29) in the **Midnight** direction (Q37): a deep navy page, solid
cards and one indigo accent. It started as "Midnight Glass", with frosted cards and gradient
glows; in October 2026 the owner found that busy and picked the calmer version described here
(Q37 amendment, [ADR 0016](adr/0016-calm-midnight.md)). It should feel quiet and premium, and stay
dense enough for professionals (Q8).

Code: [`core/designsystem`](../core/designsystem/src/main/kotlin/io/github/lucabarbalata/anprox/core/designsystem).

## Principles

1. **One thing per surface.** A card holds one subject: a guest's state, its tools, a list. Rows
   inside a card are separated by hairlines, not by more cards.
2. **The common case first, the rest folded.** A screen opens on what is used most (state,
   console, power, tools). Charts, details and rare tools sit behind a header that opens them,
   or a ⋯ menu, or the grid's **More**.
3. **Colour means something.** Neutral greys for everything; the accent for the main action and
   selection; green, amber and red only for state. No gradients, glows or decorative hues.
4. **Never colour alone.** Every status colour comes with words or a description for TalkBack
   (WCAG 1.4.1).
5. **Dense but touchable.** Information density suits sysadmins, but every touch target is at
   least 48 dp.
6. **One look everywhere.** Phones, tablets, light, dark and AMOLED all come from the same tokens.

## Colour

Screens use **roles** from `AnProxTheme.colors`, never raw hex values. Material 3 components get a
matching `ColorScheme` generated from these roles (`Theme.kt`), so dialogs, text fields and
switches match automatically. Material You dynamic colour is deliberately **not** used: the
palette is the identity ([ADR 0010](adr/0010-bespoke-design.md)). The role names still say
"glass" from the first look; they are solid colours now.

| Role | Dark | Light | AMOLED | Used for |
|---|---|---|---|---|
| `backgroundTop` = `backgroundBottom` | `#0D1220` | `#EDF1F8` | `#000000` | The page (flat) |
| `glass` | `#171E30` | `#FFFFFF` | `#0E1118` | Cards, the navigation bar |
| `glassStrong` | `#1F2840` | `#E8EDF6` | `#181D29` | Raised parts of a card: secondary buttons, selected rows and filters, tool icons |
| `surfaceSolid` | `#171E30` | `#FFFFFF` | `#0E1118` | Dialogs and menus |
| `hairline` | `#252E45` | `#E2E8F0` | `#1C2230` | Dividers between rows, tracks of bars |
| `glowPrimary`, `glowSecondary`, `highlight` | transparent | transparent | transparent | Kept for compatibility; unused |
| `textPrimary` | `#E6EAF2` | `#0F1729` | = dark | Titles, values |
| `textSecondary` | `#9AA4B8` | `#4A5568` | = dark | Labels, section headers |
| `textTertiary` | `#7E879B` | `#64748B` | = dark | Subtitles, hints, timestamps (4.6:1 and 4.8:1 on cards) |
| `accent` | `#7C9CFF` indigo | `#4458E0` | = dark | The primary button, selection, bars, the first chart line |
| `success` | `#34D399` | `#047857` | = dark | Running, online, OK |
| `warning` | `#FBBF24` | `#B45309` | = dark | ≥ 75 % usage, expired session, warnings |
| `danger` | `#F87171` | `#C81E1E` | = dark | ≥ 90 % usage, offline, failed, destructive |
| `info` | `#60A5FA` | `#1D5FD1` | = dark | Running tasks |
| `paused` | `#A78BFA` | `#7C4DDB` | = dark | Paused/suspended guests |

The light palette uses darker status shades so that text keeps ≥ 4.5:1 contrast on white.

### Status mapping

| Proxmox state | Tone | Label |
|---|---|---|
| guest `running`, node `online`, storage `available`, task `OK` | SUCCESS | Running / Online / Available / Finished |
| guest `stopped` | NEUTRAL (a grey ring, not a filled dot) | Stopped |
| guest `paused`/`suspended` | PAUSED | Paused |
| node `offline`, task failed, no quorum | DANGER | Offline / Failed / No quorum |
| unknown, task `WARNINGS: n`, session expired | WARNING | Unknown / Warnings / Session expired |
| task running | INFO | Running |

Usage bars: **accent** below 75 %, **warning** from 75 %, **danger** from 90 % (`toneForUsage`).

## Typography

Fonts are bundled (no Google Play Services needed, so F-Droid is happy): **Inter** for the UI
and **JetBrains Mono** for UPIDs and logs. Both are variable fonts under the SIL OFL
([licenses](third-party/)). Numbers use **tabular figures** (`tnum`), so live values don't
jitter as they change.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| headlineSmall | 24 / 30 | SemiBold | Server name on login |
| titleLarge | 20 / 26 | SemiBold | Top bar titles, the dashboard's three numbers |
| titleMedium | 16 / 22 | SemiBold | Card titles (a server's name) |
| titleSmall | 14 / 20 | Medium | Menu rows, section headers |
| bodyLarge | 16 / 24 | Regular | Names in lists (guests, nodes, favourites) |
| bodyMedium | 14 / 20 | Regular | Body text, a summary's state line |
| bodySmall | 12 / 16 | Regular | Subtitles, the label and value of a usage line |
| labelMedium | 12 / 16 | Medium | Tool labels, pills |
| `MonoStyle` | 13 / 18 | Regular | UPIDs, task logs, addresses |

Section headers are written in sentence case (titleSmall, `textSecondary`), not upper case.

## Shape, spacing, elevation

- **Radii:** cards 20 dp; buttons and tool icons 14 dp; rows inside a card 12–16 dp; pills fully
  rounded.
- **Spacing:** a 4 dp grid. Screen gutters are 16 dp, the gap between cards is 12 dp, card
  padding is 16 dp, list rows 16 × 12 dp.
- **No elevation.** A card is a solid `glass` fill on the flat page: no border, no shadow, no
  highlight. Something raised inside a card (a secondary button, a selected filter, a tool icon)
  uses `glassStrong`.

## Patterns

- **Summary card** (guest, node): the state line ("● Running · 3 days · 10.0.0.100"), up to three
  usage lines (label, value, a 4 dp bar), then the actions. On a guest: **Console** and the one
  power action the state calls for (Start, Shut down or Resume); the others in a ⋯ menu, Stop
  and Reset in red. A node's reboot and shutdown sit behind the power icon in its top bar.
- **Tool grid** (`ToolGrid`): a card with icons and short labels, four per row, most used first.
  More than eight fold behind a **More** cell; **Fewer** folds them again.
- **Folded sections** (`CollapsibleHeader`): Network, Performance and Details on a guest,
  Performance and System on a node. They start closed; TalkBack hears "Expanded" or "Collapsed".
- **Lists as one surface**: rows share one card, rounded at the ends, with a hairline between
  them. A row is a status dot, the name, one quiet subtitle and one value on the right (CPU of a
  running guest or node, how full a storage is); anything not running says its state there in
  words instead. When the right side is a number, the dot carries the state for TalkBack.
- **Attention inside its card**: an offline node, lost quorum or a full storage is a tinted row
  inside its server's card on the dashboard, not a separate list.
- **Menus** (Datacenter, More): titles only, in small groups with a sentence-case header.
- **Filters**: plain words; the chosen one sits on a `glassStrong` pill.

## Motion

- Bars animate to new values with a soft spring (damping 0.85, low stiffness), so live data
  glides rather than jumps.
- Navigation: forward is a fade plus a short slide (1/12 of the width, 260 ms); back is the
  reverse.
- Selection (nav items, segments) cross-fades colour.
- Respect the system "remove animations" setting. Compose's animations follow the animator duration scale.

## Components

| Component | File | Notes |
|---|---|---|
| `GlassBackground` | `Glass.kt` | The flat page. Put it once at the root (`MainActivity`). |
| `GlassCard` | `Glass.kt` | `onClick`/`onLongClick` make the whole card a button; `strong = true` marks selection; `shape` lets list rows round only their ends. |
| `UsageRing` | `Gauges.kt` | Circular gauge, solid arc. Includes a TalkBack description and progress semantics. |
| `UsageBar` | `Gauges.kt` | Thin solid bar. |
| `ChartCard`, `ChartLine` | `Charts.kt` | A card with a line chart: gridlines labelled at 0, half and the top; a faint fill under each line; gaps where data is missing; tap or drag to read a moment. Lines use the accent and a grey. The readout above the chart doubles as the legend. TalkBack reads a summary (now, average, peak). Callers pick round axis tops with `ChartScale` (`:core:model`). |
| `StatusDot`, `StatusPill`, `TagChip` | `Status.kt` | A stopped guest's dot is a ring. Status always has words nearby or a description. |
| `MetricTile` | `Common.kt` | Big value plus caption (not used on the dashboard any more). |
| `SectionHeader`, `EmptyState`, `ErrorBanner`, `InfoRow` | `Common.kt` | Section headers carry heading semantics. |
| `AnProxButton` | `Buttons.kt` | PRIMARY (accent), SECONDARY (`glassStrong`), DANGER (red text on `glassStrong`). ≥ 48 dp. Loading state. |
| `GlassTextField`, `SwitchRow`, `SegmentedPicker` | `Inputs.kt` | `SwitchRow` toggles from anywhere on the row. |
| `AdaptiveNavScaffold` | `AdaptiveNav.kt` | A floating bottom bar below 600 dp width, a side rail above; the selected item sits on a `glassStrong` pill. Exposes `LocalBottomBarPadding`. |

App-level helpers in `:app/ui/common`: `AnProxScaffold`, `ConfirmActionDialog` (tiered
confirmation, Q12), `ToolGrid` and `ToolEntry`, `CollapsibleHeader`, `UsageLine`, `MenuRow`
(titles with an optional one-line explanation, red for destructive entries), `statusAppearance`
and `UiText`.

## Layout and adaptivity (Q3, Q31)

| Width | Navigation | Layout |
|---|---|---|
| < 600 dp (phones) | Floating bottom bar | Single pane, detail screens push |
| ≥ 600 dp (tablets, unfolded, landscape) | Side rail | Dashboard grid (`Adaptive(340dp)`), resources list + detail |

## Accessibility checklist (WCAG 2.1 AA target)

- [x] Text contrast ≥ 4.5:1 in all three themes, tertiary text included.
- [x] Status never conveyed by colour alone (words beside it, or a description on the dot).
- [x] Touch targets ≥ 48 dp (buttons, nav items, rows, filters, tool cells).
- [x] Gauges expose `contentDescription` and progress semantics to TalkBack.
- [x] Section headers are headings; folded sections say whether they are open; nav items are
      tabs in a selectable group; filters are tabs or a checkbox.
- [x] All text comes from string resources and respects the system font size.
- [ ] Full TalkBack audit on device (M9).

## Iconography and brand

- Icons are Material Symbols, Outlined by default and Filled for the selected nav item. In tool
  grids and menus they are neutral grey; only destructive entries are red.
- **Launcher icon** (original artwork, Q32): an "A" drawn as an indigo→violet peak with a teal
  "status LED" dot where the crossbar would be, on the midnight gradient. Adaptive and themed
  (monochrome) layers are in `app/src/main/res/drawable/ic_launcher_*.xml`. It deliberately
  doesn't resemble the Proxmox logo, because the app is unofficial.
