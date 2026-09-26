import { useMemo } from "react";
import { Link, NavLink, useLocation, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { UNSORTED } from "../../lib/catalogSearch";
import { buildTree, subtreeCount } from "../../lib/tree";
import { Icon, Diamond, type IconName } from "./icons";
import { useShellData } from "./ShellData";

/**
 * The five areas, in the order of the phone tab bar (issue #83 F5). Status, Settings and Trash are reached through
 * the avatar; on the desktop, Trash and Settings also sit at the bottom of the sidebar.
 */
export const AREAS: readonly { to: string; key: string; icon: IconName }[] = [
  { to: "/today", key: "nav.today", icon: "today" },
  { to: "/documents", key: "nav.documents", icon: "documents" },
  { to: "/tasks", key: "nav.tasks", icon: "tasks" },
  { to: "/contracts", key: "nav.contracts", icon: "contracts" },
  { to: "/inbox", key: "nav.inbox", icon: "inbox" },
];

/** The count badge of the inbox: action colour, because open cards and messages ask to be looked at. */
function InboxCount({ className }: { className: string }) {
  const { t } = useTranslation();
  const { pending } = useShellData();
  if (pending <= 0) return null;
  return (
    <span className={`grid h-[18px] min-w-[18px] place-items-center bg-action px-1 font-mono text-[11px] text-void ${className}`} aria-label={t("nav.pendingCount", { count: pending })} data-testid="inbox-badge">
      {pending}
    </span>
  );
}

/** Phone tab bar: five entries with at least 44 px targets; NavLink sets aria-current on the active one. */
export function BottomNav() {
  const { t } = useTranslation();
  return (
    <nav
      aria-label={t("nav.menu")}
      className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-line bg-surface pb-[env(safe-area-inset-bottom)] lg:hidden"
      data-testid="bottom-nav"
    >
      {AREAS.map((area) => (
        <NavLink
          key={area.to}
          to={area.to}
          className="relative flex min-h-[60px] flex-col items-center justify-center gap-1 px-0.5 text-center text-xs leading-tight font-medium text-text-3 aria-[current=page]:text-action aria-[current=page]:before:absolute aria-[current=page]:before:inset-x-[22%] aria-[current=page]:before:-top-px aria-[current=page]:before:h-0.5 aria-[current=page]:before:bg-action"
        >
          <Icon name={area.icon} className="h-[22px] w-[22px]" />
          <span>{t(area.key)}</span>
          {area.to === "/inbox" && <InboxCount className="absolute top-1.5 left-[calc(50%+6px)]" />}
        </NavLink>
      ))}
    </nav>
  );
}

const SIDE_ITEM =
  "flex min-h-11 w-full items-center gap-2.5 border-l-2 border-transparent px-4 text-left text-sm text-text-2 hover:bg-raised hover:text-text-1 aria-[current=page]:border-action aria-[current=page]:bg-action/6 aria-[current=page]:text-action";

/** A number at the end of a sidebar row, in the mono font. */
function RowCount({ value, className = "text-text-4" }: { value: number | string | null; className?: string }) {
  if (value === null || value === "" || value === 0) return null;
  return <span className={`ml-auto font-mono text-xs ${className}`}>{value}</span>;
}

/** Top-level folders with the documents in them and below them, plus Unsorted, linked to the filtered list. */
function FolderList() {
  const { t } = useTranslation();
  const { catalog } = useShellData();
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const selected = pathname === "/documents" ? params.get("category") : null;
  const roots = useMemo(() => buildTree(catalog?.categories ?? []), [catalog]);
  const folders = [
    ...roots.map((node) => ({ id: node.category.id, name: node.category.name, count: subtreeCount(node) })),
    ...(catalog && catalog.unsortedCount > 0 ? [{ id: UNSORTED, name: t("documents.unsorted"), count: catalog.unsortedCount }] : []),
  ];
  return (
    // Only the folders scroll, so Upload, the areas, Trash and Settings stay in view with any number of folders.
    <nav aria-label={t("shell.folders")} className="min-h-0 flex-1 overflow-y-auto" data-testid="sidebar-folders">
      <p className="px-4 pt-4 pb-1.5 font-mono text-[11px] tracking-[0.22em] text-text-4 uppercase" aria-hidden="true">
        {t("shell.folders")}
      </p>
      <ul>
        {folders.map((folder) => (
          <li key={folder.id}>
            <Link
              to={`/documents?${new URLSearchParams({ category: folder.id })}`}
              className={SIDE_ITEM}
              aria-current={selected === folder.id ? "page" : undefined}
            >
              <Diamond className="ml-1.5 h-1.5 w-1.5 text-data" />
              <span className="min-w-0 truncate">{folder.name}</span>
              <RowCount value={folder.count} />
            </Link>
          </li>
        ))}
        <li>
          <Link to="/documents/folders" className={`${SIDE_ITEM} text-data`}>
            <span aria-hidden="true" className="w-4 text-center">+</span>
            {t("documents.manageFolders")}
          </Link>
        </li>
      </ul>
    </nav>
  );
}

/**
 * Desktop sidebar (issue #27): Upload as the one primary action, the five areas, the folders with their counts, and
 * Trash and Settings at the bottom. It stays in place while the work area scrolls.
 */
export function Sidebar() {
  const { t } = useTranslation();
  const { catalog, pending } = useShellData();
  const counts: Record<string, number | null> = {
    "/documents": catalog?.totalDocuments ?? null,
    "/inbox": pending,
  };
  return (
    <aside
      className="sticky top-14 hidden h-[calc(100dvh-3.5rem-1.75rem)] flex-col border-r border-line bg-surface pt-3.5 pb-2.5 lg:flex"
      aria-label={t("shell.sidebar")}
      data-testid="sidebar"
    >
      <Link to="/documents?upload=1" className="btn-primary mx-3.5 mb-2.5" data-testid="sidebar-upload">
        <Icon name="upload" className="h-[18px] w-[18px]" />
        {t("shell.upload")}
      </Link>
      <nav aria-label={t("nav.menu")}>
        <ul>
          {AREAS.map((area) => (
            <li key={area.to}>
              <NavLink to={area.to} className={SIDE_ITEM}>
                <Icon name={area.icon} className="h-[18px] w-[18px]" />
                {t(area.key)}
                <RowCount value={counts[area.to] ?? null} className={area.to === "/inbox" ? "text-action" : "text-text-4"} />
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <FolderList />
      <ul className="flex-none border-t border-line pt-1.5">
        <li>
          <NavLink to="/trash" className={SIDE_ITEM}>
            <Icon name="trash" className="h-[18px] w-[18px]" />
            {t("trash.title")}
          </NavLink>
        </li>
        <li>
          <NavLink to="/settings" className={SIDE_ITEM}>
            <Icon name="settings" className="h-[18px] w-[18px]" />
            {t("nav.settings")}
          </NavLink>
        </li>
      </ul>
    </aside>
  );
}
