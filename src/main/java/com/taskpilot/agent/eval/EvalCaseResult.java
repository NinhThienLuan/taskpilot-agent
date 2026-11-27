package com.taskpilot.agent.eval;

import com.taskpilot.agent.model.TokenUsageSummary;

public record EvalCaseResult(
        String caseId,
        String difficulty,
        String category,
        boolean passed,
        FailureReason failureReason,
        boolean regressionTestsPassed,
        boolean antiRewardHackingClean,
        boolean hiddenTestsPassed,
        int iterations,
        long durationMs,
        TokenUsageSummary tokenUsage,
        String details
) {
    public enum FailureReason {
        NONE,
        AGENT_FAILED,
        MAX_ITERATIONS,
        TOKEN_BUDGET_EXCEEDED,
        STUCK_LOOP,
        REGRESSION_FAIL,
        REWARD_HACKING,
        HIDDEN_TEST_FAIL
    }
}
