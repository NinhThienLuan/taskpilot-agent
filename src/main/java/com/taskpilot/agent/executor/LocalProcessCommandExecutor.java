package com.taskpilot.agent.executor;

import com.taskpilot.agent.model.CommandResult;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Local subprocess implementation of {@link CommandExecutor}.
 * Strips sensitive environment variables (API keys, Git/Jira tokens) before spawning commands.
 */
@Component
public class LocalProcessCommandExecutor implements CommandExecutor {

    private static final Set<String> SENSITIVE_ENV_FRAGMENTS = Set.of(
            "API_KEY", "SECRET", "TOKEN", "PASSWORD", "CREDENTIAL", "PRIVATE_KEY"
    );

    @Override
    public CommandResult execute(Path workspacePath, String command, Duration timeout) {
        long startNanos = System.nanoTime();
        List<String> shellCommand = isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("bash", "-lc", command);

        ProcessBuilder pb = new ProcessBuilder(shellCommand);
        pb.directory(workspacePath.toFile());
        scrubSensitiveEnv(pb.environment());

        try {
            Process process = pb.start();
            CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return new CommandResult(
                        -1,
                        safeJoin(stdoutFuture),
                        safeJoin(stderrFuture) + "\nCommand timed out after " + timeout.toSeconds() + "s",
                        true,
                        durationMs
                );
            }

            return new CommandResult(
                    process.exitValue(),
                    safeJoin(stdoutFuture),
                    safeJoin(stderrFuture),
                    false,
                    durationMs
            );
        } catch (IOException e) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new CommandResult(-1, "", "Failed to execute command: " + e.getMessage(), false, durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new CommandResult(-1, "", "Command execution interrupted", false, durationMs);
        }
    }

    static void scrubSensitiveEnv(Map<String, String> env) {
        env.keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            for (String fragment : SENSITIVE_ENV_FRAGMENTS) {
                if (upper.contains(fragment)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String safeJoin(CompletableFuture<String> future) {
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
