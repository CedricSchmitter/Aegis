import { useTranslation } from "react-i18next";

/** The fixed order a document goes through after upload. */
export const PROCESSING_STEPS = ["saved", "archived", "textRecognized", "classified"] as const;
export type ProcessingStep = (typeof PROCESSING_STEPS)[number];
export type StepState = "done" | "running" | "failed" | "pending";

/**
 * The state of each step when `completed` steps are done. The next step is running, failed or still waiting,
 * depending on `current`; the steps after it are pending.
 */
export function stepStates(completed: number, current: "running" | "failed" | "waiting"): StepState[] {
  const done = Math.min(Math.max(completed, 0), PROCESSING_STEPS.length);
  return PROCESSING_STEPS.map((_, index) => {
    if (index < done) return "done";
    if (index === done) return current === "waiting" ? "pending" : current;
    return "pending";
  });
}

type ProcessingStepsProps = {
  /** Number of steps already done, 0 to 4. */
  completed: number;
  /** What happens with the next step. */
  current?: "running" | "failed" | "waiting";
};

/** Gespeichert → Archiviert → Text erkannt → Eingeordnet, each with its state readable as text. */
export function ProcessingSteps({ completed, current = "running" }: ProcessingStepsProps) {
  const { t } = useTranslation();
  const states = stepStates(completed, current);
  return (
    <ol className="ui-steps" aria-label={t("ui.steps.label")}>
      {PROCESSING_STEPS.map((step, index) => (
        <li key={step} data-step={states[index]} aria-current={states[index] === "running" ? "step" : undefined}>
          <span>{t(`ui.steps.${step}`)}</span>
          <span className="sr-only">{t(`ui.steps.state.${states[index]}`)}</span>
        </li>
      ))}
    </ol>
  );
}
