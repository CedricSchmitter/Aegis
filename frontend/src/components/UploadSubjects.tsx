import { useTranslation } from "react-i18next";
import { useSession } from "../auth/SessionContext";
import { subjectChoices } from "../lib/labels";

/**
 * Who or what a new upload is about: the house and each person, none ticked at first. Associations are metadata and
 * never restrict visibility; a document about both persons names each of them.
 */
export function UploadSubjects({
  value,
  onChange,
  testIdPrefix = "upload",
}: {
  value: string[];
  onChange: (value: string[]) => void;
  /** Distinguishes several of these on one screen, such as one per new document in the page editor. */
  testIdPrefix?: string;
}) {
  const { t } = useTranslation();
  const { persons, memberName } = useSession();
  const toggle = (subject: string) =>
    onChange(value.includes(subject) ? value.filter((entry) => entry !== subject) : [...value, subject]);
  const options = subjectChoices(persons).map((subject) => ({
    subject,
    label: subject.startsWith("MEMBER:") ? (memberName(subject.substring(7)) ?? subject) : t(`subjects.${subject}`),
  }));

  return (
    <fieldset data-testid={`${testIdPrefix}-subjects`}>
      <legend className="label">{t("documents.subjects")}</legend>
      <div className="flex flex-wrap gap-x-5">
        {options.map((option) => (
          <label key={option.subject} className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
            <input
              type="checkbox"
              className="ui-checkbox"
              data-testid={`${testIdPrefix}-subject-${option.subject}`}
              checked={value.includes(option.subject)}
              onChange={() => toggle(option.subject)}
            />
            {option.label}
          </label>
        ))}
      </div>
      <p className="mt-1 text-xs text-text-3">{t("subjects.hint")}</p>
    </fieldset>
  );
}
