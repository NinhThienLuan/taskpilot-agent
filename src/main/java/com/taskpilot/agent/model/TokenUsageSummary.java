package com.taskpilot.agent.model;

public record TokenUsageSummary(
        long promptTokens,
        long generationTokens,
        long totalTokens
) {
    public static final TokenUsageSummary ZERO = new TokenUsageSummary(0, 0, 0);

    public TokenUsageSummary add(long prompt, long generation, long total) {
        long nextPrompt = this.promptTokens + Math.max(0, prompt);
        long nextGeneration = this.generationTokens + Math.max(0, generation);
        long computedTotal = total > 0 ? total : (Math.max(0, prompt) + Math.max(0, generation));
        return new TokenUsageSummary(nextPrompt, nextGeneration, this.totalTokens + computedTotal);
    }
}
