package dev.aegis.shared;

import java.util.Objects;
import java.util.UUID;

/** Stable Aegis identity of a contract; other contexts link to it without knowing the contracts model. */
public record ContractId(UUID value) {
    public ContractId {
        Objects.requireNonNull(value, "contract id");
    }

    public static ContractId random() {
        return new ContractId(UUID.randomUUID());
    }

    public static ContractId of(String value) {
        try {
            return new ContractId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid ContractId value");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
