package com.taskpilot.agent.executor;

import com.taskpilot.agent.model.CommandResult;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Abstraction for executing shell commands inside a job workspace.
 * Phase 3 uses {@link LocalProcessCommandExecutor}; Phase 2 swaps in a Docker sandbox implementation
 * without changing {@code WorkspaceTools} or {@code CodingAgent}.
 */
public interface CommandExecutor {

    CommandResult execute(Path workspacePath, String command, Duration timeout);
}
