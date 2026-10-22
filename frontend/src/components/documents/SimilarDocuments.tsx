import { useEffect, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { DocumentDto } from "../../api/types";
import { formatDate } from "../../lib/format";

/**
 * Documents that are probably the same as this one, from their archive texts (ADR-0028). A hint only: nothing is
 * merged or deleted, so the members decide which copy to keep. Renders nothing while there is none; `refreshKey`
 * asks again when the document changes, for example once its text has been checked.
 */
export function SimilarDocuments({ documentId, refreshKey }: { documentId: string; refreshKey: string }) {
  const { t, i18n } = useTranslation();
  const [similar, setSimilar] = useState<DocumentDto[]>([]);

  useEffect(() => {
    let active = true;
    void api.GET("/api/documents/{id}/similar", { params: { path: { id: documentId } } }).then(
      (result) => {
        if (active) setSimilar(unwrap(result));
      },
      () => {
        if (active) setSimilar([]);
      },
    );
    return () => {
      active = false;
    };
  }, [documentId, refreshKey]);

  if (similar.length === 0) return null;
  return (
    <section className="flex flex-col gap-2.5" aria-labelledby="similar-documents" data-testid="similar-documents">
      <div className="ui-sec min-h-11">
        <h2 id="similar-documents" className="font-normal">
          {t("documents.similar")}
        </h2>
      </div>
      <p className="text-sm text-text-2">{t("documents.similarHint")}</p>
      <ul className="flex flex-col gap-2">
        {similar.map((other) => (
          <li key={other.id} className="card flex flex-wrap items-center justify-between gap-2" data-testid="similar-document">
            <Link to={`/documents/${other.id}`} className="ui-stretch font-medium break-words text-data hover:underline">
              {other.title}
            </Link>
            <span className="font-mono text-sm text-text-2">
              {t("documents.similarUploaded", { date: formatDate(other.uploadedAt, i18n.language) })}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
