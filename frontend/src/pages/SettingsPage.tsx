import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { LanguageSwitch } from "../components/LanguageSwitch";
import { ErrorBox, InfoBox, PageTitle } from "../components/common";
import { problemMessage } from "../lib/problem";

export function SettingsPage() {
  const { t } = useTranslation();
  const { member } = useSession();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const changePassword = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setMessage(null);
    const result = await api.POST("/api/members/me/password", { body: { currentPassword, newPassword } });
    if (!result.response.ok) {
      const { ApiError } = await import("../api/client");
      setError(problemMessage(t, new ApiError(result.response.status, (result.error ?? {}) as object)));
      return;
    }
    setMessage(t("settings.passwordChanged"));
    setCurrentPassword("");
    setNewPassword("");
  };

  return (
    <>
      <PageTitle>{t("settings.title")}</PageTitle>
      <section className="card mb-4 flex flex-col gap-2">
        <h2 className="font-semibold">{t("settings.account")}</h2>
        {member && <p>{t("settings.signedInAs", { name: member.displayName, username: member.username })}</p>}
        <div className="flex items-center gap-3">
          <span className="label mb-0">{t("language.label")}</span>
          <LanguageSwitch />
          <span className="text-xs text-stone-500">{t("settings.localeSaved")}</span>
        </div>
      </section>
      <form className="card flex flex-col gap-3" onSubmit={changePassword}>
        <h2 className="font-semibold">{t("settings.password")}</h2>
        <label>
          <span className="label">{t("settings.currentPassword")}</span>
          <input type="password" className="field" autoComplete="current-password" required value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} />
        </label>
        <label>
          <span className="label">{t("settings.newPassword")}</span>
          <input type="password" className="field" autoComplete="new-password" required minLength={10} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
          <span className="text-xs text-stone-500">{t("settings.newPasswordHint")}</span>
        </label>
        <ErrorBox message={error} />
        {message && <InfoBox>{message}</InfoBox>}
        <div>
          <button type="submit" className="btn-primary">
            {t("settings.changePassword")}
          </button>
        </div>
      </form>
    </>
  );
}
