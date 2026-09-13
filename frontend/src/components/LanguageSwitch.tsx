import { useTranslation } from "react-i18next";
import { useSession } from "../auth/SessionContext";
import { applyLocale, type ProductLocale } from "../i18n";

/** Toggles German/English. Persists per member when logged in, otherwise only in this browser. */
export function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  const { member, changeLocale } = useSession();
  const current: ProductLocale = i18n.language.startsWith("en") ? "EN" : "DE";
  const next: ProductLocale = current === "DE" ? "EN" : "DE";
  const nextLabel = t(next === "EN" ? "language.en" : "language.de");

  const onClick = async () => {
    if (member) {
      await changeLocale(next);
    } else {
      applyLocale(next);
    }
  };

  return (
    <button type="button" className="btn-secondary" onClick={onClick} aria-label={t("language.switchTo", { language: nextLabel })} data-testid="language-switch">
      {current === "DE" ? "EN" : "DE"}
    </button>
  );
}
