package dev.aegis.shared;

/** Product locales. Stored as stable enum values; presentation is localized at the boundary. */
public enum ProductLocale {
    DE,
    EN;

    public static ProductLocale defaultLocale() {
        return DE;
    }
}
