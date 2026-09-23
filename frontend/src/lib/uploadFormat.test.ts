import { describe, expect, it } from "vitest";
import { detectFormat, detectFormatFromHead, isImageContentType, uploadName } from "./uploadFormat";

const bytes = (...values: (number | string)[]) =>
  Uint8Array.from(values.flatMap((value) => (typeof value === "string" ? [...value].map((c) => c.charCodeAt(0)) : [value])));

const ftyp = (brand: string) => bytes(0, 0, 0, 24, "ftyp", brand, 0, 0, 0, 0, "mif1", "heic");

describe("upload formats", () => {
  it("recognizes PDFs and photos by their first bytes like the server", () => {
    expect(detectFormatFromHead(bytes("%PDF-1.7"))).toBe("pdf");
    expect(detectFormatFromHead(bytes(0xff, 0xd8, 0xff, 0xe1, 0, 0x10, "Exif"))).toBe("jpeg");
    expect(detectFormatFromHead(bytes(0x89, "PNG", 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, "IHDR"))).toBe("png");
    expect(detectFormatFromHead(bytes("RIFF", 0x24, 0x10, 0, 0, "WEBP", "VP8 "))).toBe("webp");
    expect(detectFormatFromHead(bytes("RIFF", 0x24, 0x10, 0, 0, "WEBP", "VP8X"))).toBe("webp");
    expect(detectFormatFromHead(ftyp("heic"))).toBe("heic");
    expect(detectFormatFromHead(ftyp("heix"))).toBe("heic");
  });

  it("refuses other content, damaged headers and containers the archive cannot read", () => {
    expect(detectFormatFromHead(bytes("GIF89a"))).toBeNull();
    expect(detectFormatFromHead(bytes("not a pdf"))).toBeNull();
    expect(detectFormatFromHead(bytes(0x89, "PNG", 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, "IHDX"))).toBeNull();
    expect(detectFormatFromHead(bytes("RIFF", 0, 0, 0, 0, "WEBP", "JUNK"))).toBeNull();
    expect(detectFormatFromHead(ftyp("mif1"))).toBeNull();
    expect(detectFormatFromHead(ftyp("avif"))).toBeNull();
    expect(detectFormatFromHead(bytes(0, 0, 0, 8, "ftyp", "heic"))).toBeNull();
    expect(detectFormatFromHead(bytes(0xff, 0xd8))).toBeNull();
    expect(detectFormatFromHead(new Uint8Array())).toBeNull();
  });

  it("reads only the head of a blob", async () => {
    expect(await detectFormat(new Blob([bytes(0xff, 0xd8, 0xff, 0xdb), new Uint8Array(4096)]))).toBe("jpeg");
    expect(await detectFormat(new Blob([]))).toBeNull();
  });

  it("names a file for the server without doubling or inventing extensions", () => {
    expect(uploadName("IMG_20260923_101500.jpg", "Geteilt", "jpeg")).toBe("IMG_20260923_101500.jpg");
    expect(uploadName("Rechnung.PDF", "Geteilt", "pdf")).toBe("Rechnung.PDF");
    expect(uploadName("1000012345", "Geteilt", "jpeg")).toBe("1000012345.jpg");
    expect(uploadName("document-1234", "Geteilt", "pdf")).toBe("document-1234.pdf");
    expect(uploadName("  ", "Geteilt 2026-09-23", "heic")).toBe("Geteilt 2026-09-23.heic");
    expect(uploadName("Brief.pdf", "Geteilt", "png")).toBe("Brief.pdf");
  });

  it("knows which stored media types are photos", () => {
    expect(isImageContentType("image/jpeg")).toBe(true);
    expect(isImageContentType("image/heic")).toBe(true);
    expect(isImageContentType("application/pdf")).toBe(false);
    expect(isImageContentType("image/gif")).toBe(false);
  });
});
