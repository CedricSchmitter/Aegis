package dev.aegis.assistant.application.api;

import java.math.BigDecimal;

/**
 * A model family the household can choose, with the model the API uses for it and its list prices per million tokens.
 * The owner's subscription uses the newest model of the family that Claude Code offers.
 */
public record ModelOptionView(String family, String model, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
}
