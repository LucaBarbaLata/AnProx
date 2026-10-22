# 0010. Bespoke "Midnight Glass" design instead of Material You

- Status: Accepted; the glass, glows and gradients were dropped by [0016](0016-calm-midnight.md)
- Date: 2026-10-14
- Questionnaire: Q29, Q30, Q31, Q37

## Context
The owner chose a custom bespoke design (Q29) in the "Midnight Glass" direction (Q37), with
system/light/dark plus AMOLED themes (Q30) and a bottom bar that becomes a rail on tablets (Q31).

## Decision
- A dedicated `:core:designsystem` module with its own colour roles (`AnProxColors`), Inter and
  JetBrains Mono typography, shapes and components.
- Material 3 components are still used for complex widgets (dialogs, text fields, menus,
  switches), and are themed by mapping the bespoke roles onto a Material `ColorScheme`.
- **No dynamic colour.** The palette is the brand.
- Glass is simulated with translucency, an edge highlight and hairlines over a glowing gradient.
  There is **no real-time blur**, which would need API 31+ and costs GPU time (docs/design-system.md).
- Custom adaptive navigation (`AdaptiveNavScaffold`): a floating glass bottom bar below 600 dp,
  a glass rail above.

## Consequences
- A distinctive look that is identical on every device and Android version.
- More components to maintain than with stock Material.
- Accessibility (contrast, touch size, semantics) is our responsibility, and is tracked in the
  design system checklist.
