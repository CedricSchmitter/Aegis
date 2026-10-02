import { useEffect, useState } from "react";
import { Link } from "react-router";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { components } from "../../api/schema";
import { useSession } from "../../auth/SessionContext";
import { formatDate } from "../../lib/format";

type DerivationsDto = components["schemas"]["DerivationsDto"];
type SourceRangeDto = components["schemas"]["SourceRangeDto"];
type PageSpanDto = components["schemas"]["PageSpanDto"];

/** "Seite 3" or "Seiten 1–3, 5" for one-based inclusive spans; `leading` for the start of a line. */
export function pageList(spans: readonly PageSpanDto[], t: TFunction, leading = false): string {
  const single = spans.length === 1 && spans[0].firstPage === spans[0].lastPage;
  if (single) return t(leading ? "pageEdit.pages.singleLeading" : "pageEdit.pages.single", { page: spans[0].firstPage });
  const list = spans
    .map((span) => (span.firstPage === span.lastPage ? `${span.firstPage}` : `${span.firstPage}–${span.lastPage}`))
    .join(", ");
  return t(leading ? "pageEdit.pages.manyLeading" : "pageEdit.pages.many", { list });
}

const sourceSpan = (range: SourceRangeDto): PageSpanDto => ({
  firstPage: range.firstSourcePage,
  lastPage: range.lastSourcePage,
});

/**
 * Where this document's pages came from ("Erstellt aus Seiten 1–3 von …") and which documents were created from
 * its pages ("Daraus erstellt"), from the page editor's records (ADR-0024). Renders nothing for a document without
 * either; a trashed or deleted source says so.
 */
export function Derivations({ documentId }: { documentId: string }) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [data, setData] = useState<DerivationsDto | null>(null);

  useEffect(() => {
    let active = true;
    void api.GET("/api/documents/{id}/derivations", { params: { path: { id: documentId } } }).then(
      (result) => {
        if (active) setData(unwrap(result));
      },
      () => {
        if (active) setData(null);
      },
    );
    return () => {
      active = false;
    };
  }, [documentId]);

  if (!data || (!data.origin && data.derived.length === 0)) return null;
  const origin = data.origin;
  const resultPagesShown = (origin?.ranges.length ?? 0) > 1;

  return (
    <div className="flex flex-col gap-3" data-testid="derivations">
      {origin && (
        <div className="flex flex-col gap-1.5" data-testid="derivation-origin">
          <ul className="flex flex-col gap-1.5">
            {origin.ranges.map((range) => (
              <li key={`${range.firstPage}`} className="text-sm" data-testid="derivation-range" data-source-state={range.sourceState ?? "SCAN"}>
                {resultPagesShown && (
                  <span className="font-mono text-xs text-text-3">
                    {pageList([{ firstPage: range.firstPage, lastPage: range.lastPage }], t, true)} ·{" "}
                  </span>
                )}
                <OriginRange range={range} />
              </li>
            ))}
          </ul>
          <p className="text-xs text-text-3">
            {t("pageEdit.derivations.createdBy", {
              name: memberName(origin.createdBy) ?? "",
              date: formatDate(origin.createdAt, i18n.language),
            })}
          </p>
        </div>
      )}
      {data.derived.length > 0 && (
        <div className="flex flex-col gap-1.5" data-testid="derivation-derived">
          <h3 className="text-sm font-medium">{t("pageEdit.derivations.derived")}</h3>
          <ul className="flex flex-col gap-1.5">
            {data.derived.map((derived) => (
              <li key={derived.documentId} className="flex flex-col text-sm" data-testid="derived-document">
                <Link to={`/documents/${derived.documentId}`} className="font-medium break-words text-data hover:underline">
                  {derived.title}
                </Link>
                <span className="text-xs text-text-3">
                  {t("pageEdit.derivations.takenPages", { pages: pageList(derived.sourcePages, t) })}
                  {derived.trashed && ` · ${t("pageEdit.derivations.trashed")}`}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function OriginRange({ range }: { range: SourceRangeDto }) {
  const { t } = useTranslation();
  if (range.sourceKind === "SCAN") return <>{t("pageEdit.derivations.fromScan")}</>;
  const pages = pageList([sourceSpan(range)], t);
  if (range.sourceState === "DELETED" || !range.sourceDocumentId) {
    return <>{t("pageEdit.derivations.fromDeleted", { pages, title: range.sourceTitle ?? t("pageEdit.derivations.unknownTitle") })}</>;
  }
  return (
    <>
      {t("pageEdit.derivations.from", { pages })}{" "}
      <Link to={`/documents/${range.sourceDocumentId}`} className="break-words text-data hover:underline">
        {range.sourceTitle ?? t("pageEdit.derivations.unknownTitle")}
      </Link>
      {range.sourceState === "TRASHED" && <span className="text-text-3"> · {t("pageEdit.derivations.trashed")}</span>}
    </>
  );
}
