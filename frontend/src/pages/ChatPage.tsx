import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import type { ChatAnswerDto, ChatCitationDto, ChatOverviewDto, ChatTurnDto, ConversationDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { BackLink, BigTitle, ErrorBox, InfoBox, Loading } from "../components/common";
import { Icon } from "../components/shell/icons";
import { Badge, Button, Dialog, type Tone } from "../components/ui";
import {
  CHAT_POLL_MS,
  MAX_QUESTION,
  answerSegments,
  citationTarget,
  newId,
  progressSteps,
  turnOpen,
  turnRetryable,
  waitingMessage,
} from "../lib/chat";
import { failureReasonText } from "../lib/failureReasons";
import { formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";

/**
 * Questions to the household assistant (issue #25). It reads the household's documents, contracts and tasks and
 * answers with numbered sources; every quote was checked against its source on the server. A question runs in the
 * background, so the page shows what the assistant is doing and keeps checking until the answer is there; leaving
 * and coming back shows the same state. Conversations belong to the member who asked.
 */
export function ChatPage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [overview, setOverview] = useState<ChatOverviewDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const loadOverview = useCallback(async () => {
    try {
      setOverview(unwrap(await api.GET("/api/chat/conversations")));
      setLoadError(null);
    } catch (failure) {
      setLoadError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);

  return (
    <div className="lg:grid lg:grid-cols-[260px_minmax(0,1fr)] lg:gap-6" data-testid="chat-page">
      <aside className={id ? "hidden lg:block" : "block"}>
        <BigTitle eyebrow={t("chat.eyebrow")}>{t("chat.title")}</BigTitle>
        <ErrorBox message={loadError} />
        {overview ? <ConversationList overview={overview} selected={id ?? null} /> : !loadError && <Loading />}
      </aside>
      <section className={id ? "block" : "mt-6 lg:mt-0"} aria-label={t("chat.conversation")}>
        {id ? (
          <ConversationThread key={id} id={id} overview={overview} onChanged={loadOverview} />
        ) : (
          overview && <NewConversation overview={overview} onStarted={loadOverview} />
        )}
      </section>
    </div>
  );
}

function ConversationList({ overview, selected }: { overview: ChatOverviewDto; selected: string | null }) {
  const { t, i18n } = useTranslation();
  return (
    <nav aria-label={t("chat.conversations")}>
      <Link to="/chat" className="btn-secondary mb-3 w-full" aria-current={selected === null ? "page" : undefined} data-testid="chat-new">
        <Icon name="plus" className="h-4 w-4" />
        {t("chat.newConversation")}
      </Link>
      {overview.conversations.length === 0 ? (
        <p className="text-sm text-text-3">{t("chat.noConversations")}</p>
      ) : (
        <ul className="flex flex-col gap-1" data-testid="chat-conversations">
          {overview.conversations.map((conversation) => (
            <li key={conversation.id}>
              <Link
                to={`/chat/${conversation.id}`}
                className="flex min-h-11 flex-col justify-center border-l-2 border-transparent px-3 py-1.5 text-sm text-text-2 hover:bg-raised hover:text-text-1 aria-[current=page]:border-action aria-[current=page]:bg-action/6 aria-[current=page]:text-action"
                aria-current={conversation.id === selected ? "page" : undefined}
              >
                <span className="line-clamp-2 break-words">{conversation.title}</span>
                <span className="font-mono text-[11px] text-text-4">
                  {formatDateTime(conversation.updatedAt, i18n.language)} · {t("chat.questionCount", { count: conversation.questions })}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </nav>
  );
}

/** Why the assistant cannot be asked right now, in the member's words. */
function Unavailable({ overview }: { overview: ChatOverviewDto }) {
  const { t } = useTranslation();
  if (overview.available) return null;
  const reason = overview.unavailableReason ?? "ai.notConfigured";
  return <InfoBox>{t(`chat.unavailable.${reason}`, { defaultValue: t("chat.unavailable.generic") })}</InfoBox>;
}

type Ids = { question: string; conversationId: string; turnId: string };

/**
 * The ids of a question being sent. Sending the same words again after a failed request reuses them, so the server
 * returns what the first request may already have created instead of asking twice.
 */
function useQuestionIds(conversationId: string | null) {
  const pending = useRef<Ids | null>(null);
  return {
    idsFor(question: string): Ids {
      if (pending.current?.question === question) return pending.current;
      pending.current = { question, conversationId: conversationId ?? newId(), turnId: newId() };
      return pending.current;
    },
    sent() {
      pending.current = null;
    },
  };
}

function NewConversation({ overview, onStarted }: { overview: ChatOverviewDto; onStarted: () => Promise<void> }) {
  const { t } = useTranslation();
  const { member } = useSession();
  const navigate = useNavigate();
  const ids = useQuestionIds(null);
  const [error, setError] = useState<string | null>(null);

  const send = async (question: string): Promise<boolean> => {
    const { conversationId, turnId } = ids.idsFor(question);
    setError(null);
    try {
      unwrap(await api.POST("/api/chat/conversations", { body: { conversationId, turnId, question, locale: member?.locale ?? "DE" } }));
      ids.sent();
      await onStarted();
      navigate(`/chat/${conversationId}`);
      return true;
    } catch (failure) {
      setError(problemMessage(t, failure));
      return false;
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="card flex flex-col gap-2">
        <h2 className="text-lg font-semibold">{t("chat.introTitle")}</h2>
        <p className="text-sm text-text-2">{t("chat.intro")}</p>
        <ul className="list-disc pl-5 text-sm text-text-3">
          <li>{t("chat.example1")}</li>
          <li>{t("chat.example2")}</li>
          <li>{t("chat.example3")}</li>
        </ul>
      </div>
      <Unavailable overview={overview} />
      <ErrorBox message={error} />
      <Composer disabled={!overview.available} onSend={send} autoFocus />
    </div>
  );
}

function ConversationThread({ id, overview, onChanged }: { id: string; overview: ChatOverviewDto | null; onChanged: () => Promise<void> }) {
  const { t } = useTranslation();
  const { member } = useSession();
  const navigate = useNavigate();
  const ids = useQuestionIds(id);
  const [conversation, setConversation] = useState<ConversationDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const end = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      setConversation(unwrap(await api.GET("/api/chat/conversations/{id}", { params: { path: { id } } })));
      setLoadError(null);
    } catch (failure) {
      setLoadError(toApiError(failure).status === 404 ? t("chat.notFound") : problemMessage(t, failure));
    }
  }, [id, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const latest = conversation?.turns.at(-1);
  const open = turnOpen(latest);
  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => void load(), CHAT_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [open, conversation, load]);

  const turnCount = conversation?.turns.length ?? 0;
  const answered = latest?.state === "ANSWERED";
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "end" });
  }, [turnCount, answered]);

  const send = async (question: string): Promise<boolean> => {
    const { turnId } = ids.idsFor(question);
    setError(null);
    try {
      setConversation(
        unwrap(
          await api.POST("/api/chat/conversations/{id}/turns", {
            params: { path: { id } },
            body: { conversationId: id, turnId, question, locale: member?.locale ?? "DE" },
          }),
        ),
      );
      ids.sent();
      void onChanged();
      return true;
    } catch (failure) {
      setError(problemMessage(t, failure));
      return false;
    }
  };

  const act = async (turn: ChatTurnDto, action: "cancel" | "retry") => {
    setBusy(true);
    setError(null);
    try {
      const path = action === "cancel" ? "/api/chat/conversations/{id}/turns/{turnId}/cancel" : "/api/chat/conversations/{id}/turns/{turnId}/retry";
      setConversation(unwrap(await api.POST(path, { params: { path: { id, turnId: turn.id } } })));
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setConfirmDelete(false);
    setBusy(true);
    try {
      unwrap(await api.DELETE("/api/chat/conversations/{id}", { params: { path: { id } } }));
      await onChanged();
      navigate("/chat");
    } catch (failure) {
      setError(problemMessage(t, failure));
      setBusy(false);
    }
  };

  if (loadError && !conversation) {
    return (
      <div className="flex flex-col gap-3">
        <BackLink to="/chat">{t("chat.title")}</BackLink>
        <ErrorBox message={loadError} />
      </div>
    );
  }
  if (!conversation) return <Loading />;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between gap-2">
          <div className="lg:hidden">
            <BackLink to="/chat">{t("chat.title")}</BackLink>
          </div>
          <Button variant="ghost" className="ml-auto" onClick={() => setConfirmDelete(true)} disabled={busy} data-testid="chat-delete">
            <Icon name="trash" className="h-4 w-4" />
            {t("chat.delete")}
          </Button>
        </div>
        <h2 className="text-lg font-semibold break-words">{conversation.title}</h2>
      </div>
      <ol className="flex flex-col gap-5" data-testid="chat-turns">
        {conversation.turns.map((turn) => (
          <TurnItem
            key={turn.id}
            turn={turn}
            latest={turn.id === latest?.id}
            busy={busy}
            onCancel={() => void act(turn, "cancel")}
            onRetry={() => void act(turn, "retry")}
          />
        ))}
      </ol>
      <div ref={end} />
      {overview && <Unavailable overview={overview} />}
      <ErrorBox message={error} />
      <Composer disabled={open || busy || overview?.available === false} waiting={open} onSend={send} />
      <Dialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        title={t("chat.deleteTitle")}
        variant="danger"
        actions={
          <>
            <Button onClick={() => setConfirmDelete(false)}>{t("app.cancel")}</Button>
            <Button variant="danger" onClick={() => void remove()} data-testid="chat-delete-confirm">
              {t("chat.delete")}
            </Button>
          </>
        }
      >
        <p className="text-sm text-text-2">{t("chat.deleteText")}</p>
      </Dialog>
    </div>
  );
}

function TurnItem({ turn, latest, busy, onCancel, onRetry }: { turn: ChatTurnDto; latest: boolean; busy: boolean; onCancel: () => void; onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <li className="flex flex-col gap-2" data-testid="chat-turn" data-state={turn.state}>
      <div className="max-w-[85%] self-end bg-raised px-3.5 py-2.5 text-sm whitespace-pre-wrap break-words text-text-1" data-testid="chat-question">
        {turn.question}
      </div>
      {turn.answer ? (
        <Answer answer={turn.answer} />
      ) : turnOpen(turn) ? (
        <div className="card flex flex-col gap-2" role="status" aria-live="polite" data-testid="chat-progress">
          <p className="flex items-center gap-2 text-sm text-text-2">
            <span className="h-2 w-2 animate-pulse bg-data motion-reduce:animate-none" aria-hidden="true" />
            {turn.state === "WAITING" ? <WaitingText turn={turn} /> : t("chat.working")}
          </p>
          <ul className="flex flex-col gap-0.5 font-mono text-xs text-text-3">
            {progressSteps(turn.progress).map((step, index) => (
              <li key={index}>
                {t(step.key)}
                {step.count > 1 && ` ×${step.count}`}
              </li>
            ))}
          </ul>
          {latest && (
            <Button className="self-start" onClick={onCancel} disabled={busy} data-testid="chat-cancel">
              {t("chat.cancel")}
            </Button>
          )}
        </div>
      ) : (
        <div className="card card-danger flex flex-col gap-2" data-testid="chat-failed">
          <p className="text-sm text-text-2">
            {turn.state === "CANCELLED" ? t("chat.cancelled") : t("chat.failed", { reason: failureReasonText(t, turn.failureCode) })}
          </p>
          {latest && turnRetryable(turn) && (
            <Button className="self-start" onClick={onRetry} disabled={busy} data-testid="chat-retry">
              {t("chat.retry")}
            </Button>
          )}
        </div>
      )}
    </li>
  );
}

function WaitingText({ turn }: { turn: ChatTurnDto }) {
  const { t, i18n } = useTranslation();
  const message = waitingMessage(turn);
  return <>{t(message.key, message.at ? { time: formatDateTime(message.at, i18n.language) } : undefined)}</>;
}

const EVIDENCE_TONES: Record<string, Tone> = { COMPLETE: "data", PARTIAL: "attention", NOT_FOUND: "neutral" };
const CHECK_TONES: Record<string, Tone> = { VERIFIED: "data", NOT_IN_SOURCE: "danger", SOURCE_UNAVAILABLE: "danger" };

function Answer({ answer }: { answer: ChatAnswerDto }) {
  const { t } = useTranslation();
  return (
    <article className="card flex flex-col gap-3" data-testid="chat-answer">
      <div className="flex items-center gap-2">
        <Badge tone={EVIDENCE_TONES[answer.evidence] ?? "neutral"} data-evidence={answer.evidence}>
          {t(`chat.evidence.${answer.evidence}`)}
        </Badge>
      </div>
      <p className="text-sm leading-relaxed whitespace-pre-wrap break-words text-text-1">
        {answerSegments(answer.text, answer.citations).map((segment, index) =>
          segment.kind === "text" ? (
            <span key={index}>{segment.text}</span>
          ) : (
            <a key={index} href={`#source-${segment.number}`} className="mx-0.5 align-super font-mono text-[11px] text-data hover:underline" aria-label={t("chat.sourceNumber", { number: segment.number })}>
              [{segment.number}]
            </a>
          ),
        )}
      </p>
      {answer.caveats.length > 0 && (
        <div className="border-l-2 border-action pl-3" data-testid="chat-caveats">
          <p className="ui-eyebrow">{t("chat.caveats")}</p>
          <ul className="list-disc pl-4 text-sm text-text-2">
            {answer.caveats.map((caveat, index) => (
              <li key={index}>{caveat}</li>
            ))}
          </ul>
        </div>
      )}
      {answer.citations.length > 0 && (
        <div>
          <p className="ui-eyebrow">{t("chat.sources")}</p>
          <ol className="flex flex-col gap-2" data-testid="chat-sources">
            {answer.citations.map((citation) => (
              <Source key={citation.number} citation={citation} />
            ))}
          </ol>
        </div>
      )}
    </article>
  );
}

function Source({ citation }: { citation: ChatCitationDto }) {
  const { t } = useTranslation();
  const name = citation.title ?? t(`chat.sourceKind.${citation.kind}`);
  return (
    <li id={`source-${citation.number}`} className="flex flex-col gap-1 border-t border-line pt-2 text-sm" data-check={citation.check}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="font-mono text-xs text-text-4">[{citation.number}]</span>
        {citation.check === "SOURCE_UNAVAILABLE" ? (
          <span className="text-text-2">{name}</span>
        ) : (
          <Link to={citationTarget(citation)} className="text-data hover:underline">
            {name}
          </Link>
        )}
        {citation.page != null && <span className="font-mono text-xs text-text-3">{t("chat.page", { page: citation.page })}</span>}
        {citation.check !== "VERIFIED" && (
          <Badge tone={CHECK_TONES[citation.check] ?? "neutral"} data-check={citation.check}>
            {t(`chat.check.${citation.check}`)}
          </Badge>
        )}
      </div>
      {citation.quote && <blockquote className="border-l border-line pl-2 text-text-3 italic">„{citation.quote}“</blockquote>}
    </li>
  );
}

function Composer({ disabled, waiting = false, onSend, autoFocus = false }: { disabled: boolean; waiting?: boolean; onSend: (question: string) => Promise<boolean>; autoFocus?: boolean }) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const question = draft.trim();

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!question || disabled || sending) return;
    setSending(true);
    if (await onSend(question)) setDraft("");
    setSending(false);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  };

  return (
    <form className="sticky bottom-[calc(60px+env(safe-area-inset-bottom))] z-10 flex flex-col gap-2 border-t border-line bg-void py-2 lg:bottom-7" onSubmit={(event) => void submit(event)} data-testid="chat-composer">
      <label className="sr-only" htmlFor="chat-question">
        {t("chat.questionLabel")}
      </label>
      <textarea
        id="chat-question"
        className="field min-h-[72px] resize-y"
        rows={2}
        value={draft}
        maxLength={MAX_QUESTION}
        placeholder={waiting ? t("chat.placeholderWaiting") : t("chat.placeholder")}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKeyDown}
        autoFocus={autoFocus}
        data-testid="chat-input"
      />
      <div className="flex justify-end">
        <Button variant="primary" type="submit" disabled={!question || disabled || sending} data-testid="chat-send">
          {t("chat.send")}
        </Button>
      </div>
    </form>
  );
}
