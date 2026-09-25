# ADR-0011: A photo of a paper document stays the original; the archive supplies the searchable PDF

Status: accepted and implemented for issue #80. Date: 2026-09-23. Since 2026-09-25 photos taken through the camera
and gallery buttons become scanned pages of one PDF ([ADR-0016](ADR-0016-scanned-photo-pages.md)); this record
still applies to photos chosen through "Datei auswählen" and to shared photos.

## Context

The most common household capture is a phone photo of a letter. Until now Aegis accepted PDFs only: capture
required a `.pdf` name and the `%PDF-` signature, the Paperless adapter declared every file as `application/pdf`,
and readable filenames turned any other type into `.bin`. Lisa and Cedric had to convert photos on the phone first.

The deployed Paperless-ngx 3.1.3 was checked on VM 102 on 2026-09-23:

- It recognizes the type of an upload from the content with libmagic 5.46 and consumes `image/jpeg`, `image/png`,
  `image/webp`, `image/heic`, `image/tiff`, `image/bmp` and `image/gif` through its Tesseract parser. libmagic
  reports `image/heic` only for the ISO base media major brands `heic` and `heix`; a generic HEIF container
  (`mif1`), HEIF sequences and AVIF get other types, which Paperless refuses.
- With the default `PAPERLESS_ARCHIVE_FILE_GENERATION=auto` it always generates an archive PDF with a text layer
  for images (for PDFs only when they have no text of their own) and keeps the uploaded file unchanged as the
  original. The document field `archived_file_name` names the archive version; the download endpoint serves it
  without `original=true`.

Several photos combined into one multi-page PDF are issue #81, which also assesses cropping, perspective correction
and contrast.

## Decision

1. **Accepted formats.** PDF, JPEG, PNG, WebP and HEIC. TIFF, BMP and GIF are not offered: phone cameras do not
   produce them and a GIF is usually an animation. `OriginalFormat` (documents domain) recognizes the format from
   the first 16 bytes: the signature plus the parts of the header that show a damaged file (PNG's `IHDR` chunk, the
   WebP chunk type, the size and major brand of the HEIC `ftyp` box). The uploaded name and the declared media type
   are ignored; anything else is refused with `document.unsupportedType` before a record exists, and the staged
   file is removed. Upload names no longer need an extension. The web app applies the same rules to answer early
   in the member's language; the server decides.
2. **The photo is the original.** Its bytes are stored unchanged in ingress as `<id>.<ext>`, checksummed and
   acknowledged only after durable storage, exactly like a PDF. Duplicate detection by checksum is unchanged,
   including documents in the trash. Archival submits the file with its verified media type. Aegis does not
   convert, compress, rotate or crop photos.
3. **States.** Capture, archival/OCR and analysis stay separate (ADR-0004). The text for full-text search and
   automatic classification is the archive's OCR text, as for scanned PDFs. Aegis does not decode images, so it
   cannot tell a photo whose data was cut off from a complete one: the archive decodes what it can, and a JPEG cut
   off after 55 % was archived with the text of its readable part.
4. **Downloads.** "Download original" serves the photo with its own media type, and readable filenames use the
   extension of the verified type (`.jpg`, `.png`, `.webp`, `.heic`). The archive's searchable PDF is offered as a
   separate download (`GET /api/documents/{id}/archive-pdf`, "Durchsuchbares PDF") once the document is archived
   and the archive keeps an archive version; the web app shows it for photos and, since
   [ADR-0016](ADR-0016-scanned-photo-pages.md), for PDFs of which the archive keeps an archive version. Most
   browsers, including Chrome on Android, cannot display HEIC, and a letter is usually passed on as a PDF. The archive PDF is derived data owned
   by Paperless: it is served as the archive delivers it after a PDF signature check (Aegis holds no checksum for
   it), it never replaces the original, and it is not exported because the archive generates it again from the
   original.
5. **Portability.** An export writes each original under its readable filename with the extension of its type and
   keeps the media type in `data/documents.json`, a field that exists since schema 1; the schema does not change.
   A restore takes the type from the restored bytes, which the exported checksum ties to the original. Older
   generations contain PDFs only and restore as before.
6. **Limits.** The existing 50 MB upload limit applies (`aegis.archival.max-upload-bytes`, the multipart limit and
   Caddy's 60 MB request limit). Aegis sets no pixel limit; Paperless applies Pillow's decompression limit of about
   179 megapixels, above which archival fails visibly. Typical phone photos have 12 to 50 megapixels.
7. **Capture on the phone.** The upload panel offers "Foto aufnehmen" (`capture="environment"`, which opens the
   rear camera on Android), "Bild auswählen" (photo picker) and "Datei auswählen" (PDFs and photos), with a
   preview of the chosen photo. A camera capture is named "Foto <date> <time>" because the browser's temporary name
   carries no information; photos from the gallery or the share sheet keep their names. The Android share target
   already accepted every file type, so its manifest is unchanged; the share page now accepts photos by content.

## Consequences

- A photographed letter is searchable by its text and classified and filed like a PDF once the archive has
  recognized its text. Poor text leads to suggestions rather than applied values (ADR-0004, `POOR_OCR`).
- Measured on 2026-09-23 with synthetic photos of a letter against the deployed Paperless (its own OCR options:
  `clean`, `deskew`, `rotate_pages`): straight shots are read completely as JPEG, PNG, WebP and HEIC, also at 55 %
  brightness and with a keystone from a tilted phone. **Rotation is the limit**: 3 degrees were read completely,
  5 degrees gave 14 of 60 words, 7 degrees and more gave no text. A very dark, noisy 2-megapixel photo lost all
  text in Paperless' deskew step. Such a document is archived and keeps its title, labels and folder, but its
  text is not searchable; Aegis marks it and advises a new capture ([ADR-0012](ADR-0012-recognized-text.md)). Scan
  improvements (straightening, cropping, contrast) are assessed in #81.
- Paperless sizes the archive PDF page from the photo's resolution metadata: a 12-megapixel photo tagged with
  72 dpi, as phones usually do, becomes a 3000 × 4000 pt page (about 106 × 141 cm) that is scaled when printed;
  photos without such metadata get an A4-sized page.
- HEIC originals can only be viewed after download in an app that supports them; the searchable PDF is the way to
  read them in the browser.
- Verification with real letters on both household phones (good light, poor light, a skewed shot) is recorded in
  issue #80.
