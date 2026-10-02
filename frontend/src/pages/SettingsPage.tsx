import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { ApiError, api } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { LanguageSwitch } from "../components/LanguageSwitch";
import { BigTitle, ErrorBox, InfoBox } from "../components/common";
import { ContextPanel, usePanelShown } from "../components/shell/ContextPanel";
import { Icon } from "../components/shell/icons";
import { useShellData } from "../components/shell/ShellData";
import { StatusPanel } from "../components/status/StatusOverview";
import { SegmentedControl, Switch } from "../components/ui";
import { PALETTES, PALETTE_SWATCHES, type Appearance } from "../lib/appearance";
import { problemMessage } from "../lib/problem";
import type { StatusTone } from "../lib/shellStatus";

const PALETTE_NAMES = { streetkid: "Streetkid", corpo: "Corpo", nomad: "Nomad" } as const;

const STATUS_WORD: Record<StatusTone, { key: string; tone: string }> = {
  ok: { key: "status.checkState.OK", tone: "data" },
  problem: { key: "status.checkState.PROBLEM", tone: "danger" },
  unknown: { key: "settings.statusUnknown", tone: "neutral" },
};

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

/** Status, trash and the folder and label management, each one row that leads there. */
function Places() {
  const { t } = useTranslation();
  const { status } = useShellData();
  const word = STATUS_WORD[status.tone];
  const row = (to: string, title: string, hint: string, testId: string, state?: ReactNode) => (
    <Link to={to} className="ui-row pr-1" data-testid={testId}>
      <span className="ui-row-text">
        <b>{title}</b>
        <span>{hint}</span>
      </span>
      {state}
      <Icon name="chevron" className="h-[18px] w-[18px] flex-none text-text-4" />
    </Link>
  );
  return (
    <nav aria-label={t("settings.places")} className="border-t border-line">
      {row(
        "/status",
        t("status.title"),
        t("status.overview"),
        "settings-status",
        <span className="ui-state" data-tone={word.tone}>
          {t(word.key)}
        </span>,
      )}
      {row("/trash", t("trash.title"), t("settings.trashHint"), "settings-trash")}
      {row("/documents/folders", t("documents.manageFolders"), t("settings.foldersHint"), "settings-folders")}
      {row("/documents/labels", t("documents.manageLabels"), t("settings.labelsHint"), "settings-labels")}
    </nav>
  );
}

/** Palette and effects, stored for the member like the language (ADR-0019). */
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
    <section aria-labelledby="appearance" className="flex flex-col gap-3" data-testid="appearance-settings">
      <h2 id="appearance" className="ui-sec">
        {t("settings.appearance.title")}
      </h2>
      <div className="flex flex-col gap-2">
        <p className="text-[15px] font-semibold">{t("settings.appearance.palette")}</p>
        <SegmentedControl
          label={t("settings.appearance.palette")}
          value={appearance.palette}
          onChange={(palette) => change({ ...appearance, palette })}
          options={PALETTES.map((palette) => ({
            value: palette,
            label: (
              <span className="flex flex-col items-center gap-1.5 py-2 leading-tight">
                <span>{PALETTE_NAMES[palette]}</span>
                <span className="flex gap-1" aria-hidden="true">
                  {PALETTE_SWATCHES[palette].map((colour) => (
                    <i key={colour} className="ui-swatch" style={{ background: colour }} />
                  ))}
                </span>
                <span className="sr-only">{t(`settings.appearance.${palette}`)}</span>
              </span>
            ),
          }))}
        />
        <p className="text-[13px] text-text-3">{t("settings.appearance.savedNote")}</p>
      </div>
      <Switch checked={appearance.effects} onChange={(effects) => change({ ...appearance, effects })} description={t("settings.appearance.effectsHint")}>
        {t("settings.appearance.effects")}
      </Switch>
      {reducedMotion && appearance.effects && (
        <p className="text-sm text-text-3" data-testid="reduced-motion-note">
          {t("settings.appearance.reducedMotion")}
        </p>
      )}
      <ErrorBox message={error} />
    </section>
  );
}

function PasswordForm() {
  const { t } = useTranslation();
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
      setError(problemMessage(t, new ApiError(result.response.status, (result.error ?? {}) as object)));
      return;
    }
    setMessage(t("settings.passwordChanged"));
    setCurrentPassword("");
    setNewPassword("");
  };

  return (
    <details className="ui-details">
      <summary>{t("settings.password")}</summary>
      <form className="flex flex-col gap-3 pt-1 pb-4" onSubmit={changePassword}>
        <label>
          <span className="label">{t("settings.currentPassword")}</span>
          <input type="password" className="field" autoComplete="current-password" required value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} />
        </label>
        <label>
          <span className="label">{t("settings.newPassword")}</span>
          <input type="password" className="field" autoComplete="new-password" required minLength={10} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
          <span className="mt-1 block text-xs text-text-3">{t("settings.newPasswordHint")}</span>
        </label>
        <ErrorBox message={error} />
        {message && <InfoBox>{message}</InfoBox>}
        <div>
          <button type="submit" className="btn-secondary">
            {t("settings.changePassword")}
          </button>
        </div>
      </form>
    </details>
  );
}

/**
 * Settings: where Status, Trash and the management pages are, then the member's own look, language,
 * password and logout. From 1280 px the context panel shows the status overview.
 */
export function SettingsPage() {
  const { t } = useTranslation();
  const { member, logout } = useSession();
  const navigate = useNavigate();
  const panelShown = usePanelShown();

  return (
    <div className="flex max-w-2xl flex-col gap-7">
      <BigTitle
        eyebrow={
          <>
            {t("settings.signedInLabel")} <b>{member?.displayName}</b>
          </>
        }
      >
        {t("settings.title")}
      </BigTitle>
      <Places />
      <AppearanceSettings />
      <section aria-labelledby="account" className="flex flex-col">
        <h2 id="account" className="ui-sec mb-2">
          {t("settings.account")}
        </h2>
        <div className="border-t border-line">
          {member && (
            <div className="ui-row">
              <span className="ui-row-text">
                <b>{t("settings.username")}</b>
                <span className="font-mono">{member.username}</span>
              </span>
            </div>
          )}
          <div className="ui-row">
            <span className="ui-row-text">
              <b>{t("language.label")}</b>
              <span>{t("settings.localeSaved")}</span>
            </span>
            <LanguageSwitch />
          </div>
        </div>
        <PasswordForm />
        <button
          type="button"
          className="ui-row border-t border-line"
          data-testid="settings-logout"
          onClick={async () => {
            // Leave the protected route first so the login page does not remember it as the return target.
            navigate("/login");
            await logout();
          }}
        >
          <Icon name="logout" className="h-[18px] w-[18px] flex-none text-text-3" />
          <span className="ui-row-text">
            <b>{t("nav.logout")}</b>
          </span>
        </button>
      </section>
      {panelShown && (
        <ContextPanel>
          <StatusPanel />
        </ContextPanel>
      )}
    </div>
  );
}
