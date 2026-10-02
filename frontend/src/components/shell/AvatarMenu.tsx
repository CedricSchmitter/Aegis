import { useEffect, useId, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { useSession } from "../../auth/SessionContext";
import { statusHeadline } from "../../lib/shellStatus";
import { Diamond, Icon, type IconName } from "./icons";
import { useShellData } from "./ShellData";
import { TONE_TEXT } from "./StatusIndicator";

/**
 * The avatar opens Settings, Status and Trash and signs out. A disclosure with links rather than
 * an ARIA menu: it closes on Escape, on a click outside and on navigation, and returns focus to the avatar on Escape.
 * A red mark on the avatar says that the status overview reports a problem. The panel exists only while open, so a
 * closed menu adds nothing to the page's text or accessibility tree.
 */
export function AvatarMenu() {
  const { t } = useTranslation();
  const { member, logout } = useSession();
  const { status } = useShellData();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const name = member?.displayName ?? "";
  const initial = name.trim().charAt(0).toLocaleUpperCase() || "?";
  const problem = status.tone === "problem";

  useEffect(() => setOpen(false), [pathname]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      if (!container.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        button.current?.focus();
      }
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const label = [t("shell.avatar.label", { name }), problem ? t("shell.avatar.problem") : null].filter(Boolean).join(". ");

  const item = (to: string, icon: IconName, text: string, extra?: React.ReactNode, testId?: string) => (
    <li>
      <Link
        to={to}
        className="flex min-h-11 items-center gap-3 px-4 text-sm text-text-2 hover:bg-raised hover:text-text-1"
        onClick={() => setOpen(false)}
        data-testid={testId}
      >
        <Icon name={icon} className="h-[18px] w-[18px] text-text-3" />
        <span>{text}</span>
        {extra}
      </Link>
    </li>
  );

  return (
    <div ref={container} className="relative">
      <button
        ref={button}
        type="button"
        className="flex min-h-11 cursor-pointer items-center gap-2.5 text-left"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={label}
        onClick={() => setOpen((current) => !current)}
        data-testid="avatar-menu-toggle"
      >
        <span className="relative grid h-10 w-10 place-items-center bg-data font-bold text-void [clip-path:polygon(7px_0,100%_0,100%_calc(100%-7px),calc(100%-7px)_100%,0_100%,0_7px)]">
          {initial}
        </span>
        {problem && (
          <span className="absolute top-0.5 left-[30px] h-2.5 w-2.5 bg-danger shadow-[0_0_0_2px_var(--color-void)]" data-testid="avatar-problem" aria-hidden="true" />
        )}
        {/* The name next to the avatar only where the desktop header has room for it. */}
        <span className="hidden flex-col text-sm leading-tight font-semibold lg:flex" aria-hidden="true">
          {name}
          <small className="font-mono text-[10px] font-normal tracking-[0.16em] text-text-3 uppercase">{t("shell.household")}</small>
        </span>
      </button>
      {open && (
        <div
          id={panelId}
          className="frame absolute top-full right-0 z-50 mt-2 w-72 py-2 [--frame-chamfer:var(--chamfer-lg)] [--frame-fill:var(--color-surface)] [--frame-line:var(--color-line-strong)]"
          data-testid="avatar-menu"
        >
          <p className="px-4 pt-1 pb-2 font-mono text-xs tracking-[0.18em] text-text-4 uppercase">
            {t("shell.avatar.signedInAs")} <span className="text-data">{name}</span>
          </p>
          <ul>
            {item("/settings", "settings", t("nav.settings"), undefined, "avatar-menu-settings")}
            {item(
              "/status",
              "status",
              t("nav.status"),
              <span className={`ml-auto flex items-center gap-1.5 font-mono text-[11px] tracking-[0.1em] uppercase ${TONE_TEXT[status.tone]}`}>
                <Diamond className="h-1.5 w-1.5" />
                {statusHeadline(t, status)}
              </span>,
              "avatar-menu-status",
            )}
            {item("/trash", "trash", t("trash.title"), undefined, "avatar-menu-trash")}
          </ul>
          <div className="mt-2 border-t border-line pt-2">
            <button
              type="button"
              className="flex min-h-11 w-full cursor-pointer items-center gap-3 px-4 text-left text-sm text-text-2 hover:bg-raised hover:text-text-1"
              onClick={async () => {
                setOpen(false);
                // Leave the protected route first so the login page does not remember it as the return target.
                navigate("/login");
                await logout();
              }}
            >
              <Icon name="logout" className="h-[18px] w-[18px] text-text-3" />
              {t("nav.logout")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
