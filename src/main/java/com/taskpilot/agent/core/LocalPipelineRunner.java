package com.taskpilot.agent.core;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.executor.SandboxLifecycle;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.CommandResult;
import com.taskpilot.agent.model.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic MVP orchestrator wrapping Step 2 (Sandbox session), Step 3 (CodingAgent),
 * Step 4 (independent test verification), and Step 5 preview (git diff stat).
 */
@Service
public class LocalPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalPipelineRunner.class);

    private final CodingAgent codingAgent;
    private final CommandExecutor commandExecutor;
    private final SandboxLifecycle sandboxLifecycle;
    private final AgentProperties properties;

    public LocalPipelineRunner(
            CodingAgent codingAgent,
            CommandExecutor commandExecutor,
            SandboxLifecycle sandboxLifecycle,
            AgentProperties properties
    ) {
        this.codingAgent = codingAgent;
        this.commandExecutor = commandExecutor;
        this.sandboxLifecycle = sandboxLifecycle;
        this.properties = properties;
    }

    public AgentResult execute(TaskRequest request) {
        List<String> logs = new ArrayList<>();
        logs.add("[Step 2] Opening sandbox session for " + request.ticketId());

        try (AutoCloseable ignored = sandboxLifecycle.openSession(request.ticketId(), request.repoPath())) {
            logs.add("[Step 3] Starting AI Coding Loop for " + request.ticketId());
            AgentResult agentResult = codingAgent.runCodingLoop(request);
            logs.addAll(agentResult.stepLogs());

            CommandResult verifyResult = null;
            AgentResult.Status finalStatus = agentResult.status();

            if (!request.testCommand().isBlank()) {
                log.info("[{}] [Step 4] Running independent verification test: {}", request.ticketId(), request.testCommand());
                verifyResult = commandExecutor.execute(
                        request.repoPath(),
                        request.testCommand(),
                        properties.getCommandTimeout()
                );
                logs.add("[Step 4] Verification exitCode=" + verifyResult.exitCode()
                        + ", durationMs=" + verifyResult.durationMs());
                if (!verifyResult.isSuccess() && finalStatus == AgentResult.Status.SUCCEEDED) {
                    finalStatus = AgentResult.Status.VERIFICATION_FAILED;
                }
            }

            CommandResult diffResult = commandExecutor.execute(
                    request.repoPath(),
                    "git status --short && git diff --stat",
                    Duration.ofSeconds(15)
            );
            String diffStat = diffResult.combinedOutput();

            return new AgentResult(
                    finalStatus,
                    agentResult.summary(),
                    agentResult.iterations(),
                    agentResult.tokenUsage(),
                    List.copyOf(logs),
                    verifyResult,
                    diffStat
            );
        } catch (Exception e) {
            log.error("[{}] Pipeline execution failed", request.ticketId(), e);
            logs.add("[ERROR] " + e.getMessage());
            return new AgentResult(
                    AgentResult.Status.FAILED,
                    "Pipeline failed: " + e.getMessage(),
                    0,
                    com.taskpilot.agent.model.TokenUsageSummary.ZERO,
                    List.copyOf(logs),
                    null,
                    ""
            );
        }
    }
}
