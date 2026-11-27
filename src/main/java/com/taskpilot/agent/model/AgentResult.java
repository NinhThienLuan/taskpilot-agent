package com.taskpilot.agent.model;

import java.util.List;

public record AgentResult(
        Status status,
        String summary,
        int iterations,
        TokenUsageSummary tokenUsage,
        List<String> stepLogs,
        CommandResult verificationTestResult,
        String gitDiffStat
) {
    public enum Status {
        SUCCEEDED,
        MAX_ITERATIONS_REACHED,
        TOKEN_BUDGET_EXCEEDED,
        STUCK_LOOP,
        VERIFICATION_FAILED,
        FAILED
    }
}
