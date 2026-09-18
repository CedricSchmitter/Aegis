package dev.aegis.assistant.domain;

/** Tokens reported by the provider for one or more model calls; thinking tokens are part of the output count. */
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens);
    }
}
