import { useTranslation } from "react-i18next";
import type { CategoryDto } from "../api/types";
import { UNSORTED } from "../lib/catalogSearch";
import { pathOf } from "../lib/tree";

interface Props {
  categories: CategoryDto[];
  selected: string | null;
  onSelect: (category: string | null) => void;
}

/** Mono breadcrumb of the selected folder above the list title; every step jumps to that folder. */
export function Breadcrumbs({ categories, selected, onSelect }: Props) {
  const { t } = useTranslation();
  const path = selected === UNSORTED ? [] : pathOf(categories, selected);
  const step = "min-h-8 cursor-pointer text-data hover:underline";
  return (
    <nav aria-label={t("documents.folderPath")} className="ui-eyebrow flex flex-wrap items-center gap-x-1.5">
      <button type="button" className={step} onClick={() => onSelect(null)}>
        {t("documents.title")}
      </button>
      {selected === UNSORTED && (
        <>
          <span aria-hidden="true">/</span>
          <span className="text-text-2" aria-current="page">
            {t("documents.unsorted")}
          </span>
        </>
      )}
      {path.map((category, index) => (
        <span key={category.id} className="flex items-center gap-x-1.5">
          <span aria-hidden="true">/</span>
          {index === path.length - 1 ? (
            <span className="text-text-2" aria-current="page">
              {category.name}
            </span>
          ) : (
            <button type="button" className={step} onClick={() => onSelect(category.id)}>
              {category.name}
            </button>
          )}
        </span>
      ))}
    </nav>
  );
}
