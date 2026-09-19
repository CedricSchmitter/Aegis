"""Derives the committed app icons from the master artwork.

Usage (from frontend/, needs Python 3 with Pillow and WebP support):

    python scripts/generate-icons.py [icons/aegis-icon-source.webp]

Writes to public/icons/:
  icon-192.png, icon-512.png      purpose "any": the full artwork
  icon-maskable-512.png           purpose "maskable": corner brackets removed, shield inside the 80 % safe circle
  apple-touch-icon.png            180 px, full artwork on its own background (iOS adds no padding)
  favicon.svg, favicon-32.png, favicon-16.png
                                  simplified vector mark for small sizes, where the detailed face turns to mush

To swap the icon, replace the master artwork (square, dark background) and run the script again. If the new artwork
has a different layout, adjust SHIELD_RADIUS and BRACKET_BOXES below.
"""

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "public" / "icons"
DEFAULT_SOURCE = ROOT / "icons" / "aegis-icon-source.webp"

BACKGROUND = (5, 7, 11)  # #05070b, the artwork's own background; keep in sync with the manifest
CYAN = "#00f0ff"
YELLOW = "#fcee0a"

# Measured on the master artwork (fractions of its width): the farthest shield pixel, glow included, from the
# center, and the HUD corner brackets that may be dropped for the maskable variant.
SHIELD_RADIUS = 0.456
BRACKET_BOXES = [(0.09, 0.09, 0.20, 0.20), (0.80, 0.09, 0.91, 0.20), (0.09, 0.76, 0.20, 0.87),
                 (0.80, 0.76, 0.91, 0.87)]
SAFE_RADIUS = 0.39  # Android masks may cut anything outside a centered circle of radius 0.40


def resized(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.LANCZOS)


def maskable(source: Image.Image, size: int) -> Image.Image:
    art = source.copy()
    pixels = art.load()
    w = art.width
    for x0, y0, x1, y1 in BRACKET_BOXES:
        # Everything but cyan: the bracket and its glow go, the shield corner reaching into a box stays.
        for y in range(round(y0 * w), round(y1 * w)):
            for x in range(round(x0 * w), round(x1 * w)):
                r, g, b = pixels[x, y]
                if b < r + 30:
                    pixels[x, y] = BACKGROUND
    scaled = resized(art, round(size * SAFE_RADIUS / SHIELD_RADIUS))
    canvas = Image.new("RGB", (size, size), BACKGROUND)
    offset = (size - scaled.width) // 2
    canvas.paste(scaled, (offset, offset))
    return canvas


# Simplified mark on a 64-unit grid: shield outline, yellow inner border, a head with three circuit traces.
def quad(p0, p1, p2, steps=8):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / steps for i in range(1, steps + 1))]


def shield(inset: float):
    top, shoulder, side, tip = (32, 6 + inset), (54 - inset, 12 + inset), (53 - inset, 33), (32, 59 - inset * 1.4)
    right = [top, shoulder, side] + quad(side, (51 - inset, 49 - inset * 0.4), tip)
    left = [(64 - x, y) for x, y in reversed(right[:-1])]
    return right + left


OUTER = shield(0)
INNER = shield(5.5)
HEAD = [(26.5, 27), (37.5, 27), (37, 35), (32, 42), (27, 35)]
TRACES = [((32, 27), (32, 18)), ((29, 27), (22.5, 20.5)), ((35, 27), (41.5, 20.5))]
NODES = [(32, 17), (22, 20), (42, 20)]


def points(polygon):
    return " ".join(f"{x:.2f},{y:.2f}" for x, y in polygon)


def favicon_svg() -> str:
    traces = "".join(f'<line x1="{a[0]}" y1="{a[1]}" x2="{b[0]}" y2="{b[1]}"/>' for a, b in TRACES)
    nodes = "".join(f'<circle cx="{x}" cy="{y}" r="2.6"/>' for x, y in NODES)
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
        f'<rect width="64" height="64" rx="12" fill="#{"".join(f"{c:02x}" for c in BACKGROUND)}"/>'
        f'<polygon points="{points(OUTER)}" fill="none" stroke="{CYAN}" stroke-width="4.5" stroke-linejoin="round"/>'
        f'<polygon points="{points(INNER)}" fill="none" stroke="{YELLOW}" stroke-width="2" stroke-linejoin="round"/>'
        f'<g stroke="{CYAN}" stroke-width="2.4" stroke-linecap="round">{traces}</g>'
        f'<g fill="{CYAN}">{nodes}<polygon points="{points(HEAD)}"/></g>'
        "</svg>\n"
    )


def favicon_png(size: int) -> Image.Image:
    scale = 16  # draw large, then downsample for anti-aliasing
    unit = size * scale / 64
    image = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    s = lambda polygon: [(x * unit, y * unit) for x, y in polygon]  # noqa: E731
    draw.rounded_rectangle((0, 0, 64 * unit - 1, 64 * unit - 1), radius=12 * unit, fill=BACKGROUND)
    draw.line(s(OUTER + OUTER[:1]), fill=CYAN, width=math.ceil(4.5 * unit), joint="curve")
    draw.line(s(INNER + INNER[:1]), fill=YELLOW, width=math.ceil(2 * unit), joint="curve")
    for a, b in TRACES:
        draw.line(s([a, b]), fill=CYAN, width=math.ceil(2.4 * unit))
    for x, y in NODES:
        r = 2.6 * unit
        draw.ellipse((x * unit - r, y * unit - r, x * unit + r, y * unit + r), fill=CYAN)
    draw.polygon(s(HEAD), fill=CYAN)
    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    source_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    source = Image.open(source_path).convert("RGB")
    if source.width != source.height:
        sys.exit("The master artwork must be square")
    OUT.mkdir(parents=True, exist_ok=True)
    resized(source, 192).save(OUT / "icon-192.png", optimize=True)
    resized(source, 512).save(OUT / "icon-512.png", optimize=True)
    resized(source, 180).save(OUT / "apple-touch-icon.png", optimize=True)
    maskable(source, 512).save(OUT / "icon-maskable-512.png", optimize=True)
    (OUT / "favicon.svg").write_text(favicon_svg(), encoding="utf-8", newline="\n")
    favicon_png(32).save(OUT / "favicon-32.png", optimize=True)
    favicon_png(16).save(OUT / "favicon-16.png", optimize=True)
    print(f"Icons written to {OUT}")


if __name__ == "__main__":
    main()
