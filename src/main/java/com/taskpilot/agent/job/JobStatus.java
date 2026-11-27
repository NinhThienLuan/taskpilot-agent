package com.taskpilot.agent.job;

public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    VERIFICATION_FAILED,
    MAX_ITERATIONS_REACHED,
    FAILED
}
