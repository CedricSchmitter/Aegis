import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useLocation } from "react-router";
import { api } from "../../api/client";
import type { CategoryListResponse, HealthCheck } from "../../api/types";
import { INBOX_CHANGED } from "../../lib/inbox";
import { summarizeStatus, type StatusSummary } from "../../lib/shellStatus";

/** The width from which the desktop shell (sidebar, header search, status bar) replaces the phone shell. */
export const DESKTOP_QUERY = "(min-width: 1024px)";

interface ShellData {
  status: StatusSummary;
  /** Open review cards plus unread messages (issue #83). */
  pending: number;
  /** Folders with their document counts for the desktop sidebar; null on a phone or until loaded. */
  catalog: CategoryListResponse | null;
  version: string;
  desktop: boolean;
}

const ShellDataContext = createContext<ShellData | null>(null);

// A failed shell request keeps the last known value; the page itself reports its own failures.
const ignore = () => undefined;

export function useDesktop(): boolean {
  const [desktop, setDesktop] = useState(() => typeof window.matchMedia === "function" && window.matchMedia(DESKTOP_QUERY).matches);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(DESKTOP_QUERY);
    const update = () => setDesktop(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return desktop;
}

/** Runs `load` now, on an interval while the tab is visible, and again when the tab becomes visible. */
function usePolling(load: () => void, intervalMs: number) {
  useEffect(() => {
    load();
    const timer = window.setInterval(() => {
      if (document.visibilityState !== "hidden") load();
    }, intervalMs);
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [load, intervalMs]);
}

/**
 * Loads what the shell shows around every page: the status overview (ADR-0018) for the header, avatar, status bar
 * and Today's status line, the inbox count, the folders of the sidebar and the running version. One request per
 * source serves every place that shows it.
 */
export function ShellDataProvider({ children }: { children: ReactNode }) {
  const desktop = useDesktop();
  const { pathname } = useLocation();
  const [checks, setChecks] = useState<HealthCheck[] | null>(null);
  // Each count is kept on its own, so one failed request leaves the other count current.
  const [openCards, setOpenCards] = useState(0);
  const [unreadMessages, setUnreadMessages] = useState(0);
  const [catalog, setCatalog] = useState<CategoryListResponse | null>(null);
  const [version, setVersion] = useState("");

  // The overview probes the archive, so it is asked once a minute, not on every navigation.
  const loadStatus = useCallback(() => {
    void api.GET("/api/status").then((result) => {
      if (result.data) setChecks(result.data.checks);
    }, ignore);
  }, []);
  usePolling(loadStatus, 60000);

  const loadInbox = useCallback(() => {
    void api.GET("/api/reviews/open-count").then((cards) => {
      if (cards.data) setOpenCards(cards.data.open);
    }, ignore);
    void api.GET("/api/notices/unread-count").then((messages) => {
      if (messages.data) setUnreadMessages(messages.data.unread);
    }, ignore);
  }, []);
  usePolling(loadInbox, 30000);
  useEffect(() => {
    window.addEventListener(INBOX_CHANGED, loadInbox);
    return () => window.removeEventListener(INBOX_CHANGED, loadInbox);
  }, [loadInbox]);

  // Folder counts change with uploads, moves and the trash, so the sidebar refreshes them on every page change.
  useEffect(() => {
    if (!desktop) return;
    let active = true;
    void api.GET("/api/categories").then((result) => {
      if (active && result.data) setCatalog(result.data);
    }, ignore);
    return () => {
      active = false;
    };
  }, [desktop, pathname]);

  useEffect(() => {
    void api.GET("/api/system/version").then((result) => setVersion(result.data?.commit ?? ""), ignore);
  }, []);

  const status = useMemo(() => summarizeStatus(checks), [checks]);
  const value = useMemo<ShellData>(
    () => ({ status, pending: openCards + unreadMessages, catalog: desktop ? catalog : null, version, desktop }),
    [status, openCards, unreadMessages, catalog, version, desktop],
  );
  return <ShellDataContext.Provider value={value}>{children}</ShellDataContext.Provider>;
}

export function useShellData(): ShellData {
  const context = useContext(ShellDataContext);
  if (!context) throw new Error("useShellData must be used within ShellDataProvider");
  return context;
}
