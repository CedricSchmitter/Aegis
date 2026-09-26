import type { HTMLAttributes } from "react";

/** attention uses the action colour, data the data colour, danger is for overdue and broken things. */
export type CardTone = "attention" | "data" | "danger";

type CardProps = HTMLAttributes<HTMLElement> & {
  as?: "div" | "section" | "article" | "li";
  tone?: CardTone;
  selected?: boolean;
};

export function cardClass(tone?: CardTone, selected?: boolean, extra?: string): string {
  return ["card", tone && `card-${tone}`, selected && "card-selected", extra].filter(Boolean).join(" ");
}

/** A chamfered frame on the surface colour. The selected state wins over the tone. */
export function Card({ as: Element = "div", tone, selected = false, className, ...props }: CardProps) {
  return <Element className={cardClass(tone, selected, className)} data-selected={selected || undefined} {...props} />;
}
