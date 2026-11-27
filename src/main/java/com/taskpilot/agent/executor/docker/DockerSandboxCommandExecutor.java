package com.taskpilot.agent.executor.docker;

import com.taskpilot.agent.config.SandboxProperties;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.executor.SandboxLifecycle;
import com.taskpilot.agent.model.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Docker-isolated implementation of {@link CommandExecutor} and {@link SandboxLifecycle}.
 * Active when {@code taskpilot.sandbox.mode=docker}.
 * Runs untrusted build/test commands inside a resource-limited container with no host secrets
 * and removes the container automatically when the job session closes.
 */
@Component
@ConditionalOnProperty(prefix = "taskpilot.sandbox", name = "mode", havingValue = "docker")
public class DockerSandboxCommandExecutor implements CommandExecutor, SandboxLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxCommandExecutor.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(15);

    private final SandboxProperties properties;
    private final DockerCli dockerCli;
    private final ThreadLocal<String> activeContainer = new ThreadLocal<>();

    @Autowired
    public DockerSandboxCommandExecutor(SandboxProperties properties) {
        this(properties, DockerCli.systemDefault());
    }

    DockerSandboxCommandExecutor(SandboxProperties properties, DockerCli dockerCli) {
        this.properties = properties;
        this.dockerCli = dockerCli;
    }

    @Override
    public AutoCloseable openSession(String jobId, Path workspacePath) {
        String containerName = buildContainerName(jobId);
        List<String> runArgs = buildDockerRunArgs(containerName, workspacePath);

        log.info("Starting Docker sandbox container {} for workspace {}", containerName, workspacePath);
        CommandResult startResult = dockerCli.run(runArgs, STARTUP_TIMEOUT);
        if (!startResult.isSuccess()) {
            throw new IllegalStateException(
                    "Failed to start Docker sandbox container '" + containerName + "': "
                            + startResult.combinedOutput()
            );
        }

        activeContainer.set(containerName);
        return () -> {
            activeContainer.remove();
            removeContainer(containerName);
        };
    }

    @Override
    public CommandResult execute(Path workspacePath, String command, Duration timeout) {
        String existingContainer = activeContainer.get();
        if (existingContainer != null) {
            return execInsideContainer(existingContainer, command, timeout);
        }

        // Fallback: ephemeral single-command container if execute() is called outside openSession()
        try (AutoCloseable ignored = openSession("ephemeral", workspacePath)) {
            return execInsideContainer(activeContainer.get(), command, timeout);
        } catch (Exception e) {
            return new CommandResult(-1, "", "Docker sandbox execution failed: " + e.getMessage(), false, 0);
        }
    }

    List<String> buildDockerRunArgs(String containerName, Path workspacePath) {
        String hostPath = workspacePath.toAbsolutePath().normalize().toString();
        List<String> args = new ArrayList<>();
        args.add("docker");
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(containerName);
        args.add("--cpus");
        args.add(properties.getCpus());
        args.add("--memory");
        args.add(properties.getMemory());
        args.add("--pids-limit");
        args.add(String.valueOf(properties.getPidsLimit()));
        args.add("--network");
        args.add(properties.getNetworkMode());
        args.add("-v");
        args.add(hostPath + ":/workspace");
        if (properties.getMavenCacheVolume() != null && !properties.getMavenCacheVolume().isBlank()) {
            args.add("-v");
            args.add(properties.getMavenCacheVolume().trim() + ":/home/sandbox/.m2");
        }
        args.add("-w");
        args.add("/workspace");
        args.add(properties.getImage());
        args.add("sleep");
        args.add("infinity");
        return args;
    }

    private CommandResult execInsideContainer(String containerName, String command, Duration timeout) {
        List<String> execArgs = List.of(
                "docker", "exec", "-w", "/workspace", containerName, "bash", "-lc", command
        );
        return dockerCli.run(execArgs, timeout);
    }

    private void removeContainer(String containerName) {
        log.info("Cleaning up Docker sandbox container {}", containerName);
        CommandResult rmResult = dockerCli.run(List.of("docker", "rm", "-f", containerName), CLEANUP_TIMEOUT);
        if (!rmResult.isSuccess()) {
            log.warn("Failed to remove container {}: {}", containerName, rmResult.combinedOutput());
        }
    }

    private static String buildContainerName(String jobId) {
        String sanitized = (jobId == null ? "job" : jobId)
                .toLowerCase()
                .replaceAll("[^a-z0-9-]", "-");
        if (sanitized.length() > 24) {
            sanitized = sanitized.substring(0, 24);
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "taskpilot-" + sanitized + "-" + suffix;
    }
}
