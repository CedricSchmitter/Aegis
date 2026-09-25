import { describe, expect, it } from "vitest";
import type { DocumentDto } from "../api/types";
import { searchablePdf } from "./searchablePdf";

const document = (contentType: string, state: string, archivePdfAvailable: boolean) =>
  ({ contentType, processing: { state }, archivePdfAvailable }) as unknown as DocumentDto;

describe("searchablePdf", () => {
  it("promises the searchable PDF of a photo until archival and then offers it", () => {
    expect(searchablePdf(document("image/jpeg", "SUBMITTED", false))).toBe("photoPending");
    expect(searchablePdf(document("image/heic", "ARCHIVED", false))).toBe("photo");
    expect(searchablePdf(document("image/png", "ARCHIVED", true))).toBe("photo");
  });

  it("offers it for a scanned PDF once the archive keeps an archive version", () => {
    expect(searchablePdf(document("application/pdf", "ARCHIVED", true))).toBe("scan");
  });

  it("offers nothing for a born-digital PDF or one whose archive version is not known yet", () => {
    expect(searchablePdf(document("application/pdf", "ARCHIVED", false))).toBe("none");
    expect(searchablePdf(document("application/pdf", "SUBMITTED", false))).toBe("none");
  });
});
