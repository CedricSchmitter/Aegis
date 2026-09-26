import { useState } from "react";
import { useTranslation } from "react-i18next";
import { FACETS, type Facet, type LabelChipDto, type LabelDto } from "../api/types";
import { byId, labelName, ownLabelIds } from "../lib/labels";
import { LabelPicker } from "./LabelPicker";

interface Props {
  chips: LabelChipDto[];
  labels: LabelDto[];
  onSave: (labelIds: string[]) => Promise<void>;
  onCreate: (facet: Facet, name: string) => Promise<LabelDto | null>;
}

/** Editing of the own labels of a document, one row per facet; the document type replaces its previous value. */
export function LabelEditor({ chips, labels, onSave, onCreate }: Props) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<string[]>(() => ownLabelIds(chips));
  const [saving, setSaving] = useState(false);
  const [creating, setCreating] = useState(false);
  const known = byId(labels);

  const inFacet = (facet: Facet) =>
    draft.filter((id) => known.get(id)?.facet === facet || (facet === "TAG" && !known.has(id)));

  const add = (facet: Facet, id: string) =>
    setDraft((current) => {
      const withoutType = facet === "DOCUMENT_TYPE"
        ? current.filter((entry) => known.get(entry)?.facet !== "DOCUMENT_TYPE")
        : current;
      return withoutType.includes(id) ? withoutType : [...withoutType, id];
    });

  const remove = (id: string) => setDraft((current) => current.filter((entry) => entry !== id));

  return (
    <div className="flex flex-col gap-3" data-testid="label-editor">
      {FACETS.map((facet) => (
        <div key={facet} className="flex flex-col gap-1">
          <p className="label mb-0">{t(`facets.${facet}`)}</p>
          <p className="text-xs text-text-3">{t(`facets.hint${facet}`)}</p>
          <ul className="flex flex-wrap gap-1">
            {inFacet(facet).map((id) => (
              <li key={id}>
                <span className="ui-chip">
                  {labelName(known.get(id), t) || id}
                  <button
                    type="button"
                    className="ui-chip-remove"
                    aria-label={t("documents.removeLabel", { name: labelName(known.get(id), t) })}
                    onClick={() => remove(id)}
                  >
                    ×
                  </button>
                </span>
              </li>
            ))}
          </ul>
          <LabelPicker
            facet={facet}
            labels={labels}
            disabled={creating}
            onPick={(label) => add(facet, label.id)}
            onCreate={async (name) => {
              setCreating(true);
              try {
                const created = await onCreate(facet, name);
                if (created) add(facet, created.id);
              } finally {
                setCreating(false);
              }
            }}
          />
        </div>
      ))}
      <div className="flex gap-2">
        <button
          type="button"
          className="btn-primary"
          disabled={saving || creating}
          data-testid="save-labels"
          onClick={async () => {
            setSaving(true);
            try {
              await onSave(draft);
            } finally {
              setSaving(false);
            }
          }}
        >
          {t("app.save")}
        </button>
        <button type="button" className="btn-ghost" onClick={() => setDraft(ownLabelIds(chips))}>
          {t("app.cancel")}
        </button>
      </div>
    </div>
  );
}
