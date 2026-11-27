package com.taskpilot.agent.executor.docker;

import com.taskpilot.agent.config.SandboxProperties;
import com.taskpilot.agent.model.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSandboxCommandExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void opensContainerWithResourceLimitsExecsCommandAndAutoCleansUp() throws Exception {
        List<List<String>> recordedCommands = new ArrayList<>();
        DockerCli fakeDocker = (args, timeout) -> {
            recordedCommands.add(args);
            if (args.contains("exec")) {
                return new CommandResult(0, "BUILD SUCCESS", "", false, 120);
            }
            return new CommandResult(0, "container-123", "", false, 20);
        };

        SandboxProperties props = new SandboxProperties();
        props.setMode("docker");
        props.setImage("taskpilot-sandbox:java21");
        props.setCpus("1.5");
        props.setMemory("1g");
        props.setPidsLimit(128);
        props.setNetworkMode("none");
        props.setMavenCacheVolume("taskpilot-m2");

        DockerSandboxCommandExecutor executor = new DockerSandboxCommandExecutor(props, fakeDocker);

        CommandResult execResult;
        try (AutoCloseable ignored = executor.openSession("JB-4", tempDir)) {
            execResult = executor.execute(tempDir, "mvn test", Duration.ofSeconds(60));
        }

        assertThat(execResult.isSuccess()).isTrue();
        assertThat(execResult.stdout()).isEqualTo("BUILD SUCCESS");
        assertThat(recordedCommands).hasSize(3);

        // 1. docker run with resource limits & volume mounts
        List<String> runCmd = recordedCommands.get(0);
        assertThat(runCmd).contains("docker", "run", "-d", "--cpus", "1.5", "--memory", "1g",
                "--pids-limit", "128", "--network", "none",
                tempDir.toAbsolutePath().normalize() + ":/workspace",
                "taskpilot-m2:/home/sandbox/.m2",
                "taskpilot-sandbox:java21");

        // 2. docker exec inside /workspace
        List<String> execCmd = recordedCommands.get(1);
        assertThat(execCmd).contains("docker", "exec", "-w", "/workspace", "bash", "-lc", "mvn test");

        // 3. docker rm -f cleanup on close
        List<String> rmCmd = recordedCommands.get(2);
        assertThat(rmCmd.get(0)).isEqualTo("docker");
        assertThat(rmCmd.get(1)).isEqualTo("rm");
        assertThat(rmCmd.get(2)).isEqualTo("-f");
        assertThat(rmCmd.get(3)).startsWith("taskpilot-jb-4-");
    }
}
