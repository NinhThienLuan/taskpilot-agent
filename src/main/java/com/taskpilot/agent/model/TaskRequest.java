package com.taskpilot.agent.model;

import java.nio.file.Path;

public record TaskRequest(
        String ticketId,
        String taskDescription,
        String extraPrompt,
        Path repoPath,
        String testCommand
) {
    public TaskRequest {
        if (ticketId == null || ticketId.isBlank()) {
            ticketId = "LOCAL-1";
        }
        if (taskDescription == null || taskDescription.isBlank()) {
            throw new IllegalArgumentException("taskDescription must not be blank");
        }
        if (repoPath == null) {
            throw new IllegalArgumentException("repoPath must not be null");
        }
        if (extraPrompt == null) {
            extraPrompt = "";
        }
        if (testCommand == null) {
            testCommand = "";
        }
    }
}
