import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { LanguageSwitch } from "../components/LanguageSwitch";
import { ErrorBox, InfoBox } from "../components/common";
import { Wordmark } from "../components/ui";
import { problemMessage } from "../lib/problem";

/** The login in the design system (issue #27): the wordmark, one framed form and the running version. */
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
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-5 px-4 py-8">
      <div className="flex items-center justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="m-0">
            <Wordmark />
          </h1>
          <p className="ui-eyebrow">{t("app.tagline")}</p>
        </div>
        <LanguageSwitch />
      </div>
      <form className="card flex flex-col gap-4 p-5" onSubmit={onSubmit} aria-labelledby="login-title">
        <h2 id="login-title" className="text-xl font-bold tracking-[0.06em] uppercase">
          {t("login.title")}
        </h2>
        <p className="text-sm leading-relaxed text-text-3">{t("login.intro")}</p>
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
        <button type="submit" className="btn-primary w-full" disabled={busy}>
          {busy ? t("login.submitting") : t("login.submit")}
        </button>
      </form>
      <p className="text-center font-mono text-xs text-text-4">{version && t("app.version", { commit: version.substring(0, 12) })}</p>
    </main>
  );
}
