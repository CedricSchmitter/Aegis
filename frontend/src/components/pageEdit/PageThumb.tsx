import { useTranslation } from "react-i18next";
import type { PlanPage, Rotation } from "../../pageEdit/plan";
import type { ScanPage } from "../../scan/draft";
import { useObjectUrl } from "../useObjectUrl";

/** URL of the server-rendered preview of a stored document's page (ADR-0024). */
export function previewUrl(documentId: string, pageIndex: number): string {
  return `/api/documents/${documentId}/pages/${pageIndex}/preview`;
}

/** Turns an image by the page's rotation; a quarter turn swaps which side is limited by the box. */
export function rotationStyle(rotation: Rotation) {
  return { transform: rotation === 0 ? undefined : `rotate(${rotation}deg)` };
}

/** The small picture of a page in the editor: the server preview, or the processed photo of a new page. */
export function PageThumb({ page, scanPage }: { page: PlanPage; scanPage?: ScanPage }) {
  const { t } = useTranslation();
  const scanThumbnail = useObjectUrl(scanPage?.result?.thumbnail);
  const url = page.source === "DOCUMENT" ? previewUrl(page.documentId, page.pageIndex) : scanThumbnail;
  return (
    <span className="flex h-20 w-20 shrink-0 items-center justify-center overflow-hidden bg-raised">
      {page.source === "SCAN" && !scanPage?.result ? (
        <span className="px-1 text-center text-[11px] text-text-3" aria-busy="true">
          {t("scan.processing")}
        </span>
      ) : (
        url && (
          <img
            src={url}
            alt=""
            loading="lazy"
            draggable={false}
            className="max-h-full max-w-full object-contain transition-transform"
            style={rotationStyle(page.rotation)}
          />
        )
      )}
    </span>
  );
}
