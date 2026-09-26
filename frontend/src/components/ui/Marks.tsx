import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

/** Signal tones: attention = action colour, data = data colour, danger = overdue or broken, neutral = grey. */
export type Tone = "attention" | "data" | "danger" | "neutral";

/** A small outlined status mark. Show it for deviations only; the normal state needs no badge. */
export function Badge({ tone = "neutral", children, ...rest }: { tone?: Tone; children: ReactNode } & DataAttributes) {
  return (
    <span className="ui-badge" data-tone={tone} {...rest}>
      {children}
    </span>
  );
}

type DataAttributes = { [key: `data-${string}`]: string | undefined };

export type FileKind = "pdf" | "scan" | "photo";

const FILE_KIND_TONES: Record<FileKind, Tone> = { pdf: "neutral", scan: "data", photo: "attention" };

/** The file type of a document: an uploaded PDF, a scan made in the app, or a photo original. */
export function FileTypeTag({ kind }: { kind: FileKind }) {
  const { t } = useTranslation();
  return (
    <span className="ui-tag" data-tone={FILE_KIND_TONES[kind]}>
      {t(`ui.fileKind.${kind}`)}
    </span>
  );
}

type ChipProps = {
  children: ReactNode;
  /** Short facet name shown before the value, such as "Org" or "Thema". */
  facet?: string;
  /** Shows a remove button; its accessible name includes the chip text. */
  onRemove?: () => void;
  /** The chip text for the remove button's accessible name when children are not plain text. */
  removeName?: string;
};

export function Chip({ children, facet, onRemove, removeName }: ChipProps) {
  const { t } = useTranslation();
  const name = removeName ?? (typeof children === "string" ? children : "");
  return (
    <span className="ui-chip">
      {facet && <small className="ui-chip-facet">{facet}</small>}
      <span>{children}</span>
      {onRemove && (
        <button type="button" className="ui-chip-remove" aria-label={t("ui.chip.remove", { name })} onClick={onRemove}>
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="square">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      )}
    </span>
  );
}
