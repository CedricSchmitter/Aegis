import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import type { CategoryDto } from "../api/types";
import { UNSORTED } from "../lib/catalogSearch";
import { ancestorsOf, buildTree, subtreeCount, type FolderNode } from "../lib/tree";
import { Diamond } from "./shell/icons";

interface Props {
  categories: CategoryDto[];
  unsortedCount: number;
  /** The folder the document list shows, "unsorted", or null. */
  selected: string | null;
  onSelect: (category: string) => void;
}

type Row = { id: string; label: string; depth: number; count: number; node?: FolderNode };

/**
 * The folders of the desktop sidebar as an expandable ARIA tree with a roving tab stop (issue #27): arrow keys,
 * Home/End and Enter work as well as the mouse. Counts include subfolders; Unsorted closes the list.
 */
export function FolderTree({ categories, unsortedCount, selected, onSelect }: Props) {
  const { t } = useTranslation();
  const roots = useMemo(() => buildTree(categories), [categories]);
  const [expanded, setExpanded] = useState<string[]>([]);
  const [focused, setFocused] = useState<string | null>(selected);
  const container = useRef<HTMLUListElement>(null);

  useEffect(() => {
    setExpanded((current) => [...new Set([...current, ...ancestorsOf(categories, selected)])]);
  }, [categories, selected]);

  const rows: Row[] = [];
  const append = (nodes: FolderNode[], depth: number) => {
    for (const node of nodes) {
      rows.push({ id: node.category.id, label: node.category.name, depth, count: subtreeCount(node), node });
      if (expanded.includes(node.category.id)) append(node.children, depth + 1);
    }
  };
  append(roots, 0);
  if (unsortedCount > 0 || selected === UNSORTED) {
    rows.push({ id: UNSORTED, label: t("documents.unsorted"), depth: 0, count: unsortedCount });
  }

  const toggle = (id: string) =>
    setExpanded((current) => (current.includes(id) ? current.filter((entry) => entry !== id) : [...current, id]));

  const focus = (id: string) => {
    setFocused(id);
    requestAnimationFrame(() => {
      container.current?.querySelector<HTMLElement>(`[data-folder="${id}"]`)?.focus();
    });
  };

  const move = (from: string, delta: number) => {
    const index = rows.findIndex((row) => row.id === from);
    const next = rows[Math.min(rows.length - 1, Math.max(0, (index < 0 ? 0 : index) + delta))];
    if (next) focus(next.id);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLLIElement>, row: Row) => {
    if (event.target !== event.currentTarget) return;
    const hasChildren = (row.node?.children.length ?? 0) > 0;
    const isExpanded = expanded.includes(row.id);
    switch (event.key) {
      case "ArrowDown":
        move(row.id, 1);
        break;
      case "ArrowUp":
        move(row.id, -1);
        break;
      case "ArrowRight":
        if (hasChildren && !isExpanded) toggle(row.id);
        else if (hasChildren) move(row.id, 1);
        break;
      case "ArrowLeft":
        if (hasChildren && isExpanded) toggle(row.id);
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

  if (rows.length === 0) return null;
  const activeRow = rows.some((row) => row.id === focused) ? focused : rows[0].id;

  return (
    <ul ref={container} role="tree" aria-label={t("folders.tree")} data-testid="folder-tree">
      {rows.map((row) => {
        const isSelected = row.id === selected;
        const hasChildren = (row.node?.children.length ?? 0) > 0;
        const isExpanded = expanded.includes(row.id);
        return (
          <li
            key={row.id}
            role="treeitem"
            aria-selected={isSelected}
            aria-expanded={hasChildren ? isExpanded : undefined}
            aria-level={row.depth + 1}
            data-folder={row.id}
            data-testid={`folder-${row.id}`}
            tabIndex={row.id === activeRow ? 0 : -1}
            onKeyDown={(event) => onKeyDown(event, row)}
            onClick={() => onSelect(row.id)}
            onFocus={() => setFocused(row.id)}
            style={{ paddingLeft: `${0.5 + row.depth * 0.875}rem` }}
            className="flex min-h-11 cursor-pointer items-center gap-1.5 border-l-2 border-transparent pr-4 text-sm text-text-2 hover:bg-raised hover:text-text-1 focus-visible:-outline-offset-2 aria-selected:border-action aria-selected:bg-action/6 aria-selected:text-action"
          >
            {hasChildren ? (
              <button
                type="button"
                tabIndex={-1}
                className="grid h-11 w-8 flex-none cursor-pointer place-items-center text-text-4 hover:text-text-1"
                aria-label={isExpanded ? t("folders.collapse") : t("folders.expand")}
                onClick={(event) => {
                  event.stopPropagation();
                  toggle(row.id);
                }}
              >
                <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="square" className={isExpanded ? "rotate-90" : ""}>
                  <path d="M9 5l7 7-7 7" />
                </svg>
              </button>
            ) : (
              <span className="grid w-8 flex-none place-items-center" aria-hidden="true">
                <Diamond className={`h-1.5 w-1.5 ${row.id === UNSORTED ? "text-text-4" : "text-data"}`} />
              </span>
            )}
            <span className="min-w-0 flex-1 truncate">{row.label}</span>
            {row.count > 0 && <span className="font-mono text-xs text-text-4">{row.count}</span>}
          </li>
        );
      })}
    </ul>
  );
}
