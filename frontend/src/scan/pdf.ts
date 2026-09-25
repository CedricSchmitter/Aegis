/**
 * A minimal PDF writer for scanned pages: one JPEG image per page, embedded unchanged (DCTDecode). The output is
 * deterministic (no dates, no random ids), so the same pages always give the same bytes. That lets the server's
 * checksum duplicate detection recognize a retried or repeated upload of the same scan.
 */
export interface PdfPage {
  /** Baseline or progressive JPEG with three colour components (as canvas encoders produce). */
  jpeg: Uint8Array;
  width: number;
  height: number;
}

/** Page size of the long side in points: A4 (297 mm). Pages keep the aspect ratio of their image. */
const LONG_SIDE_POINTS = 841.89;

const encoder = new TextEncoder();

/** Page size in points: the long side is A4's, so a straightened A4 sheet at any resolution prints at 100 %. */
export function pageSize(width: number, height: number): { width: number; height: number } {
  const scale = LONG_SIDE_POINTS / Math.max(width, height);
  return { width: round(width * scale), height: round(height * scale) };
}

const round = (value: number) => Math.round(value * 100) / 100;

export function buildPdf(pages: readonly PdfPage[]): Uint8Array<ArrayBuffer> {
  if (pages.length === 0) throw new Error("a PDF needs at least one page");
  const chunks: Uint8Array[] = [];
  const offsets: number[] = [];
  let length = 0;
  const write = (part: string | Uint8Array) => {
    const bytes = typeof part === "string" ? encoder.encode(part) : part;
    chunks.push(bytes);
    length += bytes.length;
  };
  const object = (id: number, body: string, stream?: Uint8Array) => {
    offsets[id] = length;
    write(`${id} 0 obj\n${body}\n`);
    if (stream) {
      write("stream\n");
      write(stream);
      write("\nendstream\n");
    }
    write("endobj\n");
  };

  // Object numbers: 1 catalog, 2 page tree, then per page: page, image, content stream.
  const pageId = (index: number) => 3 + index * 3;
  // The second line is the customary comment of bytes above 127 that marks the file as binary.
  write(Uint8Array.of(...encoder.encode("%PDF-1.4\n%"), 0xe2, 0xe3, 0xcf, 0xd3, 0x0a));

  object(1, "<< /Type /Catalog /Pages 2 0 R >>");
  const kids = pages.map((_, index) => `${pageId(index)} 0 R`).join(" ");
  object(2, `<< /Type /Pages /Kids [${kids}] /Count ${pages.length} >>`);
  pages.forEach((page, index) => {
    const id = pageId(index);
    const size = pageSize(page.width, page.height);
    object(
      id,
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${size.width} ${size.height}] ` +
        `/Resources << /XObject << /Im0 ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>`,
    );
    object(
      id + 1,
      `<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} /ColorSpace /DeviceRGB ` +
        `/BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.length} >>`,
      page.jpeg,
    );
    const content = encoder.encode(`q ${size.width} 0 0 ${size.height} 0 0 cm /Im0 Do Q`);
    object(id + 2, `<< /Length ${content.length} >>`, content);
  });

  const objectCount = 3 + pages.length * 3;
  const xref = length;
  let table = `xref\n0 ${objectCount}\n0000000000 65535 f \n`;
  for (let id = 1; id < objectCount; id++) {
    table += `${String(offsets[id]).padStart(10, "0")} 00000 n \n`;
  }
  write(table);
  write(`trailer\n<< /Size ${objectCount} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);

  const pdf = new Uint8Array(length);
  let position = 0;
  for (const chunk of chunks) {
    pdf.set(chunk, position);
    position += chunk.length;
  }
  return pdf;
}
