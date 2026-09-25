import { useEffect, useState, type ReactNode } from "react";
import { NavLink, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { INBOX_CHANGED } from "../lib/inbox";
import { LanguageSwitch } from "./LanguageSwitch";
import { PendingShares } from "./PendingShares";

const NAV = [
  { to: "/today", key: "nav.today" },
  { to: "/documents", key: "nav.documents" },
  { to: "/contracts", key: "nav.contracts" },
  { to: "/tasks", key: "nav.tasks" },
  { to: "/inbox", key: "nav.inbox" },
  { to: "/status", key: "nav.status" },
] as const;

export function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { member, logout } = useSession();
  const navigate = useNavigate();
  // Open review cards plus unread messages (issue #83); notices announcing a card are shown as the card.
  const [pending, setPending] = useState(0);
  const [version, setVersion] = useState<string>("");

  useEffect(() => {
    let active = true;
    const load = async () => {
      const [cards, messages] = await Promise.all([
        api.GET("/api/reviews/open-count"),
        api.GET("/api/notices/unread-count"),
      ]);
      if (active && cards.data && messages.data) setPending(cards.data.open + messages.data.unread);
    };
    void load();
    const timer = window.setInterval(load, 30000);
    window.addEventListener(INBOX_CHANGED, load);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener(INBOX_CHANGED, load);
    };
  }, []);

  useEffect(() => {
    void api.GET("/api/system/version").then((r) => setVersion(r.data?.commit ?? ""));
  }, []);

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `relative rounded-lg px-3 py-2 text-sm font-medium ${
      isActive
        ? "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100"
        : "text-stone-700 hover:bg-stone-100 dark:text-stone-300 dark:hover:bg-stone-800"
    }`;

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:bg-white focus:p-2">
        {t("app.skipToContent")}
      </a>
      <header className="border-b border-stone-200 bg-white dark:border-stone-800 dark:bg-stone-900">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-2">
          <div className="flex items-center gap-3">
            <span className="flex items-center gap-2 text-lg font-semibold tracking-tight">
              <img src="/icons/favicon.svg" alt="" width={28} height={28} className="h-7 w-7" />
              {t("app.name")}
            </span>
            <nav aria-label={t("nav.menu")} className="hidden gap-1 md:flex">
              {NAV.map((item) => (
                <NavLink key={item.to} to={item.to} className={linkClass}>
                  {t(item.key)}
                  {item.to === "/inbox" && pending > 0 && (
                    <span className="ml-1 rounded-full bg-red-600 px-1.5 text-xs text-white" aria-label={t("nav.pendingCount", { count: pending })} data-testid="inbox-badge">
                      {pending}
                    </span>
                  )}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-2">
            <LanguageSwitch />
            <NavLink to="/settings" className={linkClass} aria-label={t("nav.settings")}>
              {member?.displayName}
            </NavLink>
            <button
              type="button"
              className="btn-ghost"
              onClick={async () => {
                // Leave the protected route first so the login page does not remember it as the return target.
                navigate("/login");
                await logout();
              }}
            >
              {t("nav.logout")}
            </button>
          </div>
        </div>
      </header>
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-2 px-4 pt-3">
        <p className="alert-warn" role="note">
          {t(window.location.protocol === "https:" ? "app.pilotNotice" : "app.previewNotice")}
        </p>
        <PendingShares />
      </div>
      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-4 pb-24 md:pb-8">
        {children}
      </main>
      <footer className="mx-auto w-full max-w-5xl px-4 pb-20 text-xs text-stone-500 md:pb-4">
        {version && t("app.version", { commit: version.substring(0, 12) })}
      </footer>
      <nav
        aria-label={t("nav.menu")}
        className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-6 border-t border-stone-200 bg-white pb-[env(safe-area-inset-bottom)] dark:border-stone-800 dark:bg-stone-900 md:hidden"
      >
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              `relative flex min-h-14 flex-col items-center justify-center px-1 text-center text-xs leading-tight ${
                isActive ? "text-sky-800 dark:text-sky-300" : "text-stone-600 dark:text-stone-400"
              }`
            }
          >
            {t(item.key)}
            {item.to === "/inbox" && pending > 0 && (
              <span className="absolute right-3 top-2 rounded-full bg-red-600 px-1.5 text-[10px] text-white" aria-label={t("nav.pendingCount", { count: pending })} data-testid="inbox-badge">
                {pending}
              </span>
            )}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
