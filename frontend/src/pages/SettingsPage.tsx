import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { LanguageSwitch } from "../components/LanguageSwitch";
import { ErrorBox, InfoBox, PageTitle } from "../components/common";
import { SegmentedControl, Switch } from "../components/ui";
import { PALETTES, type Appearance } from "../lib/appearance";
import { problemMessage } from "../lib/problem";

const PALETTE_NAMES = { streetkid: "Streetkid", corpo: "Corpo", nomad: "Nomad" } as const;

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () => typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReduced(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return reduced;
}

/** Palette and effects, stored for the member like the language (issue #27, ADR-0019). */
function AppearanceSettings() {
  const { t } = useTranslation();
  const { appearance, changeAppearance } = useSession();
  const reducedMotion = usePrefersReducedMotion();
  const [error, setError] = useState<string | null>(null);
  const change = (next: Appearance) => {
    setError(null);
    changeAppearance(next).catch(() => setError(t("settings.appearance.saveFailed")));
  };
  return (
    <section className="card mb-4 flex flex-col gap-3" data-testid="appearance-settings">
      <h2 className="font-semibold">{t("settings.appearance.title")}</h2>
      <SegmentedControl
        label={t("settings.appearance.palette")}
        value={appearance.palette}
        onChange={(palette) => change({ ...appearance, palette })}
        options={PALETTES.map((palette) => ({
          value: palette,
          label: (
            <span className="flex flex-col items-center py-1 leading-tight">
              <span>{PALETTE_NAMES[palette]}</span>
              <span className="text-xs text-text-4">{t(`settings.appearance.${palette}`)}</span>
            </span>
          ),
        }))}
      />
      <Switch checked={appearance.effects} onChange={(effects) => change({ ...appearance, effects })} description={t("settings.appearance.effectsHint")}>
        {t("settings.appearance.effects")}
      </Switch>
      {reducedMotion && appearance.effects && (
        <p className="text-sm text-text-3" data-testid="reduced-motion-note">
          {t("settings.appearance.reducedMotion")}
        </p>
      )}
      <ErrorBox message={error} />
      <p className="text-xs text-text-3">{t("settings.appearance.savedNote")}</p>
    </section>
  );
}

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
      <AppearanceSettings />
      <section className="card mb-4 flex flex-col gap-2">
        <h2 className="font-semibold">{t("trash.title")}</h2>
        <p className="text-sm text-stone-600 dark:text-stone-400">{t("trash.intro")}</p>
        <div>
          <Link className="btn-secondary" to="/trash">
            {t("trash.open")}
          </Link>
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
