import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { CategoryDto, DocumentDto } from "../api/types";
import { labelName } from "../lib/labels";
import { problemMessage } from "../lib/problem";
import { FolderSelect } from "./FolderSelect";
import { SubjectList } from "./SubjectList";

interface Props {
  document: DocumentDto;
  categories: CategoryDto[];
  onChange: (document: DocumentDto) => void;
  onError: (message: string | null) => void;
  onMessage: (message: string) => void;
  reload: () => Promise<void>;
}

function percent(value: number | null | undefined, language: string): string | null {
  if (value === null || value === undefined) return null;
  return new Intl.NumberFormat(language === "en" ? "en-GB" : "de-DE", { style: "percent" }).format(value);
}

/**
 * The folder placement a classification run proposed, and the revert of a move it made on its own.
 * The proposed parent and the new folder names can be edited before accepting; the labels and subjects the
 * target would add are shown first, because moving a document into a folder inherits them.
 */
export function PlacementSuggestion({ document, categories, onChange, onError, onMessage, reload }: Props) {
  const { t, i18n } = useTranslation();
  const suggestion = document.classification.placementSuggestion;
  const placement = document.classification.placement;
  const [parent, setParent] = useState<string | null>(null);
  const [names, setNames] = useState("");
  const [alsoForOthers, setAlsoForOthers] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!suggestion) return;
    setParent((suggestion.newFolders.length > 0 ? suggestion.anchorFolderId : suggestion.folderId) ?? null);
    setNames(suggestion.newFolders.join(" / "));
    setAlsoForOthers(false);
  }, [suggestion]);

  const run = async (action: () => Promise<DocumentDto>, done: string) => {
    setBusy(true);
    try {
      onChange(await action());
      onError(null);
      onMessage(done);
    } catch (failure) {
      onError(problemMessage(t, failure));
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const segments = names
    .split("/")
    .map((name) => name.trim())
    .filter((name) => name !== "");

  const accept = async () => {
    const anchorVersion = categories.find((category) => category.id === parent)?.version ?? null;
    setBusy(true);
    try {
      const response = unwrap(
        await api.POST("/api/documents/{id}/classification/placement/accept", {
          params: { path: { id: document.id } },
          body: {
            folderId: segments.length === 0 ? parent : null,
            anchorFolderId: segments.length === 0 ? null : parent,
            newPath: segments,
            expectedVersion: document.classification.version,
            expectedAnchorVersion: parent === null ? null : anchorVersion,
            alsoForOtherDocuments: alsoForOthers,
          },
        }),
      );
      onChange(response.document);
      onError(null);
      onMessage(t("placement.accepted", { path: response.path.join(" / "), count: response.movedDocuments }));
      await reload();
    } catch (failure) {
      onError(problemMessage(t, failure));
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const reject = () =>
    run(
      async () =>
        unwrap(
          await api.POST("/api/documents/{id}/classification/placement/reject", {
            params: { path: { id: document.id } },
            body: { expectedVersion: document.classification.version },
          }),
        ),
      t("placement.rejected"),
    );

  const revert = () =>
    run(
      async () =>
        unwrap(
          await api.POST("/api/documents/{id}/classification/placement/revert", {
            params: { path: { id: document.id } },
            body: { expectedVersion: document.classification.version },
          }),
        ),
      t("placement.reverted"),
    );

  if (!suggestion && !placement.revertible) return null;

  if (!suggestion) {
    const estimate = percent(placement.confidence, i18n.language);
    return (
      <div className="card flex flex-col gap-2" data-testid="placement-automatic">
        <p className="text-sm">
          {estimate
            ? t("placement.automaticWithConfidence", { value: estimate })
            : t("placement.automatic")}
        </p>
        <div>
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => void revert()}
            data-testid="revert-placement">
            {t("placement.revert")}
          </button>
        </div>
      </div>
    );
  }

  const estimate = percent(suggestion.confidence, i18n.language);
  return (
    <div className="card card-attention flex flex-col gap-3" data-testid="placement-suggestion">
      <p className="ui-eyebrow text-action">{t("placement.title")}</p>
      <p className="font-mono text-sm break-words text-text-2" data-testid="placement-path">
        {suggestion.path.join(" / ")}
        {estimate && (
          <span className="text-xs text-text-3"> · {t("analysis.modelEstimate", { value: estimate })}</span>
        )}
      </p>
      {suggestion.newFolders.length > 0 && (
        <p className="text-xs text-text-3">
          {t("placement.newFolders", { count: suggestion.newFolders.length })}
        </p>
      )}
      {suggestion.evidence && (
        <p className="text-xs text-text-3">{t("analysis.evidence", { text: suggestion.evidence })}</p>
      )}

      <div className="flex flex-col gap-2">
        <FolderSelect
          categories={categories}
          value={parent}
          onChange={setParent}
          label={t("placement.parent")}
          id="placement-parent"
        />
        <label className="block">
          <span className="label">{t("placement.names")}</span>
          <input
            className="field"
            value={names}
            maxLength={240}
            onChange={(event) => setNames(event.target.value)}
            data-testid="placement-names"
          />
        </label>
        <p className="text-xs text-text-3">{t("placement.namesHint")}</p>
      </div>

      <div className="text-xs text-text-3" data-testid="placement-effects">
        {suggestion.impliedLabels.length === 0 && suggestion.impliedSubjects.length === 0 ? (
          t("placement.addsNothing")
        ) : (
          <>
            <span>{t("placement.adds")} </span>
            <span>{suggestion.impliedLabels.map((chip) => labelName(chip, t)).join(", ")}</span>
            {suggestion.impliedSubjects.length > 0 && <SubjectList subjects={suggestion.impliedSubjects} />}
          </>
        )}
      </div>

      {suggestion.otherDocuments > 0 && (
        <label className="flex min-h-11 items-center gap-3 text-sm">
          <input
            type="checkbox"
            className="ui-checkbox"
            checked={alsoForOthers}
            onChange={(event) => setAlsoForOthers(event.target.checked)}
            data-testid="placement-also-for-others"
          />
          {t("placement.alsoForOthers", { count: suggestion.otherDocuments })}
        </label>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className="btn-secondary"
          disabled={busy || (segments.length === 0 && parent === null)}
          onClick={() => void accept()}
          data-testid="accept-placement"
        >
          {segments.length > 0 ? t("placement.acceptAndCreate") : t("placement.accept")}
        </button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => void reject()}
          data-testid="reject-placement">
          {t("placement.reject")}
        </button>
      </div>
      <p className="text-xs text-text-3">{t("placement.hint")}</p>
    </div>
  );
}
