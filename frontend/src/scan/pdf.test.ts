import { describe, expect, it } from "vitest";
import { buildPdf, pageSize } from "./pdf";

const latin1 = (bytes: Uint8Array) => new TextDecoder("latin1").decode(bytes);

/** A stand-in for JPEG data: the writer embeds the bytes unchanged and never decodes them. */
const jpeg = (marker: number) => Uint8Array.of(0xff, 0xd8, 0xff, marker, 1, 2, 3, 0xff, 0xd9);

describe("scan PDF", () => {
  const pages = [
    { jpeg: jpeg(1), width: 2480, height: 3508 },
    { jpeg: jpeg(2), width: 3508, height: 2480 },
  ];

  it("is a PDF with one page per image, in order, each image embedded unchanged", () => {
    const pdf = buildPdf(pages);
    const text = latin1(pdf);
    expect(text.startsWith("%PDF-1.4\n")).toBe(true);
    expect(text.trimEnd().endsWith("%%EOF")).toBe(true);
    expect(text).toContain("/Count 2");
    expect(text).toContain("/Kids [3 0 R 6 0 R]");
    expect(text.indexOf(latin1(jpeg(1)))).toBeLessThan(text.indexOf(latin1(jpeg(2))));
    expect(text).toContain("/Width 2480 /Height 3508");
    expect(text).toContain("/Filter /DCTDecode /Length 9");
  });

  it("points every cross-reference entry and startxref at the right byte", () => {
    const pdf = buildPdf(pages);
    const text = latin1(pdf);
    const startxref = Number(/startxref\n(\d+)\n/.exec(text)![1]);
    expect(text.slice(startxref, startxref + 4)).toBe("xref");
    const entries = [...text.slice(startxref).matchAll(/^(\d{10}) 00000 n $/gm)].map((match) => Number(match[1]));
    expect(entries).toHaveLength(8);
    entries.forEach((offset, index) => expect(text.slice(offset, offset + 12)).toMatch(new RegExp(`^${index + 1} 0 obj\n`)));
  });

  it("gives the same bytes for the same pages, so a repeated upload is recognized as a duplicate", () => {
    expect(buildPdf(pages)).toEqual(buildPdf(pages));
    expect(buildPdf(pages)).not.toEqual(buildPdf([...pages].reverse()));
  });

  it("sizes pages with A4's long side and the image's aspect ratio", () => {
    expect(pageSize(2480, 3508)).toEqual({ width: 595.18, height: 841.89 });
    expect(pageSize(3508, 2480)).toEqual({ width: 841.89, height: 595.18 });
  });

  it("refuses an empty scan", () => {
    expect(() => buildPdf([])).toThrow();
  });
});
