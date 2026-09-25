# ADR-0016: Photos from the camera and the gallery become straightened pages of one PDF, assembled in the browser

Status: accepted and implemented for issue #81. Date: 2026-09-25. Changes decisions 2 and 7 of
[ADR-0011](ADR-0011-photo-originals.md) for photos taken through the camera and gallery buttons.

## Context

A phone photo of a letter is the most common capture. [ADR-0011](ADR-0011-photo-originals.md) stored each photo
unchanged as its own document and let Paperless produce the searchable PDF. Two problems remained:

- **Rotation defeats text recognition.** Measured against the deployed Paperless (issue #81): a page held at 3
  degrees is read completely, at 5 degrees only about a fifth of the words, from 7 degrees on nothing. Paperless'
  own `deskew` does not rescue it. The 10 degree fixture `synthetic-letter-photo-skewed.jpg` is read as no text.
- **A multi-page letter became several documents**, one per photo, and the archive PDF of a 12-megapixel photo
  tagged with 72 dpi has a page of about 106 × 141 cm.

The owner chose on 2026-09-25 that the photos held before saving form **one** document, that processing runs
automatically with a preview and a manual correction, and that the generated PDF is the original (variant A in
#81).

## Decision

1. **Capture paths.** "Foto aufnehmen" (camera) and "Bild auswählen" (photo picker, several at once) add pages to
   a scan in progress; "Weitere Seite fotografieren" continues it. Everything held before saving is one document.
   "Datei auswählen" keeps uploading one PDF or photo unchanged (ADR-0011), for example a HEIC photo that the
   browser cannot open or a photo that should stay untouched. It is hidden while a scan is in progress, so a file
   can never silently replace scanned pages. The Android share target is unchanged: shared files are uploaded
   unchanged, one document each.
2. **Processing in the browser, per page, automatically.** Right after capture a Web Worker decodes the photo
   upright (EXIF orientation applied, long side at most 4096 px) and uses OpenCV.js 4.12 to find the sheet:
   a brightness mask and two edge masks each yield a candidate quadrilateral; the largest plausible one wins,
   because a mask that misses part of the sheet (a shadow across it) gives a smaller quad and cutting off text is
   the worst outcome. A candidate is plausible when it is convex, covers at least 20 % of the photo, has interior
   angles between 55 and 125 degrees, has at least three edges of its own (not the photo frame) and lies at most
   12 % outside the photo; a corner cut off by the photo edge is restored by intersecting the sheet's edges. The
   sheet is warped to an upright rectangle (at most A4 at 300 dpi, never enlarged) and given the scan look:
   shadows, uneven light and the paper tint are divided out per colour channel, so paper becomes white while ink,
   stamps and signatures keep their colours.
3. **Fallback and correction.** Without a plausible sheet the page keeps the whole photo, only reduced to the same
   size limit, and says so ("Blatt nicht erkannt"), with the advice to use a dark surface. Every page is shown as a
   thumbnail before saving; tapping it opens a corner editor (drag with a finger or move with the arrow keys),
   which also offers "Ganzes Foto" and "Originalfoto verwenden". Pages can be rotated by quarter turns, reordered
   and removed. Detected corners are fixed after the first processing, so a later rotation never moves them.
4. **The generated PDF is the original.** Saving assembles one PDF in the browser: one page per image, the page
   JPEG (quality 0.85) embedded unchanged, the long side 841.89 pt (A4), so a straightened A4 sheet prints at
   100 % and Paperless sees 300 dpi. The upload then follows the normal durable capture path unchanged: the server
   recognizes a PDF by content, stores it, and acknowledges only after durable storage. The photos are not kept
   after a successful save, like the paper under a flatbed scanner. The server needs no change.
5. **No duplicates on retry.** The PDF writer is deterministic (no dates or random ids), and the page JPEGs are
   stored with the scan, so an attempt repeated after a lost answer produces the same bytes. The existing checksum
   duplicate detection returns the first document instead of creating a second one.
6. **Scans survive a closed tab.** Android may close the browser while the camera app is open. The scan in
   progress (photos, page results, settings and order) is kept in the browser's IndexedDB per member and offered
   again after a reload; pages whose processing was interrupted are processed again. Saving or discarding removes
   it. This storage is best effort: in a private window or with blocked site data the scan lives in memory only.
7. **Size bounds.** At most 30 pages per scan; a page is about 0.3 to 1 MB, well under the 50 MB upload limit,
   which the app checks before sending. OpenCV.js (about 10 MB, about 3 MB compressed by Caddy) is bundled into
   the scan worker, which is started for the first page only; the app bundle does not grow.

## Consequences

- Photos taken through the camera and gallery buttons are no longer stored as JPEG/PNG/WebP/HEIC originals; their
  original is an image PDF without a text layer. Search uses the archive's OCR text as for any scanned PDF
  (ADR-0012). ADR-0011 still describes photos uploaded through "Datei auswählen" or the share target.
- A wrong automatic crop could cut off text; the preview before saving, the corner editor and the rule "largest
  plausible sheet" address this, and "Originalfoto verwenden" always gives the whole photo back.
- Detection is measured on synthetic photos (tilt up to 15 degrees, keystone, shadow band, dim light, light
  surface, sheet filling the frame, a corner cut off): all were straightened except white paper on a white surface,
  which falls back to the photo as intended. With the deployed Paperless' OCR options, the scanned pages of the
  tilted, shadowed, dim and frame-filling photos gave 54 to 56 of 56 words where the unchanged photos gave 0 to 6.
  Details, and the results with real letters on both household phones, are recorded in issue #81.
- The worker needs module workers, `OffscreenCanvas` and `createImageBitmap` with orientation, available in Chrome
  on Android. Browsers without them report that the photo could not be processed and point to "Datei auswählen".
