import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import type { CategoryDto } from "../api/types";
import { buildTree, flatten, isInSubtree } from "../lib/tree";

interface Props {
  categories: CategoryDto[];
  value: string | null;
  onChange: (value: string | null) => void;
  /** Hides the folder and its subfolders; used when choosing a new parent. */
  exclude?: string;
  label: string;
  topLabel?: string;
  id?: string;
}

/** Folder picker as a native select with indented names; works on both phones without extra gestures. */
export function FolderSelect({ categories, value, onChange, exclude, label, topLabel, id }: Props) {
  const { t } = useTranslation();
  const options = useMemo(() => {
    const roots = buildTree(categories);
    return flatten(roots)
      .filter((node) => !exclude || !isInSubtree(roots, node.category.id, exclude))
      .map((node) => ({
        id: node.category.id,
        text: `${"  ".repeat(node.depth)}${node.category.name}`,
      }));
  }, [categories, exclude]);

  return (
    <label className="block">
      <span className="label">{label}</span>
      <select
        id={id}
        className="field"
        value={value ?? ""}
        aria-label={label}
        onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
      >
        <option value="">{topLabel ?? t("documents.unsorted")}</option>
        {options.map((option) => (
          <option key={option.id} value={option.id}>
            {option.text}
          </option>
        ))}
      </select>
    </label>
  );
}
