package com.taskpilot.agent.job;

public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    VERIFICATION_FAILED,
    MAX_ITERATIONS_REACHED,
    TOKEN_BUDGET_EXCEEDED,
    STUCK_LOOP,
    REWARD_HACKING,
    FAILED
}
