/**
 * Money input for the AI cost limits. The fields accept only an amount: digits with at most one decimal separator
 * (comma or point) and two decimals. Anything else, letters included, is not taken over while typing or pasting. The
 * server checks the same rules again (positive, whole cents, at most MAX_LIMIT, session not above daily).
 */
export const MAX_LIMIT = 1000;

/** What a field may contain while it is being typed: "", "12", "12,", "12,5", "0.25". */
const TYPING = /^\d{0,4}(?:[.,]\d{0,2})?$/;

/** Why entered limits cannot be saved; each has a message under ai.limits.problem. */
export const LIMIT_PROBLEMS = ["required", "notPositive", "tooHigh", "runAboveDaily"] as const;
export type LimitProblem = (typeof LIMIT_PROBLEMS)[number];

/** Whether `text` may stand in a money field; the caller keeps the previous value otherwise. */
export function acceptsMoneyInput(text: string): boolean {
  return TYPING.test(text);
}

/** The amount of a field, or null while it holds no number ("" or a lone separator). */
export function parseMoney(text: string): number | null {
  if (!acceptsMoneyInput(text) || !/\d/.test(text)) return null;
  return Number(text.replace(",", "."));
}

/** An amount as the field shows it: two decimals, the language's separator, no grouping ("0,25" / "0.25"). */
export function formatMoneyInput(amount: number, language: string): string {
  const fixed = amount.toFixed(2);
  return language === "en" ? fixed : fixed.replace(".", ",");
}

/** Problems of a daily and a session limit as entered, per field; empty when both can be saved. */
export function limitProblems(daily: string, run: string): { daily?: LimitProblem; run?: LimitProblem } {
  const problems: { daily?: LimitProblem; run?: LimitProblem } = {};
  const dailyAmount = parseMoney(daily);
  const runAmount = parseMoney(run);
  const check = (amount: number | null): LimitProblem | undefined =>
    amount == null ? "required" : amount <= 0 ? "notPositive" : amount > MAX_LIMIT ? "tooHigh" : undefined;
  const dailyProblem = check(dailyAmount);
  const runProblem = check(runAmount);
  if (dailyProblem) problems.daily = dailyProblem;
  if (runProblem) problems.run = runProblem;
  else if (!dailyProblem && dailyAmount != null && runAmount != null && runAmount > dailyAmount) problems.run = "runAboveDaily";
  return problems;
}
