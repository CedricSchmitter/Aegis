import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { PageEditDto } from "../../api/pageEdits";

interface Props {
  /** Null right after a reload, until the first answer about the accepted operation arrives. */
  operation: PageEditDto | null;
  onStartOver: () => void;
}

/** The accepted operation: a note while the new documents are created, then links to them. */
export function PageEditResult({ operation, onStartOver }: Props) {
  const { t } = useTranslation();
  const done = operation?.state === "COMPLETED";
  const results = operation?.results ?? [];

  return (
    <section className="card flex flex-col gap-3" data-testid="page-edit-result" data-state={operation?.state ?? "REQUESTED"}>
      <h2 className="text-lg font-semibold" role="status">
        {done ? t("pageEdit.result.done", { count: results.length }) : t("pageEdit.result.running")}
      </h2>
      {!done && <p className="text-sm text-text-3">{t("pageEdit.result.runningHint")}</p>}
      {results.length > 0 && (
        <ul className="flex flex-col gap-2">
          {results.map((result) => (
            <li key={result.documentId} className="flex flex-col" data-testid="page-edit-result-document">
              <Link to={`/documents/${result.documentId}`} className="font-medium break-words text-data hover:underline">
                {result.title}
              </Link>
              {result.existing && (
                <span className="text-xs text-text-3">
                  {t(result.trashed ? "pageEdit.result.existingTrashed" : "pageEdit.result.existing")}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {done && (
        <>
          <p className="text-sm text-text-3">{t("pageEdit.result.sourcesKept")}</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className="btn-primary" onClick={onStartOver} data-testid="page-edit-start-over">
              {t("pageEdit.result.startOver")}
            </button>
            <Link to="/documents" className="btn-secondary">
              {t("pageEdit.result.toDocuments")}
            </Link>
          </div>
        </>
      )}
    </section>
  );
}
