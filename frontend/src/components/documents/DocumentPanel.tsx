import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { CategoryDto, DocumentDto, LabelChipDto } from "../../api/types";
import { documentProgress } from "../../lib/documentView";
import { problemMessage } from "../../lib/problem";
import { LabelChips } from "../LabelChips";
import { PlacementSuggestion } from "../PlacementSuggestion";
import { TextRecognitionHint } from "../TextRecognitionHint";
import { ProcessingSteps } from "../ui";
import { DocumentHead, DocumentPreview } from "./DocumentHead";

interface Props {
  document: DocumentDto;
  categories: CategoryDto[];
  folderName: (folderId: string) => string;
  onChange: (document: DocumentDto) => void;
  onError: (message: string | null) => void;
  onMessage: (message: string) => void;
  reload: () => Promise<void>;
}

/**
 * The selected document in the context panel of the desktop list: preview, where it stands, and the
 * suggestions to take over or reject, without leaving the list. "Open large" leads to the document view.
 */
export function DocumentPanel({ document, categories, folderName, onChange, onError, onMessage, reload }: Props) {
  const { t } = useTranslation();
  const progress = documentProgress(document);

  const review = async (chip: LabelChipDto, decision: "accept" | "reject") => {
    try {
      const path = decision === "accept"
        ? "/api/documents/{id}/labels/{labelId}/accept"
        : "/api/documents/{id}/labels/{labelId}/reject";
      onChange(
        unwrap(
          await api.POST(path, {
            params: { path: { id: document.id, labelId: chip.labelId } },
            body: { expectedVersion: document.classification.version },
          }),
        ),
      );
      onError(null);
    } catch (failure) {
      onError(problemMessage(t, failure));
      await reload();
    }
  };

  return (
    <section className="flex flex-col gap-4" aria-label={document.title} data-testid="document-panel">
      <div className="ui-sec">
        <span>{t("documents.preview.title")}</span>
      </div>
      <DocumentPreview document={document} />
      <DocumentHead document={document} heading={(title) => <h2 className="text-lg leading-tight font-bold break-words">{title}</h2>} />
      <Link to={`/documents/${document.id}`} className="btn-secondary" data-testid="open-document">
        {t("documents.openLarge")}
      </Link>
      <ProcessingSteps completed={progress.completed} current={progress.current} />
      <TextRecognitionHint document={document} />
      <PlacementSuggestion
        key={document.id}
        document={document}
        categories={categories}
        onChange={onChange}
        onError={onError}
        onMessage={onMessage}
        reload={reload}
      />
      <div className="flex flex-col gap-2">
        <div className="ui-sec">
          <span>{t("documents.labels")}</span>
        </div>
        {document.classification.labels.length === 0 ? (
          <p className="text-sm text-text-3">{t("documents.noLabels")}</p>
        ) : (
          <LabelChips
            chips={document.classification.labels}
            folderName={folderName}
            onAccept={(chip) => void review(chip, "accept")}
            onReject={(chip) => void review(chip, "reject")}
          />
        )}
      </div>
    </section>
  );
}
