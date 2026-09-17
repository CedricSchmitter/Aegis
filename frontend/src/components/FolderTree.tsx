import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import type { CategoryDto } from "../api/types";
import { UNSORTED } from "../lib/catalogSearch";
import { ancestorsOf, buildTree, subtreeCount, type FolderNode } from "../lib/tree";

interface Props {
  categories: CategoryDto[];
  unsortedCount: number;
  totalDocuments: number;
  selected: string | null;
  onSelect: (category: string | null) => void;
}

type Row = { id: string | null; label: string; depth: number; count: number; node?: FolderNode };

/**
 * Expandable folder navigation with "All documents" and the Unsorted inbox. Implemented as an ARIA tree with a
 * roving tab stop, so it can be used with arrow keys, Home/End and Enter as well as by touch.
 */
export function FolderTree({ categories, unsortedCount, totalDocuments, selected, onSelect }: Props) {
  const { t } = useTranslation();
  const roots = useMemo(() => buildTree(categories), [categories]);
  const [expanded, setExpanded] = useState<string[]>([]);
  const [focused, setFocused] = useState<string | null>(selected);
  const container = useRef<HTMLUListElement>(null);

  useEffect(() => {
    setExpanded((current) => [...new Set([...current, ...ancestorsOf(categories, selected)])]);
  }, [categories, selected]);

  const rows: Row[] = [
    { id: null, label: t("documents.allDocuments"), depth: 0, count: totalDocuments },
    { id: UNSORTED, label: t("documents.unsorted"), depth: 0, count: unsortedCount },
  ];
  const append = (nodes: FolderNode[], depth: number) => {
    for (const node of nodes) {
      rows.push({ id: node.category.id, label: node.category.name, depth, count: subtreeCount(node), node });
      if (expanded.includes(node.category.id)) append(node.children, depth + 1);
    }
  };
  append(roots, 0);

  const toggle = (id: string) =>
    setExpanded((current) => (current.includes(id) ? current.filter((entry) => entry !== id) : [...current, id]));

  const move = (from: string | null, delta: number) => {
    const index = rows.findIndex((row) => row.id === from);
    const next = rows[Math.min(rows.length - 1, Math.max(0, (index < 0 ? 0 : index) + delta))];
    if (next) focus(next.id);
  };

  const focus = (id: string | null) => {
    setFocused(id);
    const key = id ?? "all";
    requestAnimationFrame(() => {
      container.current?.querySelector<HTMLElement>(`[data-folder="${key}"]`)?.focus();
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLLIElement>, row: Row) => {
    const hasChildren = (row.node?.children.length ?? 0) > 0;
    const isExpanded = row.id ? expanded.includes(row.id) : false;
    switch (event.key) {
      case "ArrowDown":
        move(row.id, 1);
        break;
      case "ArrowUp":
        move(row.id, -1);
        break;
      case "ArrowRight":
        if (hasChildren && !isExpanded && row.id) toggle(row.id);
        else if (hasChildren) move(row.id, 1);
        break;
      case "ArrowLeft":
        if (hasChildren && isExpanded && row.id) toggle(row.id);
        else if (row.node?.category.parentId) focus(row.node.category.parentId);
        break;
      case "Home":
        focus(rows[0].id);
        break;
      case "End":
        focus(rows[rows.length - 1].id);
        break;
      case "Enter":
      case " ":
        onSelect(row.id);
        break;
      default:
        return;
    }
    event.preventDefault();
  };

  const activeRow = rows.some((row) => row.id === focused) ? focused : rows[0].id;

  return (
    <nav aria-label={t("folders.tree")} className="card p-2">
      <ul ref={container} role="tree" aria-label={t("folders.tree")} className="flex flex-col">
        {rows.map((row) => {
          const isSelected = row.id === selected || (row.id === null && selected === null);
          const hasChildren = (row.node?.children.length ?? 0) > 0;
          const isExpanded = row.id ? expanded.includes(row.id) : undefined;
          return (
            <li
              key={row.id ?? "all"}
              role="treeitem"
              aria-selected={isSelected}
              aria-expanded={hasChildren ? isExpanded : undefined}
              aria-level={row.depth + 1}
              data-folder={row.id ?? "all"}
              data-testid={`folder-${row.id ?? "all"}`}
              tabIndex={row.id === activeRow ? 0 : -1}
              onKeyDown={(event) => onKeyDown(event, row)}
              onClick={() => onSelect(row.id)}
              style={{ paddingLeft: `${row.depth * 0.75 + 0.25}rem` }}
              className={`flex min-h-11 cursor-pointer list-none items-center gap-1 rounded-lg px-2 text-sm ${
                isSelected
                  ? "bg-sky-100 font-semibold text-sky-900 dark:bg-sky-900 dark:text-sky-100"
                  : "hover:bg-stone-100 dark:hover:bg-stone-800"
              }`}
            >
              <>
                {hasChildren ? (
                  <button
                    type="button"
                    className="flex h-8 w-6 items-center justify-center text-stone-500"
                    aria-label={isExpanded ? t("folders.collapse") : t("folders.expand")}
                    aria-hidden={false}
                    onClick={(event) => {
                      event.stopPropagation();
                      if (row.id) toggle(row.id);
                    }}
                  >
                    {isExpanded ? "▾" : "▸"}
                  </button>
                ) : (
                  <span className="w-6" aria-hidden="true" />
                )}
                <span className="flex-1 truncate">{row.label}</span>
                <span className="text-xs text-stone-500">{row.count}</span>
              </>
            </li>
          );
        })}
      </ul>
      <p className="px-2 pt-1 text-xs text-stone-500">{t("documents.unsortedHint")}</p>
    </nav>
  );
}
