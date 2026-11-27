package com.taskpilot.agent.harness;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Application-level denylist blocking destructive or privilege-escalating shell commands
 * before they reach {@code CommandExecutor}.
 */
public final class CommandGuard {

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$HOME)(\\s|$|/)"),
            Pattern.compile("(?i)\\brm\\s+-[a-z]*f[a-z]*r[a-z]*\\s+(/|~|\\$HOME)(\\s|$|/)"),
            Pattern.compile("(?i)\\bsudo\\b"),
            Pattern.compile("(?i)\\b(shutdown|reboot|poweroff|mkfs|fdisk)\\b"),
            Pattern.compile("(?i)\\bdd\\s+if="),
            Pattern.compile("(?i)git\\s+push\\s+.*(--force|-f\\b)"),
            Pattern.compile("(?i)(curl|wget)\\s+.*\\|\\s*(ba)?sh\\b"),
            Pattern.compile(":\\(\\)\\s*\\{")
    );

    private CommandGuard() {
    }

    public static void validate(String command) {
        if (command == null || command.isBlank()) {
            throw new SecurityException("Command must not be blank");
        }
        String normalized = command.trim();
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                throw new SecurityException("Blocked dangerous command by Agent Harness: " + normalized);
            }
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("chmod 777 /")) {
            throw new SecurityException("Blocked dangerous command by Agent Harness: " + normalized);
        }
    }
}
