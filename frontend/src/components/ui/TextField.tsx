import { useId, type ComponentProps, type InputHTMLAttributes, type ReactNode } from "react";

type TextFieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: ReactNode;
  /** Shown below the field; announced with it. */
  hint?: ReactNode;
  /** Marks the field invalid and replaces the hint. */
  error?: ReactNode;
};

/** A labelled input. The hint or error is linked through aria-describedby so screen readers read it with the field. */
export function TextField({ label, hint, error, id, className, ...props }: TextFieldProps) {
  const generated = useId();
  const inputId = id ?? generated;
  const noteId = `${inputId}-note`;
  const note = error ?? hint;
  return (
    <label htmlFor={inputId} className="block">
      <span className="label">{label}</span>
      <input
        id={inputId}
        className={className ? `field ${className}` : "field"}
        aria-invalid={error ? true : undefined}
        aria-describedby={note ? noteId : undefined}
        {...props}
      />
      {note && (
        <span id={noteId} className={`mt-1 block text-xs ${error ? "text-danger" : "text-text-3"}`}>
          {note}
        </span>
      )}
    </label>
  );
}

/** Also takes a `ref` to the input (React 19 passes it as a prop), for example for a Ctrl+K shortcut. */
type SearchFieldProps = Omit<ComponentProps<"input">, "type"> & {
  /** Accessible name; the field has no visible label. */
  label: string;
  /** Keyboard shortcut hint such as "Ctrl K", shown on wide screens only. */
  shortcut?: string;
};

export function SearchField({ label, shortcut, ...props }: SearchFieldProps) {
  return (
    // A label, so a tap anywhere on the 44 px box focuses the input.
    <label className="ui-search">
      <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="square">
        <path d="M9 3h6l4 4v6l-4 4H9l-4-4V7z" />
        <path d="M17.5 17.5L21 21" />
      </svg>
      <input type="search" aria-label={label} {...props} />
      {shortcut && <kbd className="hidden md:inline">{shortcut}</kbd>}
    </label>
  );
}
