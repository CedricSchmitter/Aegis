import { useTranslation } from "react-i18next";
import type { DocumentListResponse } from "../../api/types";

export function SearchCoverageNotice({ result }: { result: DocumentListResponse }) {
  const { t } = useTranslation();
  if (result.contentSearch === "READY" && result.countAccuracy === "LOWER_BOUND" && !result.notContentIndexed)
    return <p className="alert-warn" role="alert">{t("documents.unindexedFacetCounts")}</p>;
  if (result.contentSearch !== "LIMITED" && result.contentSearch !== "UNAVAILABLE") return null;
  const bounded = ["REQUEST_LIMIT", "TIME_LIMIT", "HIT_LIMIT"].includes(result.searchLimitation ?? "");
  const key = result.contentSearch === "UNAVAILABLE" ? "contentSearchUnavailable"
    : bounded ? "contentSearchLimited" : "contentSearchInterrupted";
  return <p className="alert-warn" role="alert">{t(`documents.${key}`)}</p>;
}
