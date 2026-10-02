import type { ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { PendingShares } from "./PendingShares";
import { Wordmark } from "./ui";
import { AvatarMenu } from "./shell/AvatarMenu";
import { ContextPanelColumn, ContextPanelProvider, useContextPanelSlot } from "./shell/ContextPanel";
import { HeaderSearch } from "./shell/HeaderSearch";
import { Icon } from "./shell/icons";
import { BottomNav, Sidebar } from "./shell/Navigation";
import { ShellDataProvider, useShellData } from "./shell/ShellData";
import { StatusBar } from "./shell/StatusBar";
import { StatusIndicator } from "./shell/StatusIndicator";

/** The inbox in the desktop header, with the count of open cards and unread messages. */
function HeaderInbox() {
  const { t } = useTranslation();
  const { pending } = useShellData();
  return (
    <Link
      to="/inbox"
      className="relative grid h-11 w-11 flex-none place-items-center text-text-2 hover:text-text-1"
      aria-label={pending > 0 ? `${t("nav.inbox")}: ${t("nav.pendingCount", { count: pending })}` : t("nav.inbox")}
      data-testid="header-inbox"
    >
      <Icon name="bell" className="h-[22px] w-[22px]" />
      {pending > 0 && (
        <span className="absolute top-1 right-0 grid h-[18px] min-w-[18px] place-items-center bg-action px-1 font-mono text-[11px] text-void" aria-hidden="true">
          {pending}
        </span>
      )}
    </Link>
  );
}

/**
 * The application shell (ADR-0019). On a phone: a header with the wordmark and the avatar, the page, and a
 * tab bar with the five areas. From 1024 px: a header with search, status and inbox, a sidebar, the work area, a
 * context panel from 1280 px while a page fills it, and a status bar.
 */
export function Layout({ children }: { children: ReactNode }) {
  return (
    <ShellDataProvider>
      <Shell>{children}</Shell>
    </ShellDataProvider>
  );
}

function Shell({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { slot, filled, setTarget } = useContextPanelSlot();

  return (
    <ContextPanelProvider slot={slot}>
      <div className="flex min-h-dvh flex-col">
        <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:bg-surface focus:p-2">
          {t("app.skipToContent")}
        </a>
        <header className="sticky top-0 z-40 flex h-14 flex-none items-center gap-2.5 border-b border-line bg-surface px-3.5 lg:gap-4.5 lg:px-4">
          <Link to="/today" className="flex min-h-11 items-center lg:w-[200px]" aria-label={t("shell.home")}>
            <Wordmark />
          </Link>
          <div className="hidden flex-1 lg:block">
            <HeaderSearch />
          </div>
          <div className="ml-auto flex items-center gap-2">
            <div className="hidden lg:block">
              <StatusIndicator />
            </div>
            <div className="hidden lg:block">
              <HeaderInbox />
            </div>
            <AvatarMenu />
          </div>
        </header>
        <div
          className="flex-1 lg:grid lg:grid-cols-[232px_minmax(0,1fr)] xl:data-[panel=true]:grid-cols-[232px_minmax(0,1fr)_340px]"
          data-panel={filled}
        >
          <Sidebar />
          <main id="main" className="min-w-0 px-4 pt-4 pb-24 lg:px-7 lg:pt-6 lg:pb-14">
            <div className="mx-auto w-full max-w-5xl">
              <div className="mb-3 flex flex-col gap-2 empty:hidden">
                <PendingShares />
              </div>
              {children}
            </div>
          </main>
          <ContextPanelColumn filled={filled} setTarget={setTarget} />
        </div>
        <StatusBar />
        <BottomNav />
      </div>
    </ContextPanelProvider>
  );
}
