import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { LanguageSwitch } from "../components/LanguageSwitch";
import { ErrorBox, InfoBox } from "../components/common";
import { problemMessage } from "../lib/problem";

export function LoginPage() {
  const { t } = useTranslation();
  const { login } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState("");
  const from = (location.state as { from?: string } | null)?.from;

  useEffect(() => {
    void api.GET("/api/system/version").then((r) => setVersion(r.data?.commit ?? ""));
  }, []);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      navigate(from && from !== "/login" ? from : "/today", { replace: true });
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-4 py-8">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-semibold">{t("app.name")}</h1>
          <p className="text-stone-600 dark:text-stone-400">{t("app.tagline")}</p>
        </div>
        <LanguageSwitch />
      </div>
      <p className="alert-warn mb-4" role="note">
        {t(window.location.protocol === "https:" ? "app.pilotNotice" : "app.previewNotice")}
      </p>
      <form className="card flex flex-col gap-4" onSubmit={onSubmit} aria-labelledby="login-title">
        <h2 id="login-title" className="text-xl font-semibold">
          {t("login.title")}
        </h2>
        <p className="text-sm text-stone-600 dark:text-stone-400">{t("login.intro")}</p>
        {from?.startsWith("/share") && <InfoBox>{t("share.loginFirst")}</InfoBox>}
        <div>
          <label className="label" htmlFor="username">
            {t("login.username")}
          </label>
          <input
            id="username"
            name="username"
            className="field"
            autoComplete="username"
            autoCapitalize="none"
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div>
          <label className="label" htmlFor="password">
            {t("login.password")}
          </label>
          <input
            id="password"
            name="password"
            type="password"
            className="field"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <ErrorBox message={error} />
        <button type="submit" className="btn-primary" disabled={busy}>
          {busy ? t("login.submitting") : t("login.submit")}
        </button>
      </form>
      <p className="mt-4 text-center text-xs text-stone-500">
        {version && t("app.version", { commit: version.substring(0, 12) })}
      </p>
    </main>
  );
}
