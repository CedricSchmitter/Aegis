# ADR-0019: A dark design system with tokens, palettes, chamfers and optional effects

Status: accepted; implemented for issue #27 in six PRs: foundation (PR 1), shell with per-member preferences (PR 2),
document screens (PR 3), Today, tasks and contracts (PR 4), inbox, status, trash, management, login and share (PR 5)
and cleanup with the contrast, keyboard and reduced-motion review (PR 6).
Date: 2026-09-26.

## Context

Aegis grew with Tailwind's default `stone`/`sky`/`amber`/`red` classes, a light and a dark theme chosen by the
operating system, and rounded cards. The owner provided a design system ("ShardVault", a desktop prototype plus a
design-system PDF) and approved a clickable phone and desktop mockup of Aegis in that style on 2026-09-26 (issue #27,
comment "Owner approval and implementation plan"). The redesign ships in six focused PRs; every PR must leave every page
readable. This ADR records the visual rules that all six follow. ShardVault's domain features (ICE levels, versions,
approval workflow, quarantine, access requests, storage quota) do not apply to Aegis and are not part of it.

## Decision

1. **Dark only.** The light theme is removed. `:root` declares `color-scheme: dark` and the page always uses the
   dark tokens. No markup uses the `dark:` variant any more, and the always-on variant is gone (PR 6).

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
   `text-base` font-size utility into a colour; use `bg-(--color-base)` for it in markup. Tailwind's default colour
   scales are switched off (`--color-*: initial`), so these tokens are the only colours markup can name; the one
   addition is `white`, the paper behind document previews.

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

   Non-text contrast (WCAG 1.4.11, re-checked in PR 6): checkbox and switch outlines use `text-4` (6.41:1 on `base`,
   6.09 on `raised`) because the outline is all that identifies them. The focus ring is the data colour, at least
   7.97:1 on `base` (Corpo) and 7.57 on `raised`; on an action fill it is `void` (at least 9.48, Nomad). Field borders
   keep `line-strong` (2.54 on `base`, 2.41 on their `raised` fill) as in the approved mockup: every field has a visible
   label, and the search field its icon (`text-3`, 8.20) and placeholder (`text-4`, 6.09), so the border never
   identifies a field on its own. Frames and dividers in `line` (1.51) only group content.

   **PR 6 review (2026-09-27).** A Playwright walk over the built app, served with invented data from
   `api/openapi.json` in the browser, covered every page and the login, the upload and filter sheets and the avatar
   menu at 375×812, 1440×900, 1280×800 and 1100×800, in Streetkid, Corpo (English) and Nomad. It measured every text
   against the colour behind it, including the `::before` fill of chamfered frames, tabbed through 40 stops per page,
   measured every target and counted primary actions and running animations:
   - text contrast: nothing below 4.5:1; the lowest value was 5.26 (Streetkid), 5.52 (Corpo) and 5.29 (Nomad), the
     login 6.41;
   - reduced motion: no running animation on any page; with motion allowed only the wordmark glitch and the
     processing steps run;
   - fonts: no request to any host other than Aegis;
   - fixed: the chamfered checkboxes (`ui-checkbox`, `task-check`) are clipped, which hid their outside focus
     outline entirely; they now show the ring of decision 8. The document viewer's iframe showed no focus while its
     content had it (no focus pseudo-class matches an iframe then); the page now marks it with `data-focused`. Title
     links that were the only target of a card or row (trash entries, a document's linked contracts, the desktop
     document table below 1280 px) were 20-24 px high; the link now covers its card or row. The search field's box
     is a label, so its whole 44 px height focuses the input, and the folder tree's expand button is 44 px high;
   - desktop pages with their own primary action (Neue Aufgabe, Ordner anlegen, Erledigt, Wiederherstellen) show it
     next to the sidebar's Upload, both in the action fill, as in the approved mockup. Every sheet and every phone
     screen has at most one.

   Real Android checks (uploads, sharing into Aegis, notifications, the redesigned screens) on both members' phones
   are the owner's and are recorded in issue #27, not here.

6. **Typography, self-hosted.** Chakra Petch (400, 500, 600, 700) for UI and display text, Share Tech Mono (400) for
   data, labels, dates and counters. Both are SIL Open Font License 1.1 fonts, pinned as
   `@fontsource/chakra-petch@5.3.0` and `@fontsource/share-tech-mono@5.3.0` and bundled by Vite into
   `dist/assets`. Aegis is LAN-only and makes no request to Google Fonts or any other font host. The per-weight
   files (`400.css`, not `latin-400.css`) are imported because only they declare each subset with its
   `unicode-range`; a browser then downloads a subset (latin, latin-ext, Vietnamese, Thai) only when a page contains
   characters from it. Vite never inlines font files into the CSS (`assetsInlineLimit` in `vite.config.ts`), because
   an inlined subset would be downloaded on every visit. The decorative 盾 ("shield") glyph of the wordmark is a single inline SVG path taken from Noto Sans JP
   Bold 5.3.0 (Adobe, SIL OFL 1.1, attribution next to the path) instead of a Japanese font. The glyph is
   `aria-hidden` and never carries information.

   Added size (production build, compared with `main` at 57d935d): app CSS +28.3 kB (+3.3 kB with `gzip -9`, now 9.8 kB),
   app JavaScript +2.8 kB (+2.1 kB gzip). A German or English page downloads 53.1 kB of fonts once (the latin subset of
   four Chakra Petch weights at 9.8-10.0 kB each and Share Tech Mono at 13.5 kB, all WOFF2; measured live on VM 102).
   `dist` carries 305 kB in 34 font files, including WOFF fallbacks and the other subsets that browsers fetch only
   when needed.

7. **Chamfers instead of rounded corners.** 45° cuts at the top left and bottom right, in five sizes: 4 px
   (checkboxes), 6 px (chips, badges), 8 px (fields, secondary buttons), 10 px (primary buttons, cards), 16 px
   (dialogs, panels). A framed element is not clipped itself; `::before` paints the fill and `::after` a one-pixel ring
   drawn as an even-odd polygon, with the inner cut 0.6 px smaller so the diagonal edge is as thick as the straight
   ones. That keeps dropdowns, focus rings and touch targets intact and lets a frame be transparent on any background.
   Elements without pseudo-elements (inputs, selects, checkboxes) are clipped and draw their diagonal edge with two
   corner gradients. No soft shadows; depth comes from lines and surfaces.

8. **Focus and touch.** Focus is always visible: a 2 px `data` outline, drawn 4 px inside chamfered elements so the
   corner cut does not hide it, `void` on action fills, and a 2 px `data` border on fields. Clipped controls (checkboxes)
   cannot show an outline, so their edge turns into a 2 px `data` ring with a `void` band inside it (`focus-edge`),
   which also stands apart from a checked fill. A card or row whose title link covers it shows the ring on the card. Buttons and rows are at least
   44 px high. One primary action per view. Scroll padding on the root keeps what is scrolled into view or focused
   clear of the sticky header, the phone tab bar and the desktop status bar (WCAG 2.4.11); before it, a button could
   sit half under the status bar and a click landed on the bar.

9. **Effects are optional and motion yields.** The effects layer is the 40 px grid (2.2 % opacity) on the work area,
   scanlines (2.5 %) over the page and a short glitch on the wordmark every 6 s. It is on by default and switched off with
   `data-effects="off"` on the root element. `prefers-reduced-motion: reduce` always stops every animation and
   transition, whatever the effects setting says; the static grid and scanlines stay.

10. **Palette and effects are per-member preferences**, stored on the member like the locale. The identity context
    owns them: the `Appearance` value object (`Palette` and whether effects are on) on the `Member` aggregate, migration
    `V1_3__member_appearance` (every existing member keeps Streetkid with effects on), `PUT /api/members/me/appearance`
    and the `appearance` next to the member in `GET /api/auth/session` and the login response. Changes use the member's
    existing optimistic-concurrency version, like the locale. The member directory (`GET /api/members`) does not publish
    another member's look. The web app applies the look from the session right after login, applies a change at once and
    goes back to the stored look if saving fails; after logout the default applies again. Export schema 10 carries each
    member's palette and effects; a restore gives them to a member it has to create, while an existing member keeps their
    own. The preference only changes presentation, never what a member can see or do, and `prefers-reduced-motion`
    still stops every animation whatever the setting says.

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

13. **No transitional bridge.** While pages were redesigned (PRs 1-5), `styles.css` re-pointed the Tailwind scales they
    still used (`stone`, `sky`, `emerald`, `amber`, `violet`, `red`) to the tokens. PR 6 replaced the last uses (the
    scan corner editor) with tokens, removed the bridge and switched the default scales off (decision 2).

14. **Shell.** The shell follows the approved mockup (issue #27, PR 2):
    - **Phone (below 1024 px):** a header with the wordmark (a link to Today) and the avatar, and a tab bar with the five
      areas Heute, Dokumente, Aufgaben, Verträge and Eingang (#83 F5). Tabs are at least 60 px high and carry
      `aria-current`; the inbox count uses the action colour, because open cards ask to be looked at. Status left the tab
      bar: a status line at the top of Today and the avatar lead to it.
    - **Avatar:** a disclosure (not an ARIA menu) with Settings, Status (with its state), Trash and logout. It closes on
      Escape, a click outside and navigation. A red mark on the avatar says that the status overview reports a problem.
    - **Desktop (from 1024 px):** three columns. The sidebar (232 px) holds Upload as the one primary action, the five
      areas with the document and inbox counts, the top-level folders and Unsorted with their document counts (including
      subfolders) and "Ordner verwalten", and Trash and Settings at the bottom. The work area keeps the pages at their
      previous maximum width. The context panel (340 px) appears from 1280 px only while a page renders into it through
      `ContextPanel`; the page redesigns (PRs 3-5) fill it with the selection, and below 1280 px a page shows the same
      content in its own flow. The header carries the wordmark, a document search that Ctrl+K (Cmd+K) focuses and that
      opens the document list with the query, the status indicator, the inbox with its count and the avatar with the
      member's name. A status bar (28 px) at the bottom shows the overall state, the document count, the last backup and
      the running version.
    - **Status indicator:** one request to the status overview (`GET /api/status`, ADR-0018) per minute while the tab is
      visible serves the header, the avatar mark, the status bar and Today's status line. It reads "Alles in Ordnung" in
      the data colour, "Problem: Backup" or "2 Bereiche brauchen Aufmerksamkeit" in red, and "Status nicht abrufbar" in
      grey while the overview is not known. An unchecked AI connection stays neutral, as on the status page.

15. **Documents.** The document screens follow the approved mockup (issue #27, PR 3, and the arrangement of #108):
    - **Phone list:** the title with the total, Upload as the one primary action (it reads "Scan fortsetzen" while a
      scan draft exists), the search, a swipeable folder rail (All, Unsorted, the folders of the current level with
      their counts including subfolders, and "Ordner verwalten" at its end; a chosen folder with subfolders shows them,
      a breadcrumb leads back up), the filter button with the active values as removable chips and "Filter
      zurücksetzen", then cards. A card shows the type tag (PDF, Scan for a PDF of scanned pages, Foto), the title, the
      date, the folder path and the labels in the data colour; the whole card opens the document. The trash link
      closes the list. Settings, Status and Trash also stay behind the avatar.
    - **Desktop list:** the sidebar's folders become an expandable ARIA tree (arrow keys, Home/End, Enter) with
      Unsorted at its end and "Ordner verwalten" below; the page has no folder panel and no second search, because
      the header search serves it. The list is a table (type, name with folder and labels, state, date). From 1280 px
      a click selects a row, marks it with target brackets and shows the document in the context panel (preview,
      where it stands, the text hint, the folder suggestion and label suggestions to take over or reject, "Groß
      öffnen"); a double click, Enter on the title or "Groß öffnen" opens it. Below 1280 px the title opens it.
      A drop target ends the list and opens the upload with the dropped file.
    - **State badges** appear only for deviations (#83 F10) and carry `data-state`; label chips use the chip class,
      so page checks select states by `data-state`, never by class, and never read household organization names.
    - **Filter:** a sheet with the facet values as pills with counts, "N Treffer zeigen", "Filter zurücksetzen" and
      "Labels verwalten". Resetting clears the facet values only; the folder and the search stay.
    - **Upload and scan** live in one sheet (a bottom sheet on a phone, a centred panel on a desktop): camera, gallery
      and "Datei unverändert hochladen" as options on a phone; a drop zone with "Datei wählen" and "Fotos als Scan"
      on a desktop, where browsers open no camera. The sheet cannot be closed while the server has not answered, so a
      failure is always seen; after a confirmed durable save it closes and the list says so. The scan draft stays in the browser
      when the sheet closes and reopens it after a reload. The corner editor renders inside the open sheet, because a
      modal dialog lies in the top layer above the rest of the page; its Escape closes only the editor. The sidebar's
      "Hochladen" and "Neues Foto hochladen" on a document without text open `/documents?upload=1`; the parameter is removed
      again, so the same link works a second time.
    - **Document view:** back link, folder path, title, type and uploader, then "Original öffnen", "Original
      herunterladen" and "Durchsuchbares PDF". A phone shows the preview; from 1024 px the document itself appears in
      the browser's own viewer: a photo as an image, a PDF of scanned pages and a HEIC photo as the archive's
      searchable PDF, any other PDF as the original. The steps Gespeichert → Archiviert → Text erkannt → Eingeordnet
      (filed means lying in a folder), the processing state, the analysis, folder, labels, subjects, linked contracts
      and tasks, title and technical data and the trash fill the context panel from 1280 px and follow in the page
      below it. Content that moves between panel and page is rendered once (`ContextPanelOrFlow`), so ids and form
      state exist a single time. "In den Papierkorb" acts at once and offers undo (decision 17).
    - **Preview images** come from the archive: `GET /api/documents/{id}/thumbnail` serves the first-page image
      Paperless renders for every document it stores, once the document is archived (404 before). The adapter
      accepts only WebP, PNG or JPEG recognized from the bytes, and the browser may keep it privately for a day. It is
      derived data like the archive PDF and is not exported. Aegis now answers `X-Frame-Options: SAMEORIGIN` instead
      of Spring's `DENY`, so the viewer can frame originals from the same origin while other sites still cannot.
    - After an upload or a move the page sends `aegis:catalog-changed`, so the sidebar's folder counts follow at once.

16. **Today, tasks and contracts.** These screens follow the approved mockup (issue #27, PR 4):
    - **Today:** the weekday and date, the big title and the status line, then Überfällig (a red heading and red
      frames), Heute fällig, Heute geplant, the cancellation deadlines of active contracts within 30 days (each with
      the large Countdown in the action colour) and Nächste 7 Tage; tasks nobody is responsible for keep their
      own section at the end, as the deterministic Today view (`TodayPolicy`) still returns them. A cancellation
      review whose deadline is already counted down is not listed a second time. On a desktop the urgent tasks
      stand left and the next seven days right; the deadlines and a summary of the inbox (cards to review and new
      messages, from the shell's counts) fill the context panel from 1280 px and follow below the columns before.
    - **Task cards** (Today, the task list and a document's linked tasks): the checkbox completes a task at once
      with the version the member saw and offers undo in a message, and it reopens a completed one. The rest of the
      card opens the task. The due date ("Frist", overdue "Überfällig seit" in red) and the planned work day
      ("Geplant", data colour) stay separate, next to the source and the responsible member as an initial.
    - **Task list:** Offen or Alle as a segmented control, responsibility (everyone, each member, nobody) as a rail,
      "Neue Aufgabe" as the one primary action. From 1280 px a click selects a task, marks it with the action frame
      and shows it in the context panel; a double click or Enter opens it.
    - **Task view:** deadline, planned work day and responsibility, the source document and, for a cancellation
      review, its contract; "Erledigt" is the primary action with Zuweisen, Arbeit umplanen, Frist ändern and
      Bearbeiten below it. Rescheduling changes the planned day only; a cancellation review has no deadline change
      at all, because its due date is the contractual deadline.
    - **Contracts:** each contract with its cancellation date and Countdown, highlighted with the action frame from
      30 days before it and red once it has passed while the contract is active; the status appears as a badge only
      when a contract is no longer active. From 1280 px the selected contract fills the context panel with its
      deadline, conflicts and current statements to confirm or reject; history, new statements, renaming, the status
      and the trash stay on the contract page ("Vertrag öffnen").
    - **Contract view:** the cancellation date with the Countdown and its derivation (the rule, the confirmed
      statements it rests on and the evaluation day), then every statement with its source document and page:
      "Bestätigt" in the data colour, "Vorschlag" in the action colour with "Bestätigen" as its primary action. A
      rejected statement reads as neutral history instead of danger.
    - Cards in the context panel lie on `base`, so they stand out from the panel's `surface`.

17. **Inbox, status, trash, management, login and share.** These screens follow the approved mockup (issue #27, PR 5):
    - **Inbox:** "Zu prüfen" and "Mitteilungen" are two segments on a phone (both stay mounted, so switching keeps
      their state; an old `/notices` link or new messages with no open card start on the messages) and sit side by
      side on a desktop; from 1280 px the messages fill the context panel. A review card names its category, says
      what happened and why it matters, and has one highlighted action (the card's action, such as retrying the
      archival, otherwise the link to its document or contract) with "Ausblenden" quietly next to it. A failed
      archival or analysis is red; other cards only ask for a decision. The card states (open, resolved, dismissed,
      outdated) are a rail of pills, because dismissing has no undo in the API and the dismissed cards stay findable
      there; the category filter was dropped, as the category stands on every card. A message is one link that opens
      its target and reads it; a failed backup, low disk space and a stopped job are red, other unread messages carry
      the data frame and "Neu".
    - **Status:** the head card answers "Ist alles in Ordnung?" in the data colour or red, then one row per area with
      its sentence and a mono state word (`data-state`); a broken area is red, "Noch nicht geprüft" stays grey. Below,
      "Was jetzt?" in a red card names what to check or do for every broken area (the backup hint plus the note that
      the documents are not affected, or a text per reason). "Technische Details" stays collapsed, now as rows instead
      of cards. From 1280 px Settings and Trash show the same overview, compact, in the context panel.
    - **Trash:** "In den Papierkorb" on documents, tasks and contracts acts at once; the message that follows says
      until when the record stays restorable and offers undo, which restores with the version the trash returned. The
      page keeps the message, so it stays when a task leaves the list and the context panel. The record view shows the
      trash banner with "Wiederherstellen" as its primary action. The trash page filters by kind with pills and
      confirms a restore in a message. Typed confirmation (ConfirmDialog) stays reserved for irreversible actions;
      permanent deletion (#85) is not built, so no page uses it yet.
    - **Settings:** rows to Status (with its state), Trash, "Ordner verwalten" and "Labels verwalten", then the
      palette as a radio group with colour samples, the effects switch, the username and language, the password
      form (collapsed) and logout.
    - **Folder and label management:** a back link to Documents, the big title with the count, then rows instead of
      cards: a folder row shows its counts in mono and the labels and subjects it passes on as chips; a label row
      shows a badge only when the label is built in, retired or merged. Editing opens in the row. Deleting a folder
      asks in a dialog instead of the browser's confirm, because it cannot be undone, but it is not typed: no
      document is deleted.
    - **Login and share:** the login shows the wordmark, one framed form and the version; the share page lists the
      shared files as cards with a state badge only once something happened (saving, saved, failed), and discarding
      all asks in a dialog instead of the browser's confirm.

## Consequences

- Every page is dark, uses the new fonts, frames and colours and follows palette changes; a colour outside the tokens
  produces no CSS at all.
- New UI uses the token utilities and the base components instead of Tailwind's default colour scales.
- A light theme would need a second token set and is not planned.
- Fonts add about 53 kB to the first visit and nothing afterwards. They never leave the LAN.
- The effects cost one fixed overlay and a background image; members who find them distracting switch them off in
  Settings, and reduced motion is honoured without any setting.
- Two members on one device see their own look after login; the login page always shows the default.
- The shell asks the status overview once a minute, which probes the archive; a household of two keeps that load
  negligible.
- Today asks for the confirmed cancellation deadlines next to the task buckets, one more request per visit.
- A document preview costs one thumbnail request to the archive per document and day and browser; the viewer loads
  the whole original or searchable PDF, as "Original öffnen" did before. Headless browsers without a PDF viewer show
  an empty frame there; members' browsers (Chrome, Edge, Firefox) render it.
