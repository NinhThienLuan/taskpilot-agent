package com.taskpilot.agent.executor;

import java.nio.file.Path;

/**
 * Optional per-job lifecycle hook for sandboxes.
 * In local mode this is a no-op; in Docker mode it starts a dedicated container
 * for the duration of the job and guarantees {@code docker rm -f} cleanup on close.
 */
public interface SandboxLifecycle {

    AutoCloseable openSession(String jobId, Path workspacePath);

    static SandboxLifecycle noop() {
        return (jobId, workspacePath) -> () -> { };
    }
}
