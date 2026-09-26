import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { PANEL_QUERY, useMediaQuery } from "./ShellData";

interface Slot {
  target: HTMLElement | null;
  /** Counts the pages that currently fill the panel; the column only takes room while one does. */
  register(): () => void;
}

const ContextPanelContext = createContext<Slot | null>(null);

/**
 * The right column of the desktop shell (issue #27) for the selection of the current page: a document preview and
 * suggestions, the selected task or contract, or messages. The pages fill it from PR 3 on; without content the
 * column takes no room. It is shown from 1280 px; below that a page shows the same content in its own flow.
 */
export function useContextPanelSlot() {
  const [target, setTarget] = useState<HTMLElement | null>(null);
  const [filled, setFilled] = useState(0);
  const register = useCallback(() => {
    setFilled((count) => count + 1);
    return () => setFilled((count) => count - 1);
  }, []);
  const slot = useMemo<Slot>(() => ({ target, register }), [target, register]);
  return { slot, filled: filled > 0, setTarget };
}

export function ContextPanelProvider({ slot, children }: { slot: Slot; children: ReactNode }) {
  return <ContextPanelContext.Provider value={slot}>{children}</ContextPanelContext.Provider>;
}

/** The empty aside that pages render into; the shell places it as the third column. */
export function ContextPanelColumn({ filled, setTarget }: { filled: boolean; setTarget: (element: HTMLElement | null) => void }) {
  const { t } = useTranslation();
  return (
    <aside
      ref={setTarget}
      aria-label={t("shell.contextPanel")}
      hidden={!filled}
      className="ui-panel sticky top-14 hidden h-[calc(100dvh-3.5rem-1.75rem)] overflow-y-auto border-l border-line bg-surface p-4.5 xl:block"
      data-testid="context-panel"
    />
  );
}

/** Renders its children into the context panel of the desktop shell. */
export function ContextPanel({ children }: { children: ReactNode }) {
  const slot = useContext(ContextPanelContext);
  const register = slot?.register;
  useEffect(() => register?.(), [register]);
  return slot?.target ? createPortal(children, slot.target) : null;
}

/** Whether the context panel is shown, so a page can decide where its selection goes. */
export function usePanelShown(): boolean {
  const slot = useContext(ContextPanelContext);
  return useMediaQuery(PANEL_QUERY) && slot !== null;
}

/**
 * The same content in the context panel from 1280 px and in the page flow below, rendered once, so its ids and
 * form state exist a single time whatever the width.
 */
export function ContextPanelOrFlow({ children }: { children: ReactNode }) {
  return usePanelShown() ? <ContextPanel>{children}</ContextPanel> : <>{children}</>;
}
