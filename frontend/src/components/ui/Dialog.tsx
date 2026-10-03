import { useEffect, useId, useLayoutEffect, useRef, useState, type ComponentPropsWithoutRef, type MouseEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

/*
 * Layering (ADR-0019, decision 12): dialogs, sheets and overlays are native modal <dialog> elements in the browser's
 * top layer. Whatever has to cover an open one is a modal dialog of its own, such as the Overlay, never a fixed element
 * inside it: a sheet is a scroll container, and a swipe on a fixed element inside it would scroll the page behind.
 */

/**
 * Keeps a native <dialog> in step with `open`. The native modal makes the rest of the page inert, traps focus,
 * returns focus on close and turns Escape into a cancel event, which is reported through onClose.
 */
function useModal(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDialogElement>(null);
  const close = useRef(onClose);

  useEffect(() => {
    close.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      if (typeof dialog.showModal === "function") dialog.showModal();
      else dialog.setAttribute("open", "");
    } else if (!open && dialog.open) {
      if (typeof dialog.close === "function") dialog.close();
      else dialog.removeAttribute("open");
    }
  }, [open]);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const onCancel = (event: Event) => {
      event.preventDefault();
      close.current();
    };
    dialog.addEventListener("cancel", onCancel);
    return () => dialog.removeEventListener("cancel", onCancel);
  }, []);

  return ref;
}

type DialogProps = {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  /** Buttons at the bottom; the dialog does not add any on its own. */
  actions?: ReactNode;
  /** Small mono line above the title. */
  eyebrow?: ReactNode;
  /** Danger adds the red frame and the warning stripe. Reserve it for irreversible actions. */
  variant?: "default" | "danger";
};

/** A centred modal for decisions. */
export function Dialog({ open, onClose, title, children, actions, eyebrow, variant = "default" }: DialogProps) {
  const ref = useModal(open, onClose);
  const titleId = useId();
  return (
    <dialog ref={ref} className="ui-dialog" data-variant={variant} aria-labelledby={titleId}>
      {variant === "danger" && <span className="ui-dialog-stripe" aria-hidden="true" />}
      {eyebrow && <p className={`ui-eyebrow ${variant === "danger" ? "text-danger" : ""}`}>{eyebrow}</p>}
      <h2 id={titleId}>{title}</h2>
      {children}
      {actions && <div className="flex flex-wrap justify-end gap-2">{actions}</div>}
    </dialog>
  );
}

/** Whether a click on the dialog element landed on its backdrop rather than on its content. */
function isBackdropClick(event: MouseEvent<HTMLDialogElement>): boolean {
  if (event.target !== event.currentTarget) return false;
  const box = event.currentTarget.getBoundingClientRect();
  return event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom;
}

type SheetProps = {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  testId?: string;
  /** A wider panel on large screens, for forms with previews such as the upload. */
  wide?: boolean;
  /** A full-height panel from the left edge on every screen, for lists to pick from such as earlier questions. */
  side?: boolean;
};

/** A bottom sheet on phones and a centred panel on wider screens, for choices and short forms. */
export function Sheet({ open, onClose, title, children, testId, wide = false, side = false }: SheetProps) {
  const { t } = useTranslation();
  const ref = useModal(open, onClose);
  const titleId = useId();
  return (
    <dialog
      ref={ref}
      className="ui-sheet"
      data-wide={wide || undefined}
      data-side={side || undefined}
      aria-labelledby={titleId}
      data-testid={testId}
      onClick={(event) => {
        if (isBackdropClick(event)) onClose();
      }}
    >
      <div className="flex items-center justify-between gap-3">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="btn-ghost -mr-2 w-11 px-0" aria-label={t("app.close")} onClick={onClose}>
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="square">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      {children}
    </dialog>
  );
}

type OverlayProps = Omit<ComponentPropsWithoutRef<"dialog">, "open" | "onClose" | "onCancel"> & {
  onClose: () => void;
  children: ReactNode;
};

/**
 * A full-screen modal that is open for as long as it is mounted, such as the scan's corner editor. It is a modal
 * dialog of its own: opened from a sheet, it stacks above the sheet in the top layer instead of lying inside the
 * sheet's scroll container, and its contained overscroll keeps a swipe from scrolling the sheet or the page behind it.
 * The focus moves into it; Escape or the phone's back gesture reports onClose for the overlay only, and
 * closing returns the focus to where it was before, such as the sheet's button that opened it.
 */
export function Overlay({ onClose, className = "", children, ...rest }: OverlayProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const close = useRef(onClose);

  useEffect(() => {
    close.current = onClose;
  }, [onClose]);

  // A layout effect, because its cleanup runs before React removes the element: only a dialog closed while still in
  // the document returns the focus.
  useLayoutEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
    dialog.focus();
    const onCancel = (event: Event) => {
      // Without a recent user activation the browser closes the dialog anyway; onNativeClose reports that.
      if (!event.cancelable) return;
      event.preventDefault();
      close.current();
    };
    // The close event arrives a task later, so one left over from a closed and reopened dialog must be ignored.
    const onNativeClose = () => {
      if (!dialog.open) close.current();
    };
    dialog.addEventListener("cancel", onCancel);
    dialog.addEventListener("close", onNativeClose);
    return () => {
      dialog.removeEventListener("cancel", onCancel);
      dialog.removeEventListener("close", onNativeClose);
      if (!dialog.open) return;
      if (typeof dialog.close === "function") dialog.close();
      else dialog.removeAttribute("open");
    };
  }, []);

  return (
    <dialog ref={ref} tabIndex={-1} className={`ui-overlay ${className}`} {...rest}>
      {children}
    </dialog>
  );
}

/** Whether the typed text confirms: case and surrounding spaces do not matter, the word does. */
export function matchesConfirmation(typed: string, word: string): boolean {
  return typed.trim().toLocaleUpperCase() === word.trim().toLocaleUpperCase();
}

type ConfirmDialogProps = {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: ReactNode;
  children: ReactNode;
  /** The word the member has to type, such as "LÖSCHEN". */
  confirmWord: string;
  /** Label of the destructive button, such as "Endgültig löschen". */
  confirmLabel: string;
  busy?: boolean;
};

/**
 * The danger dialog for irreversible actions such as purging the trash: the destructive button stays
 * disabled until the member has typed the confirmation word.
 */
export function ConfirmDialog({ open, onClose, onConfirm, title, children, confirmWord, confirmLabel, busy = false }: ConfirmDialogProps) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState("");
  const inputId = useId();
  const confirmed = matchesConfirmation(typed, confirmWord);

  const close = () => {
    setTyped("");
    onClose();
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={title}
      eyebrow={t("ui.confirm.eyebrow")}
      variant="danger"
      actions={
        <>
          <button type="button" className="btn-ghost" onClick={close}>
            {t("app.cancel")}
          </button>
          <button
            type="button"
            className="btn-danger"
            disabled={!confirmed || busy}
            onClick={() => {
              setTyped("");
              onConfirm();
            }}
          >
            {confirmLabel}
          </button>
        </>
      }
    >
      <div className="text-sm text-text-2">{children}</div>
      <label htmlFor={inputId} className="block">
        <span className="ui-eyebrow mb-1 block">{t("ui.confirm.typeWord", { word: confirmWord })}</span>
        <input
          id={inputId}
          className="field font-mono tracking-[0.1em] uppercase"
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          value={typed}
          onChange={(event) => setTyped(event.target.value)}
        />
      </label>
    </Dialog>
  );
}
