import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { DocumentDto, SubjectDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { formatDate, formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { Badge } from "./common";

interface Props {
  document: DocumentDto;
  onChange: (document: DocumentDto) => void;
  onError: (message: string | null) => void;
  onMessage: (message: string) => void;
  reload: () => Promise<void>;
}

/** States in which a member may start (or restart) the analysis of an archived document. */
const REQUESTABLE = new Set(["NOT_REQUESTED", "NOT_CLASSIFIED", "FAILED", "UNAVAILABLE", "CLASSIFIED", "NEEDS_REVIEW"]);
/** States in which the analysis has not produced anything yet, so starting it is the main action. */
const NOT_STARTED = new Set(["NOT_REQUESTED", "NOT_CLASSIFIED"]);

function percent(value: number | null | undefined, language: string): string | null {
  if (value === null || value === undefined) return null;
  return new Intl.NumberFormat(language === "en" ? "en-GB" : "de-DE", { style: "percent" }).format(value);
}

/**
 * Automatic analysis of one document: its state apart from archival, the generated title, issue date and
 * suggestions with accept/reject, the friendly filename with manual override, and explicit (re)processing.
 */
export function AnalysisPanel({ document, onChange, onError, onMessage, reload }: Props) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const a = document.analysis;
  const id = document.id;

  const run = async (action: () => Promise<DocumentDto>, done?: string) => {
    setBusy(true);
    try {
      onChange(await action());
      onError(null);
      if (done) onMessage(done);
    } catch (failure) {
      onError(problemMessage(t, failure));
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const request = () =>
    run(
      async () =>
        unwrap(
          await api.POST("/api/documents/{id}/analysis", {
            params: { path: { id } },
            body: { expectedVersion: a.version },
          }),
        ),
      t("analysis.requested"),
    );

  const decideTitle = (accept: boolean) =>
    run(async () =>
      unwrap(
        await api.POST("/api/documents/{id}/analysis/title", {
          params: { path: { id } },
          body: { accept, expectedVersion: a.version },
        }),
      ),
    );

  const decideCandidate = (key: string, accept: boolean) =>
    run(async () =>
      unwrap(
        await api.POST("/api/documents/{id}/analysis/candidates", {
          params: { path: { id } },
          body: { key, accept, expectedVersion: a.version },
        }),
      ),
    );

  const decideSubject = (subject: SubjectDto, accept: boolean) =>
    run(async () =>
      unwrap(
        await api.POST("/api/documents/{id}/analysis/subjects", {
          params: { path: { id } },
          body: { subject, accept, expectedVersion: a.version },
        }),
      ),
    );

  const saveName = (stem: string | null) =>
    run(async () => {
      const updated = unwrap(
        await api.PUT("/api/documents/{id}/filename", {
          params: { path: { id } },
          body: { stem, expectedVersion: a.version },
        }),
      );
      setEditingName(false);
      return updated;
    }, t("analysis.filenameSaved"));

  const subjectLabel = (subject: SubjectDto) =>
    subject.kind === "MEMBER"
      ? (memberName(subject.memberId ?? "") ?? t("app.unknownMember"))
      : t(`subjects.${subject.kind}`);

  const archived = document.processing.state === "ARCHIVED";
  const pendingCandidates = a.candidates.filter((candidate) => candidate.decision === "SUGGESTED");
  const pendingSubjects = a.subjects.filter((subject) => subject.decision === "SUGGESTED");
  const titleOpen = a.title && (a.titleDecision === "SUGGESTED" || a.titleDecision === "APPLIED");

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="analysis-heading" data-testid="analysis">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="analysis-heading" className="text-lg font-semibold">
          {t("analysis.title")}
        </h2>
        <Badge state={a.state} label={t(`analysis.stateShort.${a.state}`)} />
      </div>
      <p className="text-sm" data-testid="analysis-state">
        {t(`analysis.state.${a.state}`, { name: memberName(a.requestedBy ?? "") ?? t("app.unknownMember") })}
      </p>
      {a.state === "PENDING" && a.runState && (
        <p className="text-sm text-text-3">
          {t(`ai.state.${a.runState}`)}
          {a.nextAttemptAt && ` · ${t("ai.nextAttempt", { date: formatDateTime(a.nextAttemptAt, i18n.language) })}`}
        </p>
      )}
      {(a.state === "FAILED" || a.state === "UNAVAILABLE") && (
        <p className="text-sm text-danger" data-testid="analysis-failure">
          {a.failureCategory
            ? t(`ai.failure.${a.failureCategory}`, { defaultValue: t("errors.generic") })
            : t(`errors.${a.failureCode}`, { defaultValue: t("errors.generic") })}
        </p>
      )}
      {a.completedAt && (
        <p className="text-xs text-text-3">
          {t("analysis.completed", {
            date: formatDateTime(a.completedAt, i18n.language),
            model: a.model ?? "",
            name: memberName(a.requestedBy ?? "") ?? t("app.unknownMember"),
          })}
        </p>
      )}
      {a.flags.length > 0 && (
        <ul className="alert-info list-inside list-disc text-sm" data-testid="analysis-flags">
          {a.flags.map((flag) => (
            <li key={flag}>{t(`analysis.flag.${flag}`)}</li>
          ))}
        </ul>
      )}

      {titleOpen && (
        <div className="flex flex-col gap-1" data-testid="analysis-title">
          <p className="text-sm">
            {a.titleDecision === "APPLIED"
              ? t("analysis.titleApplied", { title: a.title })
              : t("analysis.titleSuggested", { title: a.title })}
          </p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => void decideTitle(true)}>
              {a.titleDecision === "APPLIED" ? t("analysis.keepTitle") : t("analysis.useTitle")}
            </button>
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => void decideTitle(false)}>
              {a.titleDecision === "APPLIED" ? t("analysis.revertTitle") : t("analysis.rejectTitle")}
            </button>
          </div>
        </div>
      )}

      <p className="text-sm" data-testid="analysis-date">
        {a.documentDateStatus === "FOUND" && a.documentDate
          ? t("analysis.dateFound", { date: formatDate(a.documentDate, i18n.language) })
          : t(`analysis.date.${a.documentDateStatus}`)}
        {a.documentDateEvidence && (
          <span className="block text-xs text-text-3">
            {t("analysis.evidence", { text: a.documentDateEvidence })}
          </span>
        )}
      </p>

      {pendingCandidates.length > 0 && (
        <div className="flex flex-col gap-2" data-testid="analysis-candidates">
          <p className="text-sm font-medium">{t("analysis.newNames")}</p>
          {pendingCandidates.map((candidate) => (
            <div key={candidate.key} className="flex flex-wrap items-center gap-2 text-sm">
              <span>
                {t(`facets.${candidate.facet}`)}: <strong>{candidate.name}</strong>
                {percent(candidate.confidence, i18n.language) && (
                  <span className="text-xs text-text-3">
                    {" "}
                    · {t("analysis.modelEstimate", { value: percent(candidate.confidence, i18n.language) })}
                  </span>
                )}
              </span>
              <button
                type="button"
                className="btn-secondary"
                disabled={busy}
                onClick={() => void decideCandidate(candidate.key, true)}
              >
                {t("analysis.createAndAssign")}
              </button>
              <button
                type="button"
                className="btn-ghost"
                disabled={busy}
                onClick={() => void decideCandidate(candidate.key, false)}
              >
                {t("analysis.reject")}
              </button>
            </div>
          ))}
        </div>
      )}

      {pendingSubjects.length > 0 && (
        <div className="flex flex-col gap-2" data-testid="analysis-subjects">
          <p className="text-sm font-medium">{t("analysis.subjectSuggestions")}</p>
          {pendingSubjects.map((suggestion) => (
            <div
              key={`${suggestion.subject.kind}:${suggestion.subject.memberId ?? ""}`}
              className="flex flex-wrap items-center gap-2 text-sm"
            >
              <span>{subjectLabel(suggestion.subject)}</span>
              {suggestion.evidence && (
                <span className="text-xs text-text-3">{t("analysis.evidence", { text: suggestion.evidence })}</span>
              )}
              <button
                type="button"
                className="btn-secondary"
                disabled={busy}
                onClick={() => void decideSubject(suggestion.subject, true)}
              >
                {t("analysis.accept")}
              </button>
              <button
                type="button"
                className="btn-ghost"
                disabled={busy}
                onClick={() => void decideSubject(suggestion.subject, false)}
              >
                {t("analysis.reject")}
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-col gap-1 border-t border-line pt-3 ">
        <p className="text-sm font-medium">{t("analysis.filename")}</p>
        <p className="break-all font-mono text-sm" data-testid="friendly-filename">
          {document.filename.filename}
        </p>
        <p className="text-xs text-text-3">
          {t(`analysis.filenameSource.${document.filename.source}`)} ·{" "}
          {t("analysis.originalFilename", { name: document.originalFilename })}
        </p>
        {editingName ? (
          <div className="flex flex-wrap items-end gap-2">
            <label className="label mb-0" htmlFor="filename-draft">
              {t("analysis.filenameEdit")}
            </label>
            <input
              id="filename-draft"
              className="field max-w-sm"
              value={nameDraft}
              maxLength={120}
              onChange={(event) => setNameDraft(event.target.value)}
            />
            <button
              type="button"
              className="btn-primary"
              disabled={busy || nameDraft.trim() === ""}
              onClick={() => void saveName(nameDraft)}
              data-testid="save-filename"
            >
              {t("app.save")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setEditingName(false)}>
              {t("app.cancel")}
            </button>
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="btn-ghost"
              onClick={() => {
                setNameDraft(document.filename.override ?? document.filename.filename.replace(/_[0-9a-f]{8}\.\w+$/, ""));
                setEditingName(true);
              }}
              data-testid="edit-filename"
            >
              {t("analysis.filenameEdit")}
            </button>
            {document.filename.source === "MANUAL" && (
              <button type="button" className="btn-ghost" disabled={busy} onClick={() => void saveName(null)}>
                {t("analysis.filenameReset")}
              </button>
            )}
          </div>
        )}
        <p className="text-xs text-text-3">{t("analysis.filenameHint")}</p>
      </div>

      {archived && REQUESTABLE.has(a.state) && (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className={NOT_STARTED.has(a.state) ? "btn-primary" : "btn-secondary"}
            disabled={busy}
            onClick={() => void request()}
            data-testid="request-analysis"
          >
            {NOT_STARTED.has(a.state)
              ? t("analysis.start")
              : a.state === "FAILED" || a.state === "UNAVAILABLE"
                ? t("analysis.retry")
                : t("analysis.reprocess")}
          </button>
          <span className="text-xs text-text-3">{t("analysis.costHint")}</span>
        </div>
      )}
      <p className="text-xs text-text-3">{t("analysis.policyHint")}</p>

      {a.history.length > 0 && (
        <details className="text-sm">
          <summary className="cursor-pointer">{t("analysis.history", { count: a.history.length })}</summary>
          <ul className="mt-2 flex flex-col gap-1 text-xs text-text-3">
            {a.history.map((entry) => (
              <li key={`${entry.generation}-${entry.runRef ?? ""}`}>
                {entry.completedAt && formatDateTime(entry.completedAt, i18n.language)} · {entry.title ?? "–"}
                {entry.documentDate && ` · ${formatDate(entry.documentDate, i18n.language)}`}
                {entry.shortTopic && ` · ${entry.shortTopic}`}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
