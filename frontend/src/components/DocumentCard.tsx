import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { DocumentDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { formatDate } from "../lib/format";
import { Badge } from "./common";
import { SubjectList } from "./SubjectList";

export function DocumentCard({ document }: { document: DocumentDto }) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const state = document.processing.state;
  return (
    <article className="card flex flex-col gap-1" data-testid="document-card" data-document-id={document.id}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="font-semibold">
          <Link to={`/documents/${document.id}`} className="hover:underline">
            {document.title}
          </Link>
        </h3>
        <Badge state={state} label={t(`documents.stateShort.${state}`)} />
      </div>
      <p className="text-sm text-stone-600 dark:text-stone-400">
        {t("documents.uploadedBy", { name: memberName(document.uploadedBy) ?? t("app.unknownMember"), date: formatDate(document.uploadedAt, i18n.language) })}
      </p>
      <SubjectList subjects={document.subjects} />
    </article>
  );
}
