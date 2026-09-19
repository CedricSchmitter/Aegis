import { useTranslation } from "react-i18next";
import { useSession } from "../auth/SessionContext";

/** Who or what a new upload is about; associations are metadata and never restrict visibility. */
export function UploadSubjects({ value, onChange }: { value: string[]; onChange: (value: string[]) => void }) {
  const { t } = useTranslation();
  const { members } = useSession();
  const toggle = (subject: string) =>
    onChange(value.includes(subject) ? value.filter((entry) => entry !== subject) : [...value, subject]);
  const options = [
    ...["HOUSEHOLD", "HOUSE"].map((kind) => ({ subject: kind, label: t(`subjects.${kind}`) })),
    ...members.map((member) => ({ subject: `MEMBER:${member.id}`, label: member.displayName })),
  ];

  return (
    <fieldset>
      <legend className="label">{t("documents.subjects")}</legend>
      <div className="flex flex-wrap gap-3">
        {options.map((option) => (
          <label key={option.subject} className="flex min-h-11 items-center gap-2 text-sm">
            <input type="checkbox" checked={value.includes(option.subject)} onChange={() => toggle(option.subject)} />
            {option.label}
          </label>
        ))}
      </div>
      <p className="mt-1 text-xs text-stone-500">{t("subjects.hint")}</p>
    </fieldset>
  );
}
