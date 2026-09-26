import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { DocumentDto } from "../api/types";
import { formatDateTime } from "../lib/format";
import { lacksText } from "../lib/textRecognition";
import { isImageContentType } from "../lib/uploadFormat";

/**
 * Whether the archive recognized text in the document (ADR-0012), independent of AI. No or almost no text gets a
 * warning with advice for a better capture; a pending or failed check is mentioned quietly.
 */
export function TextRecognitionHint({ document }: { document: DocumentDto }) {
  const { t, i18n } = useTranslation();
  const recognition = document.textRecognition;
  if (lacksText(recognition)) {
    const photo = isImageContentType(document.contentType);
    return (
      <div className="alert-warn flex flex-col gap-2" role="status" data-testid="text-hint" data-state={recognition.state}>
        <p className="font-semibold">{t(`documents.text.badge.${recognition.state}`)}</p>
        <p>
          {recognition.state === "NONE"
            ? t("documents.text.none")
            : t("documents.text.little", { count: recognition.recognizedCharacters ?? 0 })}
        </p>
        <p>{photo ? t("documents.text.advicePhoto") : t("documents.text.adviceScan")}</p>
        {!document.trash && (
          <div>
            <Link className="btn-secondary" to="/documents?upload=1" data-testid="text-hint-upload">
              {photo ? t("documents.text.newPhoto") : t("documents.text.newUpload")}
            </Link>
          </div>
        )}
      </div>
    );
  }
  if (recognition.state === "PENDING") {
    return (
      <p className="text-sm text-text-3" data-testid="text-pending">
        {t("documents.text.pending")}
      </p>
    );
  }
  if (recognition.state === "UNAVAILABLE") {
    return (
      <p className="text-sm text-text-3" data-testid="text-unavailable">
        {t("documents.text.unavailable", { date: formatDateTime(recognition.nextAttemptAt, i18n.language) })}
      </p>
    );
  }
  return null;
}
