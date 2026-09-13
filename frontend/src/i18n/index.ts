import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import de from "./de.json";
import en from "./en.json";

export type ProductLocale = "DE" | "EN";
export const SUPPORTED: ProductLocale[] = ["DE", "EN"];
const STORAGE_KEY = "aegis.locale";

export function storedLocale(): ProductLocale {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    if (value === "DE" || value === "EN") return value;
  } catch {
    // storage unavailable (private mode); fall back to the product default
  }
  return "DE";
}

export function rememberLocale(locale: ProductLocale) {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // ignore
  }
}

export function toLanguage(locale: ProductLocale): "de" | "en" {
  return locale === "EN" ? "en" : "de";
}

export function applyLocale(locale: ProductLocale) {
  rememberLocale(locale);
  void i18n.changeLanguage(toLanguage(locale));
  document.documentElement.lang = toLanguage(locale);
}

void i18n.use(initReactI18next).init({
  resources: { de: { translation: de }, en: { translation: en } },
  lng: toLanguage(storedLocale()),
  fallbackLng: "de",
  interpolation: { escapeValue: false },
  returnEmptyString: false,
});

export default i18n;
