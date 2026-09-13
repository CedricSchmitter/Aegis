import type { TFunction } from "i18next";
import { toApiError } from "../api/client";

/** Localizes an API failure by its stable code; unknown codes fall back to a generic message. */
export function problemMessage(t: TFunction, error: unknown): string {
  const apiError = toApiError(error);
  const key = `errors.${apiError.code}`;
  return t(key, { defaultValue: t("errors.generic") });
}
