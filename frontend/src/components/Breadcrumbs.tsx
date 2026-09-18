import { useTranslation } from "react-i18next";
import type { CategoryDto } from "../api/types";
import { UNSORTED } from "../lib/catalogSearch";
import { pathOf } from "../lib/tree";

interface Props {
  categories: CategoryDto[];
  selected: string | null;
  onSelect: (category: string | null) => void;
}

/** Breadcrumb of the selected folder; every step jumps to that folder. */
export function Breadcrumbs({ categories, selected, onSelect }: Props) {
  const { t } = useTranslation();
  const path = selected === UNSORTED ? [] : pathOf(categories, selected);
  return (
    <nav aria-label={t("documents.folders")} className="mb-2 flex flex-wrap items-center gap-1 text-sm">
      <button type="button" className="text-sky-800 hover:underline dark:text-sky-300" onClick={() => onSelect(null)}>
        {t("documents.allDocuments")}
      </button>
      {selected === UNSORTED && (
        <>
          <span aria-hidden="true">/</span>
          <span className="font-semibold" aria-current="page">
            {t("documents.unsorted")}
          </span>
        </>
      )}
      {path.map((category, index) => (
        <span key={category.id} className="flex items-center gap-1">
          <span aria-hidden="true">/</span>
          {index === path.length - 1 ? (
            <span className="font-semibold" aria-current="page">
              {category.name}
            </span>
          ) : (
            <button
              type="button"
              className="text-sky-800 hover:underline dark:text-sky-300"
              onClick={() => onSelect(category.id)}
            >
              {category.name}
            </button>
          )}
        </span>
      ))}
    </nav>
  );
}
