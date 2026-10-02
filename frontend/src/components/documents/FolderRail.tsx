import { useMemo } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { CategoryListResponse } from "../../api/types";
import { UNSORTED } from "../../lib/catalogSearch";
import { railFolders, subtreeCount } from "../../lib/tree";

interface Props {
  catalog: CategoryListResponse;
  selected: string | null;
  onSelect: (category: string | null) => void;
}

/**
 * The swipeable folder rail of the phone: All, Unsorted and the folders of the current level with
 * their counts, and "Manage folders" at the end. Deeper levels open below a folder; the breadcrumb leads back up.
 */
export function FolderRail({ catalog, selected, onSelect }: Props) {
  const { t } = useTranslation();
  const folders = useMemo(() => railFolders(catalog.categories, selected), [catalog.categories, selected]);
  const topLevel = folders.every((node) => node.depth === 0);
  const pill = (id: string | null, label: string, count: number) => (
    <button
      key={id ?? "all"}
      type="button"
      className="ui-pill"
      aria-pressed={selected === id}
      data-testid={`folder-${id ?? "all"}`}
      onClick={() => onSelect(id)}
    >
      {label}
      <span className="ui-pill-count">{count}</span>
    </button>
  );
  return (
    <div className="ui-rail" role="group" aria-label={t("documents.folders")} data-testid="folder-rail">
      {pill(null, t("documents.allShort"), catalog.totalDocuments)}
      {topLevel && (catalog.unsortedCount > 0 || selected === UNSORTED) && pill(UNSORTED, t("documents.unsorted"), catalog.unsortedCount)}
      {folders.map((node) => pill(node.category.id, node.category.name, subtreeCount(node)))}
      <Link to="/documents/folders" className="ui-pill ui-pill-link" data-testid="manage-folders">
        {t("documents.manageFolders")}
      </Link>
    </div>
  );
}
