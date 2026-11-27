package com.taskpilot.agent.core;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.TaskRequest;
import com.taskpilot.agent.model.TokenUsageSummary;
import com.taskpilot.agent.tools.WorkspaceTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 3 (AI Coding Loop) implemented as an Explicit ReAct Loop using Spring AI's {@link ToolCallingManager}
 * with {@code internalToolExecutionEnabled(false)} so iterations, logs, and token usage are tracked cleanly.
 */
@Service
public class CodingAgent {

    private static final Logger log = LoggerFactory.getLogger(CodingAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are TaskPilot, an autonomous senior software engineer working inside a repository workspace.
            Your goal is to complete the assigned task accurately, cleanly, and verify it by running tests.

            Strict rules:
            1. Start by inspecting the project structure with list_files and reading relevant files with read_file.
            2. Prefer edit_file for targeted changes; use write_file only for new files or full rewrites.
            3. NEVER weaken, bypass, or delete existing assertions/tests just to make tests pass.
            4. Always run the test command via run_command after making changes. If tests fail, read the error log, fix the root cause, and re-run tests.
            5. Before finishing, call git_status_diff to verify your changes are clean and minimal.
            6. When all tests pass, respond with a concise summary containing:
               - Reason / Objective
               - Solution / Files changed
               - Test results
            """;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final CommandExecutor commandExecutor;
    private final AgentProperties properties;

    public CodingAgent(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            CommandExecutor commandExecutor,
            AgentProperties properties
    ) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.commandExecutor = commandExecutor;
        this.properties = properties;
    }

    public AgentResult runCodingLoop(TaskRequest request) {
        WorkspaceTools workspaceTools = new WorkspaceTools(request.repoPath(), commandExecutor, properties);
        ToolCallback[] toolCallbacks = ToolCallbacks.from(workspaceTools);

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        List<Message> initialMessages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(buildUserPrompt(request))
        );

        Prompt prompt = new Prompt(initialMessages, options);
        TokenUsageSummary totalUsage = TokenUsageSummary.ZERO;
        List<String> stepLogs = new ArrayList<>();
        int maxIterations = properties.getMaxIterations();
        long maxTotalTokens = properties.getMaxTotalTokens();
        int maxDuplicates = Math.max(2, properties.getMaxConsecutiveDuplicateToolCalls());

        String lastToolSignature = null;
        int duplicateStreak = 0;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("[{}] ReAct iteration {}/{}", request.ticketId(), iteration, maxIterations);
            ChatResponse response = chatModel.call(prompt);
            totalUsage = accumulateUsage(totalUsage, response);

            if (maxTotalTokens > 0 && totalUsage.totalTokens() > maxTotalTokens) {
                String msg = "Stopped: token budget exceeded (" + totalUsage.totalTokens()
                        + " > " + maxTotalTokens + ")";
                stepLogs.add(msg);
                log.warn("[{}] {}", request.ticketId(), msg);
                return new AgentResult(
                        AgentResult.Status.TOKEN_BUDGET_EXCEEDED,
                        msg,
                        iteration,
                        totalUsage,
                        List.copyOf(stepLogs),
                        null,
                        ""
                );
            }

            if (response.hasToolCalls()) {
                var toolCalls = response.getResult().getOutput().getToolCalls();
                String toolNames = toolCalls.stream()
                        .map(tc -> tc.name())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("unknown");
                String signature = toolCalls.stream()
                        .map(tc -> tc.name() + ":" + tc.arguments())
                        .reduce((a, b) -> a + "|" + b)
                        .orElse("");

                if (signature.equals(lastToolSignature)) {
                    duplicateStreak++;
                } else {
                    lastToolSignature = signature;
                    duplicateStreak = 1;
                }

                String logLine = "Iteration " + iteration + ": tool calls -> [" + toolNames + "]";
                log.info("[{}] {}", request.ticketId(), logLine);
                stepLogs.add(logLine);

                if (duplicateStreak >= maxDuplicates) {
                    String stuckMsg = "Stopped: stuck loop detected (" + duplicateStreak
                            + " identical tool calls in a row: " + toolNames + ")";
                    stepLogs.add(stuckMsg);
                    log.warn("[{}] {}", request.ticketId(), stuckMsg);
                    return new AgentResult(
                            AgentResult.Status.STUCK_LOOP,
                            stuckMsg,
                            iteration,
                            totalUsage,
                            List.copyOf(stepLogs),
                            null,
                            ""
                    );
                }

                ToolExecutionResult executionResult = toolCallingManager.executeToolCalls(prompt, response);
                prompt = new Prompt(executionResult.conversationHistory(), options);
            } else {
                String finalSummary = response.getResult() != null && response.getResult().getOutput() != null
                        ? response.getResult().getOutput().getText()
                        : "";
                stepLogs.add("Iteration " + iteration + ": agent completed with final response.");
                return new AgentResult(
                        AgentResult.Status.SUCCEEDED,
                        finalSummary,
                        iteration,
                        totalUsage,
                        List.copyOf(stepLogs),
                        null,
                        ""
                );
            }
        }

        stepLogs.add("Stopped: reached maxIterations=" + maxIterations);
        return new AgentResult(
                AgentResult.Status.MAX_ITERATIONS_REACHED,
                "Agent reached maximum iterations (" + maxIterations + ") without finishing.",
                maxIterations,
                totalUsage,
                List.copyOf(stepLogs),
                null,
                ""
        );
    }

    private static String buildUserPrompt(TaskRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ticket ID: ").append(request.ticketId()).append("\n");
        sb.append("Task Description:\n").append(request.taskDescription()).append("\n");
        if (!request.extraPrompt().isBlank()) {
            sb.append("\nAdditional Instructions:\n").append(request.extraPrompt()).append("\n");
        }
        if (!request.testCommand().isBlank()) {
            sb.append("\nTest Command to verify your work: `").append(request.testCommand()).append("`\n");
        }
        return sb.toString();
    }

    private static TokenUsageSummary accumulateUsage(TokenUsageSummary current, ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return current;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return current;
        }
        long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
        long completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;
        long totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : 0L;
        return current.add(promptTokens, completionTokens, totalTokens);
    }
}
