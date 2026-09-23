# Synthetic test fixtures

Only synthetic, non-personal material lives here. `synthetic-contract.pdf` is a generated one-page PDF with
embedded text (no scan) used by the backend integration tests, the browser smoke test and manual verification.

`synthetic-letter-photo.jpg` is a simulated phone photo of an invented letter ("Ablesekarte Wasser"): slightly
rotated on a darker surface, uneven light, sensor noise, 72 dpi like phone cameras. The browser tests use it to
check that a photo is archived with text recognition and found by its text. `generate-letter-photo.py` recreates
it (needs Pillow; the bytes depend on the installed fonts, so update the `.sha256` file together with the image).

Known SHA-256 of the original: see `synthetic-contract.pdf.sha256`. A downloaded original must match it byte for
byte; Paperless keeps the original unchanged and generates a separate archive rendition.
