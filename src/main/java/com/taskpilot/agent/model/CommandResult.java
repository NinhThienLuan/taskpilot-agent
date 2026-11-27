package com.taskpilot.agent.model;

public record CommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        long durationMs
) {
    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }

    public String combinedOutput() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isBlank()) {
            sb.append(stdout);
        }
        if (stderr != null && !stderr.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n--- STDERR ---\n");
            }
            sb.append(stderr);
        }
        return sb.toString();
    }
}
