package com.taskpilot.agent.executor.docker;

import com.taskpilot.agent.model.CommandResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the {@code docker} CLI to keep {@link DockerSandboxCommandExecutor} unit-testable.
 */
public interface DockerCli {

    CommandResult run(List<String> args, Duration timeout);

    static DockerCli systemDefault() {
        return (args, timeout) -> {
            long startNanos = System.nanoTime();
            ProcessBuilder pb = new ProcessBuilder(args);
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
                            safeJoin(stderrFuture) + "\nDocker command timed out after " + timeout.toSeconds() + "s",
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
                return new CommandResult(-1, "", "Docker CLI error: " + e.getMessage(), false, durationMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                return new CommandResult(-1, "", "Docker CLI interrupted", false, durationMs);
            }
        };
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
}
