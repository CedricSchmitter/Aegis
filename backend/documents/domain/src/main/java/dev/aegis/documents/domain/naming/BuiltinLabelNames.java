package dev.aegis.documents.domain.naming;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Names of the built-in document types and topics in generated filenames. Filenames use one household naming
 * language (configuration), not the viewer's UI language, so switching the UI language never renames files. The UI
 * renders built-in labels from its own locale catalogs; these values match the seeded German and English aliases.
 */
public final class BuiltinLabelNames {
    public enum Language {
        DE, EN;

        public static Language parse(String value) {
            return value == null ? DE : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static final Map<String, String[]> NAMES = Map.ofEntries(
            Map.entry("invoice", new String[] {"Rechnung", "Invoice"}),
            Map.entry("contract", new String[] {"Vertrag", "Contract"}),
            Map.entry("insurancePolicy", new String[] {"Versicherungspolice", "Insurance policy"}),
            Map.entry("accountStatement", new String[] {"Kontoauszug", "Account statement"}),
            Map.entry("officialNotice", new String[] {"Bescheid", "Official notice"}),
            Map.entry("letter", new String[] {"Schreiben", "Letter"}),
            Map.entry("receipt", new String[] {"Beleg", "Receipt"}),
            Map.entry("paymentReminder", new String[] {"Mahnung", "Payment reminder"}),
            Map.entry("certificate", new String[] {"Bescheinigung", "Certificate"}),
            Map.entry("warranty", new String[] {"Garantie", "Warranty"}),
            Map.entry("manual", new String[] {"Anleitung", "Manual"}),
            Map.entry("payslip", new String[] {"Gehaltsabrechnung", "Payslip"}),
            Map.entry("taxDocument", new String[] {"Steuerunterlage", "Tax document"}),
            Map.entry("cancellation", new String[] {"Kündigung", "Cancellation"}),
            Map.entry("medicalDocument", new String[] {"Arztbericht", "Medical document"}),
            Map.entry("internet", new String[] {"Internet", "Internet"}),
            Map.entry("phone", new String[] {"Telefon", "Phone"}),
            Map.entry("electricity", new String[] {"Strom", "Electricity"}),
            Map.entry("gas", new String[] {"Gas", "Gas"}),
            Map.entry("heating", new String[] {"Heizung", "Heating"}),
            Map.entry("water", new String[] {"Wasser", "Water"}),
            Map.entry("waste", new String[] {"Abfall", "Waste"}),
            Map.entry("insurance", new String[] {"Versicherung", "Insurance"}),
            Map.entry("taxes", new String[] {"Steuern", "Taxes"}),
            Map.entry("banking", new String[] {"Bank", "Banking"}),
            Map.entry("health", new String[] {"Gesundheit", "Health"}),
            Map.entry("car", new String[] {"Auto", "Car"}),
            Map.entry("housing", new String[] {"Wohnen", "Housing"}),
            Map.entry("work", new String[] {"Arbeit", "Work"}),
            Map.entry("purchases", new String[] {"Anschaffungen", "Purchases"}),
            Map.entry("broadcastingFee", new String[] {"Rundfunkbeitrag", "Broadcasting fee"}),
            Map.entry("pension", new String[] {"Vorsorge", "Pension"}),
            Map.entry("travel", new String[] {"Reisen", "Travel"}),
            Map.entry("education", new String[] {"Bildung", "Education"}),
            Map.entry("pets", new String[] {"Haustiere", "Pets"}));

    private BuiltinLabelNames() {
    }

    /** The name of a built-in key in the naming language; unknown keys fall back to the key itself. */
    public static String name(String builtinKey, Language language) {
        return Optional.ofNullable(NAMES.get(builtinKey)).map(names -> names[language.ordinal()]).orElse(builtinKey);
    }
}
