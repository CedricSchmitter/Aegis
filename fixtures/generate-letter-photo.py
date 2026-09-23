"""Generates synthetic-letter-photo.jpg: a phone-style photo of a made-up letter for photo-capture tests.

The letter, sender and numbers are invented. The "photo" is simulated: the page lies slightly rotated on a darker
surface with uneven light and sensor noise, and the JPEG carries 72 dpi like most phone cameras. It keeps the
fixture small while giving the archive's text recognition realistic work.

    python fixtures/generate-letter-photo.py            # needs Pillow; writes the JPEG and its .sha256 next to it
"""
import hashlib
import pathlib
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
OUTPUT = HERE / "synthetic-letter-photo.jpg"
FONTS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "C:/Windows/Fonts/arial.ttf",
]
BOLD_FONTS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
]
LINES = [
    "Wasserwerk Beispieltal",
    "Am Brunnen 1, 99999 Beispieltal",
    "",
    "Familie Muster",
    "Gartenweg 7",
    "99999 Beispieltal",
    "",
    "Beispieltal, 22. September 2026",
    "",
]
BODY = [
    "Sehr geehrte Familie Muster,",
    "",
    "bitte lesen Sie den Wasserzähler bis zum",
    "15. Oktober 2026 ab und senden Sie uns den",
    "Zählerstand zurück. Ohne Ablesung schätzen",
    "wir den Verbrauch für die Jahresabrechnung.",
    "",
    "Mit freundlichen Grüßen",
    "Ihr Wasserwerk Beispieltal",
]


def font(candidates, size):
    for path in candidates:
        if pathlib.Path(path).is_file():
            return ImageFont.truetype(path, size)
    raise SystemExit("no TrueType font found; install DejaVu Sans")


def page():
    paper = Image.new("RGB", (1000, 1414), (246, 244, 237))
    draw = ImageDraw.Draw(paper)
    regular, heading = font(FONTS, 30), font(BOLD_FONTS, 52)
    y = 90
    for line in LINES:
        draw.text((90, y), line, fill=(35, 35, 40), font=regular)
        y += 42
    draw.text((90, y + 10), "Ablesekarte Wasser", fill=(20, 20, 25), font=heading)
    y += 110
    for line in BODY:
        draw.text((90, y), line, fill=(35, 35, 40), font=regular)
        y += 44
    return paper


def photograph(paper):
    rng = random.Random(80)
    canvas = Image.new("RGB", (1200, 1600), (96, 78, 62))
    rotated = paper.rotate(-2.5, resample=Image.BICUBIC, expand=True, fillcolor=(96, 78, 62))
    canvas.paste(rotated, (70, 70))
    # Uneven light: full brightness at the top left, about a quarter darker at the bottom right.
    corners = Image.new("L", (2, 2))
    corners.putdata([255, 225, 225, 190])
    shade = corners.resize(canvas.size, Image.BILINEAR).convert("RGB")
    canvas = ImageChops.multiply(canvas, shade)
    # Seeded sensor noise, so a regenerated fixture with the same fonts is identical.
    width, height = canvas.size
    noise = Image.frombytes("L", canvas.size, rng.randbytes(width * height)).convert("RGB")
    canvas = Image.blend(canvas, noise, 0.05)
    return canvas.filter(ImageFilter.GaussianBlur(0.6))


def main():
    photo = photograph(page())
    photo.save(OUTPUT, "JPEG", quality=82, dpi=(72, 72), optimize=True)
    digest = hashlib.sha256(OUTPUT.read_bytes()).hexdigest()
    OUTPUT.with_name(OUTPUT.name + ".sha256").write_text(f"{digest}  {OUTPUT.name}\n", encoding="ascii")
    print(OUTPUT, OUTPUT.stat().st_size, digest)


if __name__ == "__main__":
    main()
