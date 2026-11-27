package com.taskpilot.agent.cli;

import com.taskpilot.agent.core.LocalPipelineRunner;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(prefix = "taskpilot.run", name = "enabled", havingValue = "true")
public class TaskPilotCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskPilotCliRunner.class);

    private final LocalPipelineRunner pipelineRunner;
    private final String ticketId;
    private final String repoPath;
    private final String taskDescription;
    private final String extraPrompt;
    private final String testCommand;

    public TaskPilotCliRunner(
            LocalPipelineRunner pipelineRunner,
            @Value("${taskpilot.run.ticket-id:LOCAL-1}") String ticketId,
            @Value("${taskpilot.run.repo}") String repoPath,
            @Value("${taskpilot.run.task}") String taskDescription,
            @Value("${taskpilot.run.extra-prompt:}") String extraPrompt,
            @Value("${taskpilot.run.test-command:mvn test}") String testCommand
    ) {
        this.pipelineRunner = pipelineRunner;
        this.ticketId = ticketId;
        this.repoPath = repoPath;
        this.taskDescription = taskDescription;
        this.extraPrompt = extraPrompt;
        this.testCommand = testCommand;
    }

    @Override
    public void run(String... args) {
        TaskRequest request = new TaskRequest(
                ticketId,
                taskDescription,
                extraPrompt,
                Path.of(repoPath).toAbsolutePath().normalize(),
                testCommand
        );

        AgentResult result = pipelineRunner.execute(request);

        log.info("=== TaskPilot MVP Result ===");
        log.info("Status     : {}", result.status());
        log.info("Iterations : {}", result.iterations());
        log.info("Tokens     : prompt={}, completion={}, total={}",
                result.tokenUsage().promptTokens(),
                result.tokenUsage().generationTokens(),
                result.tokenUsage().totalTokens());
        log.info("Steps      :\n{}", String.join("\n", result.stepLogs()));
        log.info("Git Diff   :\n{}", result.gitDiffStat());
        log.info("Summary    :\n{}", result.summary());
    }
}
