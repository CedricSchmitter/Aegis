import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { DocumentDto } from "../../api/types";
import { formatDate } from "../../lib/format";
import { problemMessage } from "../../lib/problem";
import { ErrorBox } from "../common";
import { Sheet } from "../ui";

interface Props {
  open: boolean;
  onClose: () => void;
  /** Documents already in the plan are marked; adding one again adds its pages once more. */
  inPlan: readonly string[];
  onAdd: (documentId: string) => void;
}

const RESULTS = 20;

/** Finds a stored document whose pages are added to the plan (merging); the newest first until something is typed. */
export function AddDocumentSheet({ open, onClose, inPlan, onAdd }: Props) {
  const { t, i18n } = useTranslation();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const [items, setItems] = useState<DocumentDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    let active = true;
    const search = async () => {
      try {
        const result = unwrap(
          await api.GET("/api/documents", {
            params: { query: { ...(query ? { query } : {}), sort: "NEWEST", page: 0, size: RESULTS } },
          }),
        );
        if (!active) return;
        setItems(result.items.filter((document) => !document.trash));
        setError(null);
      } catch (failure) {
        if (active) setError(problemMessage(t, failure));
      }
    };
    void search();
    return () => {
      active = false;
    };
  }, [open, query, t]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setQuery(text.trim());
  };

  return (
    <Sheet open={open} onClose={onClose} title={t("pageEdit.add.title")} testId="page-edit-add-sheet" wide>
      <form className="flex gap-2" onSubmit={submit} role="search">
        <label className="min-w-0 flex-1" htmlFor="page-edit-search">
          <span className="sr-only">{t("pageEdit.add.search")}</span>
          <input
            id="page-edit-search"
            type="search"
            className="field"
            value={text}
            placeholder={t("pageEdit.add.search")}
            onChange={(event) => {
              setText(event.target.value);
              // Emptying the field (the clear button or deleting the text) shows the newest documents again at once.
              if (!event.target.value.trim()) setQuery("");
            }}
            data-testid="page-edit-search"
          />
        </label>
        <button type="submit" className="btn-secondary">
          {t("pageEdit.add.find")}
        </button>
      </form>
      <ErrorBox message={error} />
      {items === null ? (
        <p className="text-sm text-text-3" aria-busy="true">
          {t("app.loading")}
        </p>
      ) : items.length === 0 ? (
        <p className="text-sm text-text-3">{t("pageEdit.add.none")}</p>
      ) : (
        <ul className="flex flex-col gap-2" data-testid="page-edit-candidates">
          {items.map((document) => (
            <li key={document.id}>
              <button
                type="button"
                className="ui-option"
                onClick={() => onAdd(document.id)}
                data-testid="page-edit-candidate"
                data-document-id={document.id}
              >
                <b className="col-span-2 break-words">{document.title}</b>
                <span className="col-span-2">
                  {formatDate(document.uploadedAt, i18n.language)}
                  {inPlan.includes(document.id) && ` · ${t("pageEdit.add.inPlan")}`}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Sheet>
  );
}
