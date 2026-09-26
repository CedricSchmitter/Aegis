import { useId, type InputHTMLAttributes, type ReactNode } from "react";

type SwitchProps = {
  checked: boolean;
  onChange: (checked: boolean) => void;
  children: ReactNode;
  /** Secondary text under the label, read as the switch's description. */
  description?: ReactNode;
  disabled?: boolean;
};

/** An on/off setting that takes effect at once. Space and Enter toggle it because it is a native button. */
export function Switch({ checked, onChange, children, description, disabled }: SwitchProps) {
  const id = useId();
  const labelId = `${id}-label`;
  const descriptionId = `${id}-description`;
  return (
    <button
      type="button"
      role="switch"
      className="ui-switch"
      aria-checked={checked}
      aria-labelledby={labelId}
      aria-describedby={description ? descriptionId : undefined}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="flex min-w-0 flex-1 flex-col">
        <span id={labelId} className="font-medium">
          {children}
        </span>
        {description && (
          <span id={descriptionId} className="text-sm text-text-3">
            {description}
          </span>
        )}
      </span>
      <span className="ui-switch-track" aria-hidden="true">
        <span className="ui-switch-thumb" />
      </span>
    </button>
  );
}

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & { children: ReactNode };

/** A native checkbox with a chamfered box; the whole row is the 44 px touch target. */
export function Checkbox({ children, id, className, ...props }: CheckboxProps) {
  const generated = useId();
  const inputId = id ?? generated;
  return (
    <label htmlFor={inputId} className={`flex min-h-11 cursor-pointer items-center gap-3 ${className ?? ""}`}>
      <input id={inputId} type="checkbox" className="ui-checkbox" {...props} />
      <span>{children}</span>
    </label>
  );
}
