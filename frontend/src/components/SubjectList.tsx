import { useTranslation } from "react-i18next";
import type { SubjectDto } from "../api/types";
import { useSession } from "../auth/SessionContext";

export function subjectLabel(subject: SubjectDto, memberName: (id: string | null | undefined) => string | null, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (subject.kind === "MEMBER") return t("subjects.MEMBER", { name: memberName(subject.memberId) ?? t("app.unknownMember") });
  return t(`subjects.${subject.kind}`);
}

export function SubjectList({ subjects }: { subjects: SubjectDto[] }) {
  const { t } = useTranslation();
  const { memberName } = useSession();
  if (subjects.length === 0) return <p className="text-xs text-text-3">{t("subjects.none")}</p>;
  return (
    <ul className="flex flex-wrap gap-1" aria-label={t("documents.subjects")}>
      {subjects.map((subject, index) => (
        <li key={index} className="inline-flex min-h-8 items-center border border-line px-2.5 text-sm text-text-2">
          {subjectLabel(subject, memberName, t)}
        </li>
      ))}
    </ul>
  );
}
