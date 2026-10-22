# 0016. Calm Midnight: the same palette, without the clutter

- Status: Accepted (amends [0010](0010-bespoke-design.md))
- Date: 2026-10-21
- Questionnaire: Q29, Q37 (amendment of 2026-10-21)

## Context
Before the first stable release the owner found the app "cluttered and overwhelming". The
design canvas offered five directions (A–E); the owner chose **E**: keep today's navy and indigo
Midnight look, but calm it down.

What made it busy, screen by screen:
- every surface was a frosted card with a highlight, a hairline border and glows behind it;
- the dashboard repeated itself: totals tiles, an attention list, then cluster cards with rings
  and two bars per node;
- the guest screen showed every power action as a button, then a long list of tools, each with a
  sentence under it, then charts and details;
- menus explained every entry, and section headers were upper case.

## Options
1. Keep Midnight Glass and only trim content.
2. A new look (the canvas's directions A–D: light editorial, terminal, dense console, cards on
   grey).
3. **Midnight, calmer (E):** the same palette and fonts, flat surfaces, fewer things per screen.

## Decision
Option 3.
- The page is one flat colour; cards are solid, without border, highlight or glow; the accent is
  the only colour that isn't a status; buttons and bars are solid.
- Screens open on their common case and fold the rest: a summary card with Console and one power
  action (the others in ⋯), a grid of tools with More, and Network, Performance and Details (or
  System) folded on guests and nodes.
- Lists are rows in one card. The dashboard has one card per server with three numbers and its
  own warnings; favourites are one card.
- Menus show titles only; section headers are sentence case.
- The token names (`glass`, `glassStrong`) stay, so the change is a palette and component change,
  not a rename across 150 files.

## Consequences
- The same identity, recognisable to beta testers, with far less on screen.
- A tool or action may now be one tap further (behind More or ⋯). The order of tools follows
  how often they are used, and the e2e tour opens the folded ones on purpose.
- Contrast improved: tertiary text is 4.6:1 (dark) and 4.8:1 (light) on cards.
- Real backdrop blur stays out of scope (no translucency is left to blur).
