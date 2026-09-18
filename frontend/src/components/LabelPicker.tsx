import { useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import type { Facet, LabelDto } from "../api/types";
import { findByName, labelName, matchLabels } from "../lib/labels";

interface Props {
  facet: Facet;
  labels: LabelDto[];
  onPick: (label: LabelDto) => void;
  onCreate?: (name: string) => Promise<void> | void;
  disabled?: boolean;
}

/**
 * Autocomplete for one facet: matches names, aliases and translated built-in names, and offers to create a new
 * label when nothing matches. Keyboard: arrows to move, Enter to take the highlighted entry, Escape to close.
 */
export function LabelPicker({ facet, labels, onPick, onCreate, disabled }: Props) {
  const { t } = useTranslation();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const matches = useMemo(() => matchLabels(labels, facet, query, t).slice(0, 8), [labels, facet, query, t]);
  const existing = findByName(labels, facet, query, t);
  const canCreate = Boolean(onCreate) && query.trim().length > 0 && !existing;

  const take = async (index: number) => {
    if (index < matches.length) {
      onPick(matches[index]);
    } else if (canCreate && onCreate) {
      await onCreate(query.trim());
    }
    setQuery("");
    setOpen(false);
    setActive(0);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const options = matches.length + (canCreate ? 1 : 0);
    if (event.key === "ArrowDown") setActive((current) => (options === 0 ? 0 : (current + 1) % options));
    else if (event.key === "ArrowUp") setActive((current) => (options === 0 ? 0 : (current - 1 + options) % options));
    else if (event.key === "Enter") void take(active);
    else if (event.key === "Escape") setOpen(false);
    else return;
    event.preventDefault();
  };

  const listId = `label-options-${facet}`;
  return (
    <div className="relative">
      <input
        ref={input}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-label={t("documents.addLabel") + ": " + t(`facets.${facet}`)}
        placeholder={t("labels.searchPlaceholder")}
        className="field"
        disabled={disabled}
        value={query}
        data-testid={`label-picker-${facet}`}
        onFocus={() => setOpen(true)}
        onBlur={() => window.setTimeout(() => setOpen(false), 150)}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
          setActive(0);
        }}
        onKeyDown={onKeyDown}
      />
      {open && (matches.length > 0 || canCreate) && (
        <ul
          id={listId}
          role="listbox"
          data-testid={`label-options-${facet}`}
          className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-stone-300 bg-white shadow-lg dark:border-stone-700 dark:bg-stone-900"
        >
          {matches.map((label, index) => (
            <li key={label.id}>
              <button
                type="button"
                role="option"
                aria-selected={index === active}
                className={`flex w-full min-h-11 items-center justify-between gap-2 px-3 text-left text-sm ${
                  index === active ? "bg-sky-50 dark:bg-stone-800" : ""
                }`}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => void take(index)}
              >
                <span>{labelName(label, t)}</span>
                <span className="text-xs text-stone-500">
                  {t("labels.assignments", { count: label.assignmentCount })}
                </span>
              </button>
            </li>
          ))}
          {canCreate && (
            <li>
              <button
                type="button"
                role="option"
                aria-selected={active === matches.length}
                className={`flex w-full min-h-11 items-center px-3 text-left text-sm ${
                  active === matches.length ? "bg-sky-50 dark:bg-stone-800" : ""
                }`}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => void take(matches.length)}
                data-testid={`label-create-${facet}`}
              >
                {t("labels.createNamed", { name: query.trim() })}
              </button>
            </li>
          )}
        </ul>
      )}
      {existing && query.trim() && (
        <p className="mt-1 text-xs text-stone-500">{t("labels.existing", { name: labelName(existing, t) })}</p>
      )}
    </div>
  );
}
