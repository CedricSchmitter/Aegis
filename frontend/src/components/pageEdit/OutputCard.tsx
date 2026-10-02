import { useTranslation } from "react-i18next";
import type { CategoryDto } from "../../api/types";
import { useSession } from "../../auth/SessionContext";
import {
  detailsOf,
  firstSource,
  joinWithPrevious,
  LIMITS,
  rotatePage,
  setDetails,
  setTitle,
  sourceOf,
  splitBefore,
  titleOf,
  type PagePlan,
  type PlanPage,
  type TitleWords,
} from "../../pageEdit/plan";
import type { DropTarget } from "../../pageEdit/useDragReorder";
import type { ScanPage } from "../../scan/draft";
import { FolderSelect } from "../FolderSelect";
import { UploadSubjects } from "../UploadSubjects";
import { PageThumb } from "./PageThumb";

export interface DragControls {
  dragging: string | null;
  target: DropTarget | null;
  handleProps: (pageKey: string, disabled?: boolean) => object;
}

interface Props {
  plan: PagePlan;
  index: number;
  words: TitleWords;
  categories: CategoryDto[];
  scanPages: ScanPage[];
  disabled: boolean;
  drag: DragControls;
  onChange: (edit: (plan: PagePlan) => PagePlan) => void;
  onMove: (page: PlanPage, delta: -1 | 1) => void;
  onRemove: (page: PlanPage) => void;
  /** Opens a page large: a stored page to look at, a new page in the corner editor. */
  onOpen: (page: PlanPage) => void;
}

/**
 * One new document of the plan: its title, folder and subjects (prefilled from the source of its first page) and its
 * pages in order. Between two pages a marker splits the document; the header joins it with the previous one.
 */
export function OutputCard({ plan, index, words, categories, scanPages, disabled, drag, onChange, onMove, onRemove, onOpen }: Props) {
  const { t } = useTranslation();
  const { memberName } = useSession();
  const output = plan.outputs[index];
  const count = plan.outputs.length;
  const title = titleOf(plan, index, words);
  const details = detailsOf(plan, output);
  const titleId = `output-title-${output.key}`;
  const isFirstOutput = index === 0;
  const isLastOutput = index === count - 1;
  const folderName = details.categoryId
    ? (categories.find((category) => category.id === details.categoryId)?.name ?? "")
    : t("documents.unsorted");
  const subjectNames = details.subjects.map((subject) =>
    subject.startsWith("MEMBER:") ? (memberName(subject.substring(7)) ?? subject) : t(`subjects.${subject}`),
  );
  const startTarget = drag.target?.outputKey === output.key && drag.target.position === 0 && drag.dragging !== null;

  return (
    <section
      className="card flex flex-col gap-3"
      aria-labelledby={titleId}
      data-testid="page-edit-output"
      data-output-key={output.key}
    >
      <div
        className="relative flex flex-wrap items-center justify-between gap-2"
        data-drop-output={output.key}
        data-drop-position={0}
        data-drop-edge="exact"
      >
        <h2 id={titleId} className="ui-eyebrow">
          {t("pageEdit.output.heading", { number: index + 1, count })} ·{" "}
          {t("pageEdit.output.pages", { count: output.pages.length })}
        </h2>
        {!isFirstOutput && (
          <button
            type="button"
            className="btn-ghost -mr-3"
            disabled={disabled}
            onClick={() => onChange((current) => joinWithPrevious(current, output.key))}
            aria-label={t("pageEdit.output.joinLabel", { number: index + 1, previous: index })}
            data-testid="page-edit-join"
          >
            {t("pageEdit.output.join")}
          </button>
        )}
        {startTarget && <DropLine edge="bottom" />}
      </div>

      <label className="block" htmlFor={`${titleId}-input`}>
        <span className="label">{t("pageEdit.output.title")}</span>
        <input
          id={`${titleId}-input`}
          className="field"
          value={title}
          maxLength={255}
          disabled={disabled}
          onChange={(event) => onChange((current) => setTitle(current, output.key, event.target.value))}
          data-testid="page-edit-title"
        />
      </label>
      {output.title === null ? (
        <p className="-mt-2 text-xs text-text-3">{t("pageEdit.output.titleSuggested")}</p>
      ) : (
        <div className="-mt-2">
          <button
            type="button"
            className="btn-ghost -ml-4 text-xs"
            disabled={disabled}
            onClick={() =>
              onChange((current) => ({
                ...current,
                outputs: current.outputs.map((entry) => (entry.key === output.key ? { ...entry, title: null } : entry)),
              }))
            }
          >
            {t("pageEdit.output.titleReset")}
          </button>
        </div>
      )}

      <details className="text-sm" data-testid="page-edit-details">
        <summary className="flex min-h-11 cursor-pointer items-center gap-2 text-text-2">
          <span className="font-medium">{t("pageEdit.output.details")}</span>
          <span className="min-w-0 truncate text-text-3">
            {[folderName, ...subjectNames].join(" · ")}
          </span>
        </summary>
        <div className="flex flex-col gap-3 pt-2">
          {!output.details && firstSource(plan, output) && (
            <p className="text-xs text-text-3">{t("pageEdit.output.detailsFromSource")}</p>
          )}
          <FolderSelect
            categories={categories}
            value={details.categoryId}
            onChange={(categoryId) => onChange((current) => setDetails(current, output.key, { ...details, categoryId }))}
            label={t("documents.uploadFolder")}
            id={`${titleId}-folder`}
          />
          <UploadSubjects
            value={details.subjects}
            onChange={(subjects) => onChange((current) => setDetails(current, output.key, { ...details, subjects }))}
            testIdPrefix={`page-edit-${index}`}
          />
        </div>
      </details>

      <ol className="flex flex-col" aria-label={t("pageEdit.output.pageList", { number: index + 1 })}>
        {output.pages.map((page, position) => (
          <PageRow
            key={page.key}
            plan={plan}
            page={page}
            position={position}
            outputKey={output.key}
            outputNumber={index + 1}
            scanPage={page.source === "SCAN" ? scanPages.find((entry) => entry.id === page.scanPageId) : undefined}
            first={isFirstOutput && position === 0}
            last={isLastOutput && position === output.pages.length - 1}
            splittable={position < output.pages.length - 1}
            canSplit={count < LIMITS.outputs}
            disabled={disabled}
            drag={drag}
            onSplit={() => onChange((current) => splitBefore(current, output.key, position + 1))}
            onMove={(delta) => onMove(page, delta)}
            onRotate={() => onChange((current) => rotatePage(current, page.key))}
            onRemove={() => onRemove(page)}
            onOpen={() => onOpen(page)}
          />
        ))}
      </ol>
    </section>
  );
}

interface RowProps {
  plan: PagePlan;
  page: PlanPage;
  position: number;
  outputKey: string;
  outputNumber: number;
  scanPage?: ScanPage;
  first: boolean;
  last: boolean;
  splittable: boolean;
  canSplit: boolean;
  disabled: boolean;
  drag: DragControls;
  onSplit: () => void;
  onMove: (delta: -1 | 1) => void;
  onRotate: () => void;
  onRemove: () => void;
  onOpen: () => void;
}

function PageRow(props: RowProps) {
  const { plan, page, position, outputKey, outputNumber, scanPage, first, last, splittable, canSplit, disabled, drag } = props;
  const { t } = useTranslation();
  const number = position + 1;
  const source = page.source === "DOCUMENT" ? sourceOf(plan, page.documentId) : undefined;
  const origin =
    page.source === "DOCUMENT"
      ? t("pageEdit.page.fromDocument", { title: source?.title ?? "", page: page.pageIndex + 1 })
      : t("pageEdit.page.newPage");
  const processing = page.source === "SCAN" && !scanPage?.result;
  const dragged = drag.dragging === page.key;
  const aimed = drag.dragging !== null && drag.target?.outputKey === outputKey;
  const lineBefore = aimed && drag.target?.position === position;
  const lineAfter = aimed && drag.target?.position === position + 1 && !splittable;

  return (
    <li className="flex flex-col" data-testid="page-edit-page" data-page-key={page.key} data-rotation={page.rotation}>
      <div
        className={`relative flex items-start gap-2 py-2 ${dragged ? "opacity-40" : ""}`}
        data-drop-output={outputKey}
        data-drop-position={position}
      >
        {lineBefore && <DropLine edge="top" />}
        <button
          type="button"
          className="flex h-20 w-8 shrink-0 cursor-grab touch-none items-center justify-center text-text-3 select-none active:cursor-grabbing disabled:opacity-40"
          aria-label={t("pageEdit.page.drag", { number })}
          title={t("pageEdit.page.drag", { number })}
          disabled={disabled}
          data-testid="page-edit-drag"
          {...drag.handleProps(page.key, disabled)}
        >
          <svg viewBox="0 0 12 20" width="12" height="20" aria-hidden="true" fill="currentColor">
            {[3, 9].map((x) => [4, 10, 16].map((y) => <circle key={`${x}-${y}`} cx={x} cy={y} r="1.6" />))}
          </svg>
        </button>
        <button
          type="button"
          className="shrink-0"
          onClick={props.onOpen}
          disabled={disabled || processing}
          aria-label={
            page.source === "SCAN"
              ? t("pageEdit.page.editCorners", { number })
              : t("pageEdit.page.show", { number })
          }
          data-testid="page-edit-open"
        >
          <PageThumb page={page} scanPage={scanPage} />
        </button>
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="min-w-0">
            <p className="text-sm font-medium">{t("pageEdit.page.number", { number, output: outputNumber })}</p>
            <p className="truncate text-xs text-text-3" title={origin}>
              {origin}
              {page.rotation !== 0 && ` · ${t("pageEdit.page.rotated", { degrees: page.rotation })}`}
            </p>
          </div>
          <div className="flex flex-wrap gap-1">
            <RowButton label={t("pageEdit.page.moveEarlier", { number })} disabled={disabled || first} onClick={() => props.onMove(-1)} testId="page-edit-earlier">
              ↑
            </RowButton>
            <RowButton label={t("pageEdit.page.moveLater", { number })} disabled={disabled || last} onClick={() => props.onMove(1)} testId="page-edit-later">
              ↓
            </RowButton>
            <RowButton label={t("pageEdit.page.rotate", { number })} disabled={disabled} onClick={props.onRotate} testId="page-edit-rotate">
              ↻
            </RowButton>
            <RowButton label={t("pageEdit.page.remove", { number })} disabled={disabled} onClick={props.onRemove} testId="page-edit-remove">
              ✕
            </RowButton>
          </div>
        </div>
        {lineAfter && <DropLine edge="bottom" />}
      </div>
      {splittable && (
        <div data-drop-output={outputKey} data-drop-position={position + 1} data-drop-edge="exact">
          <button
            type="button"
            className="ui-split"
            disabled={disabled || !canSplit}
            onClick={props.onSplit}
            aria-label={t("pageEdit.page.splitLabel", { number, next: number + 1 })}
            data-testid="page-edit-split"
          >
            <span aria-hidden="true">✂</span>
            {t("pageEdit.page.split")}
          </button>
        </div>
      )}
    </li>
  );
}

function RowButton({
  label,
  disabled,
  onClick,
  testId,
  children,
}: {
  label: string;
  disabled?: boolean;
  onClick: () => void;
  testId: string;
  children: string;
}) {
  return (
    <button
      type="button"
      className="btn-secondary h-11 w-11 min-w-0 px-0"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      data-testid={testId}
    >
      <span aria-hidden="true">{children}</span>
    </button>
  );
}

/** Where a dragged page would land. */
function DropLine({ edge }: { edge: "top" | "bottom" }) {
  return (
    <span
      className={`pointer-events-none absolute inset-x-0 h-0.5 bg-data ${edge === "top" ? "-top-px" : "-bottom-px"}`}
      aria-hidden="true"
      data-testid="page-edit-drop-line"
    />
  );
}
