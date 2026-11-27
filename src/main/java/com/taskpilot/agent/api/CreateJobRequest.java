package com.taskpilot.agent.api;

import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank(message = "ticketId is required") String ticketId,
        @NotBlank(message = "taskDescription is required") String taskDescription,
        String extraPrompt,
        @NotBlank(message = "repoPath is required") String repoPath,
        String testCommand
) {
}
