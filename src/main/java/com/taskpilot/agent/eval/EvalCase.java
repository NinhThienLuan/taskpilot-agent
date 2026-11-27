package com.taskpilot.agent.eval;

public record EvalCase(
        String id,
        String difficulty,
        String category,
        String repoTemplate,
        String taskDescription,
        String extraPrompt,
        String testCommand,
        String hiddenTestDir
) {
}
