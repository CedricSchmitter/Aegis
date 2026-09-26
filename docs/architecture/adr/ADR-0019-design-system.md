# ADR-0019: A dark design system with tokens, palettes, chamfers and optional effects

Status: accepted; foundation implemented for issue #27 (PR 1 of 6). Date: 2026-09-26.

## Context

Aegis grew with Tailwind's default `stone`/`sky`/`amber`/`red` classes, a light and a dark theme chosen by the
operating system, and rounded cards. The owner provided a design system ("ShardVault", a desktop prototype plus a
design-system PDF) and approved a clickable phone and desktop mockup of Aegis in that style on 2026-09-26 (issue #27,
comment "Owner approval and implementation plan"). The redesign ships in six focused PRs; every PR must leave every page
readable. This ADR records the visual rules that all six follow. ShardVault's domain features (ICE levels, versions,
approval workflow, quarantine, access requests, storage quota) do not apply to Aegis and are not part of it.

## Decision

1. **Dark only.** The light theme is removed. `:root` declares `color-scheme: dark` and the page always uses the
   dark tokens. Until the page redesigns (PRs 3-5) remove them, the `dark:` variant that pages still carry always
   applies (`@custom-variant dark` in `frontend/src/styles.css`).

2. **Tokens.** Colours are Tailwind 4 theme variables, so `bg-surface`, `text-text-3` or `border-line` work in markup:

   | Token | Value | Use |
   |---|---|---|
   | `void` | `#07080B` | outer area, status bar, text on action fills |
   | `base` | `#0A0C10` | work area (the page background, with the grid) |
   | `surface` | `#0C0E13` | bars, side panels, cards |
   | `raised` | `#10131A` | fields, hovered cards, selected items |
   | `line` | `#2B3140` | frames, dividers, inactive segments |
   | `line-strong` | `#4A5366` | hover frames, field borders |
   | `text-1` .. `text-4` | `#E8ECF1`, `#C9D1DD`, `#A3ADBD`, `#8A94A8` | text from primary to quiet |
   | `action` | palette | the one primary action, active navigation, selection, "needs attention" |
   | `data` | palette | data, links, tags, focus, finished and normal states |
   | `danger` | `#FF4D6A` | danger, overdue items and anything broken |

   `base` is declared on `:root` instead of the theme, because a theme colour named `base` would turn Tailwind's
   `text-base` font-size utility into a colour; use `bg-(--color-base)` for it in markup.

3. **Palettes switch only action and data**, through `data-palette` on the root element. `danger` and all neutrals
   stay the same in every palette.

   | Palette | Action | Data |
   |---|---|---|
   | Streetkid (default, also when the attribute is missing) | `#FCEE0A` | `#00F0FF` |
   | Corpo | `#DCE6F5` | `#5AA9FF` |
   | Nomad | `#FF9A3C` | `#4FE3C1` |

4. **Colour semantics.** Action colour = act; data colour = data, links, tags and normal or finished states; red =
   danger, overdue items and anything broken (a failed backup on Status, in the inbox and in the header); grey =
   neutral, for example "Nicht geprüft". Colour is never the only carrier of a state: every badge and step also
   has text. The shared state badge maps each backend state to one of these tones; a recurring job that keeps failing
   (`JOB_FAILING`) now reads as danger instead of amber because it is broken.

5. **Contrast.** Text has at least 4.5:1 against the surface it sits on. Measured with the WCAG 2 formula
   (`frontend` tokens, 2026-09-26):

   | Text | on `base` | on `surface` | on `raised` | worst case with effects¹ |
   |---|---|---|---|---|
   | `text-1` | 16.50 | 16.27 | 15.66 | 15.21 |
   | `text-2` | 12.72 | 12.55 | 12.08 | |
   | `text-3` | 8.64 | 8.52 | 8.20 | |
   | `text-4` | 6.41 | 6.33 | 6.09 | 5.91 |
   | `danger` | 6.08 | 6.00 | 5.77 | 5.60 |
   | Streetkid action / data | 16.19 / 13.89 | | 15.37 / 13.19 | 14.93 / 12.81 |
   | Corpo action / data | 15.55 / 7.97 | | 14.76 / 7.57 | 14.34 / 7.35 |
   | Nomad action / data | 9.26 / 12.20 | | 8.80 / 11.58 | 8.54 / 11.25 |

   ¹ `base` under both the grid line (2.2 %) and a scanline (2.5 %), the lightest pixel the effects produce.
   `void` text on an action fill: Streetkid 16.57, Corpo 15.91, Nomad 9.48; on `danger` 6.22 (white on `danger` would
   be only 3.22, so counters on red use `void`). `text-1` on the 16 % tints that replace coloured badges is at least
   11.99 in every palette; data text on a chip tint is at least 7.06 (Corpo).

   Non-text contrast: checkbox and switch outlines use `text-4` (6.4:1) because the outline is all that identifies
   them. Field borders use `line-strong` (2.5:1) as in the approved mockup; fields also have a label and a `raised`
   fill. PR 6 re-checks this with the full keyboard and contrast review.

6. **Typography, self-hosted.** Chakra Petch (400, 500, 600, 700) for UI and display text, Share Tech Mono (400) for
   data, labels, dates and counters. Both are SIL Open Font License 1.1 fonts, pinned as
   `@fontsource/chakra-petch@5.3.0` and `@fontsource/share-tech-mono@5.3.0` and bundled by Vite into
   `dist/assets`. Aegis is LAN-only and makes no request to Google Fonts or any other font host. Only the `latin`
   and `latin-ext` subsets are imported; a browser downloads a subset only when a page contains characters from its
   unicode range. The decorative 盾 ("shield") glyph of the wordmark is a single inline SVG path taken from Noto Sans JP
   Bold 5.3.0 (Adobe, SIL OFL 1.1, attribution next to the path) instead of a Japanese font. The glyph is
   `aria-hidden` and never carries information.

   Added size (production build, compared with `main` at 57d935d): app CSS +25.0 kB (+2.9 kB with `gzip -9`, now 9.4 kB), app
   JavaScript +2.8 kB (+2.1 kB gzip). A German page downloads at most 53.1 kB of fonts (four Chakra Petch weights at
   9.8-10.0 kB and Share Tech Mono at 13.5 kB, all WOFF2, cached afterwards). `dist` carries 201 kB of font files in
   total, including WOFF fallbacks and the `latin-ext` subsets that browsers only fetch when needed.

7. **Chamfers instead of rounded corners.** 45° cuts at the top left and bottom right, in five sizes: 4 px
   (checkboxes), 6 px (chips, badges), 8 px (fields, secondary buttons), 10 px (primary buttons, cards), 16 px
   (dialogs, panels). A framed element is not clipped itself; `::before` paints the fill and `::after` a one-pixel ring
   drawn as an even-odd polygon, with the inner cut 0.6 px smaller so the diagonal edge is as thick as the straight
   ones. That keeps dropdowns, focus rings and touch targets intact and lets a frame be transparent on any background.
   Elements without pseudo-elements (inputs, selects, checkboxes) are clipped and draw their diagonal edge with two
   corner gradients. No soft shadows; depth comes from lines and surfaces.

8. **Focus and touch.** Focus is always visible: a 2 px `data` outline, drawn 4 px inside chamfered elements so the
   corner cut does not hide it, `void` on action fills, and a 2 px `data` border on fields. Buttons and rows are at least
   44 px high. One primary action per view.

9. **Effects are optional and motion yields.** The effects layer is the 40 px grid (2.2 % opacity) on the work area,
   scanlines (2.5 %) over the page and a short glitch on the wordmark every 6 s. It is on by default and switched off with
   `data-effects="off"` on the root element. `prefers-reduced-motion: reduce` always stops every animation and
   transition, whatever the effects setting says; the static grid and scanlines stay.

10. **Palette and effects become per-member preferences** in PR 2, stored on the member like the locale (identity
    context, migration, API/OpenAPI, export and restore). Until then Streetkid with effects on applies; the Settings page
    offers a preview that changes the root attributes for the current tab only.

11. **Plain product language.** The look borrows from the genre; the words do not. Functional text stays plain
    German and English (Hochladen, Ordner, Papierkorb), without slang, exclamation marks or "leider". Say what happened,
    then what can be done now. Numbers, dates, paths and counters are set in the mono font. Both locale catalogues stay
    complete.

12. **Base components** live in `frontend/src/components/ui/` and use the shared classes in `styles.css`: Button
    (primary, secondary, ghost, danger), Card (attention, data, danger, selected), Chip (facet prefix, remove button),
    Badge, FileTypeTag, TextField and SearchField, SegmentedControl (a radio group with arrow keys), Switch, Checkbox,
    Dialog, Sheet (a bottom sheet on phones), ConfirmDialog (typed confirmation for irreversible actions such as #85),
    Toast (optional undo), ProcessingSteps (Gespeichert → Archiviert → Text erkannt → Eingeordnet), Countdown and the
    Wordmark. Dialogs and sheets are native `<dialog>` elements, which make the page behind them inert, trap focus and
    return it on close. The existing shared classes (`btn-*`, `card`, `field`, `label`, `badge`, `alert-*`) point to the
    same tokens and chamfers.

13. **Transitional bridge.** Until pages are redesigned, the Tailwind scales they use are re-pointed to the tokens in
    `styles.css`: `stone` to the neutral ramp, `sky` and `emerald` to the data colour, `amber` and `violet` to the action
    colour, `red` to danger (shade 100 is text on a tint, shade 900 the tint). PR 6 removes the bridge once no page uses
    these scales.

## Consequences

- Every existing page is dark and uses the new fonts, frames and colours immediately, without page rewrites; pages
  that still use the bridge follow palette changes too.
- New UI uses the token utilities and the base components instead of Tailwind's default colour scales.
- A light theme would need a second token set and is not planned.
- Fonts add about 53 kB to the first visit and nothing afterwards. They never leave the LAN.
- The effects cost one fixed overlay and a background image; members who find them distracting switch them off (PR 2),
  and reduced motion is honoured without any setting.
