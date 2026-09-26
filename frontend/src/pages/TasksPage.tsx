import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { TaskDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { BigTitle, EmptyState, ErrorBox, Loading } from "../components/common";
import { ContextPanel, usePanelShown } from "../components/shell/ContextPanel";
import { Icon } from "../components/shell/icons";
import { useDesktop } from "../components/shell/ShellData";
import { TaskForm } from "../components/TaskForm";
import { useCompletionToast } from "../components/tasks/CompletionToast";
import { TaskCard } from "../components/tasks/TaskCard";
import { TaskDetail } from "../components/tasks/TaskDetail";
import { SegmentedControl } from "../components/ui";
import { problemMessage } from "../lib/problem";
import { EVERYONE, NOBODY, filterByOwner, sortTasks } from "../lib/tasks";

type View = "open" | "all";

/**
 * The task list (issue #27): open or all, filtered by responsibility as a rail. The checkbox completes a task at
 * once. From 1280 px a click selects a task and shows it in the context panel without leaving the list.
 */
export function TasksPage() {
  const { t } = useTranslation();
  const { members } = useSession();
  const desktop = useDesktop();
  const panelShown = usePanelShown();
  const [tasks, setTasks] = useState<TaskDto[] | null>(null);
  const [view, setView] = useState<View>("open");
  const [owner, setOwner] = useState(EVERYONE);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTasks(unwrap(await api.GET("/api/tasks", { params: { query: { includeDone: view === "all" } } })));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [view, t]);

  useEffect(() => {
    void load();
  }, [load]);

  // A task stays in the list until the next load, so a completed one can still be reopened from its card; trashed
  // tasks leave at once.
  const replace = useCallback(
    (updated: TaskDto) =>
      setTasks((current) =>
        (current ?? []).flatMap((task) => (task.id !== updated.id ? [task] : updated.trash ? [] : [updated])),
      ),
    [],
  );
  const { notify, toast } = useCompletionToast(replace, setError);

  const visible = useMemo(() => (tasks ? sortTasks(filterByOwner(tasks, owner)) : []), [tasks, owner]);
  const openCount = tasks?.filter((task) => task.status === "OPEN").length ?? 0;
  const selected = panelShown ? (visible.find((task) => task.id === selectedId) ?? visible[0] ?? null) : null;
  const owners = [
    { id: EVERYONE, name: t("tasks.all") },
    ...members.map((member) => ({ id: member.id, name: member.displayName })),
    { id: NOBODY, name: t("tasks.nobodyShort") },
  ];

  return (
    <>
      <BigTitle count={tasks && t("tasks.openCount", { count: openCount })}>{t("tasks.title")}</BigTitle>
      <div className="flex flex-col gap-4">
        <button
          type="button"
          className={`btn-primary ${desktop ? "self-start" : "w-full"}`}
          aria-expanded={showForm}
          onClick={() => setShowForm((value) => !value)}
          data-testid="new-task-toggle"
        >
          <Icon name="plus" className="h-[18px] w-[18px]" />
          {t("tasks.new")}
        </button>
        {showForm && (
          <TaskForm
            onCreated={(task) => {
              setTasks((current) => [task, ...(current ?? [])]);
              setSelectedId(task.id);
              setShowForm(false);
            }}
          />
        )}
        <div className={desktop ? "max-w-80" : undefined}>
          <SegmentedControl<View>
            label={t("tasks.view")}
            options={[
              { value: "open", label: t("tasks.open") },
              { value: "all", label: t("tasks.all") },
            ]}
            value={view}
            onChange={setView}
          />
        </div>
        <div className={`ui-rail ${desktop ? "lg:mx-0 lg:flex-wrap lg:px-0" : ""}`} role="group" aria-label={t("tasks.owner")} data-testid="owner-rail">
          {owners.map((entry) => (
            <button key={entry.id} type="button" className="ui-pill" aria-pressed={owner === entry.id} onClick={() => setOwner(entry.id)}>
              {entry.name}
            </button>
          ))}
        </div>

        <ErrorBox message={error} />
        {!tasks && !error && <Loading />}
        {tasks && visible.length === 0 && <EmptyState>{tasks.length === 0 && view === "open" ? t("tasks.empty") : t("tasks.emptyView")}</EmptyState>}
        {visible.length > 0 && (
          <ul className="flex flex-col gap-2" aria-label={t("tasks.title")}>
            {visible.map((task) => (
              <TaskCard
                key={task.id}
                task={task}
                onChange={replace}
                onCompleted={notify}
                selected={selected?.id === task.id}
                onSelect={panelShown ? setSelectedId : null}
              />
            ))}
          </ul>
        )}
        {!desktop && (
          <Link to="/trash" className="ui-endlink" data-testid="open-trash">
            <Icon name="trash" className="h-4 w-4" />
            {t("trash.open")}
          </Link>
        )}
      </div>
      {selected && (
        <ContextPanel>
          <TaskDetail
            key={selected.id}
            task={selected}
            onChange={replace}
            onCompleted={notify}
            heading={(title) => <h2 className="text-lg leading-tight font-bold break-words">{title}</h2>}
          />
        </ContextPanel>
      )}
      {toast}
    </>
  );
}
