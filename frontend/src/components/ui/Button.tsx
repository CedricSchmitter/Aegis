import type { ButtonHTMLAttributes } from "react";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";

/** Class names for a button look, also for links that act as buttons. Use one primary action per view. */
export function buttonClass(variant: ButtonVariant = "secondary", extra?: string): string {
  return extra ? `btn-${variant} ${extra}` : `btn-${variant}`;
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant };

/** A chamfered button with a 44 px minimum height. Defaults to type="button" so it never submits a form by accident. */
export function Button({ variant = "secondary", className, type = "button", ...props }: ButtonProps) {
  return <button type={type} className={buttonClass(variant, className)} {...props} />;
}
