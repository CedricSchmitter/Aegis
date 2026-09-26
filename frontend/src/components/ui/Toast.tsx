import { useEffect, useRef, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

type ToastProps = {
  /** Short mono headline, such as "Gespeichert". */
  title: ReactNode;
  children?: ReactNode;
  /** Offers an undo button; choosing it undoes and dismisses the toast. */
  onUndo?: () => void;
  onDismiss: () => void;
  /** Milliseconds until the toast dismisses itself; 0 keeps it until the member acts. */
  duration?: number;
  variant?: "default" | "danger";
  testId?: string;
};

/**
 * A short confirmation after an action, announced politely. Say what happened, then what can be done now.
 * A toast with undo stays long enough to reach the button (default 8 s instead of 5 s).
 */
export function Toast({ title, children, onUndo, onDismiss, duration, variant = "default", testId = "toast" }: ToastProps) {
  const { t } = useTranslation();
  const dismiss = useRef(onDismiss);
  const timeout = duration ?? (onUndo ? 8000 : 5000);

  useEffect(() => {
    dismiss.current = onDismiss;
  }, [onDismiss]);

  useEffect(() => {
    if (timeout <= 0) return;
    const timer = window.setTimeout(() => dismiss.current(), timeout);
    return () => window.clearTimeout(timer);
  }, [timeout]);

  return (
    <div className="ui-toast" role="status" aria-live="polite" data-variant={variant} data-testid={testId}>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <b className={`ui-eyebrow font-normal ${variant === "danger" ? "text-danger" : "text-data"}`}>{title}</b>
        {children && <span className="text-sm text-text-2">{children}</span>}
      </div>
      {onUndo && (
        <button
          type="button"
          className="btn-secondary"
          onClick={() => {
            onUndo();
            dismiss.current();
          }}
        >
          {t("ui.toast.undo")}
        </button>
      )}
      <button type="button" className="btn-ghost w-11 px-0" aria-label={t("app.close")} onClick={() => dismiss.current()}>
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="square">
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      </button>
    </div>
  );
}
