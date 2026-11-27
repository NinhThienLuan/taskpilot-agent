package com.taskpilot.agent.core;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.executor.LocalProcessCommandExecutor;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.TaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CodingAgentMockTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitReActLoopExecutesToolsAccumulatesTokensAndEditsFile() throws Exception {
        Path file = tempDir.resolve("Note.txt");
        Files.writeString(file, "status=TODO\n");

        AtomicInteger turn = new AtomicInteger(0);
        ChatModel mockModel = (Prompt prompt) -> {
            int currentTurn = turn.incrementAndGet();
            if (currentTurn == 1) {
                var call = new AssistantMessage.ToolCall(
                        "call-1", "function", "read_file",
                        "{\"relativePath\":\"Note.txt\",\"startLine\":1,\"endLine\":10}"
                );
                return buildToolCallResponse(List.of(call), 100, 20);
            }
            if (currentTurn == 2) {
                var call = new AssistantMessage.ToolCall(
                        "call-2", "function", "edit_file",
                        "{\"relativePath\":\"Note.txt\",\"oldText\":\"status=TODO\",\"newText\":\"status=DONE\"}"
                );
                return buildToolCallResponse(List.of(call), 180, 30);
            }
            return buildFinalResponse("Updated Note.txt status to DONE.", 220, 40);
        };

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);

        CodingAgent agent = new CodingAgent(
                mockModel,
                ToolCallingManager.builder().build(),
                new LocalProcessCommandExecutor(),
                props
        );

        TaskRequest request = new TaskRequest(
                "TEST-1",
                "Change status from TODO to DONE in Note.txt",
                "",
                tempDir,
                ""
        );

        AgentResult result = agent.runCodingLoop(request);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCEEDED);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.tokenUsage().promptTokens()).isEqualTo(500);
        assertThat(result.tokenUsage().generationTokens()).isEqualTo(90);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(590);
        assertThat(Files.readString(file)).contains("status=DONE");
    }

    @Test
    void stopsWhenMaxIterationsReached() {
        ChatModel loopingModel = (Prompt prompt) -> {
            var call = new AssistantMessage.ToolCall(
                    "call-loop", "function", "list_files",
                    "{\"relativePath\":\".\",\"maxDepth\":2}"
            );
            return buildToolCallResponse(List.of(call), 50, 10);
        };

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(3);

        CodingAgent agent = new CodingAgent(
                loopingModel,
                ToolCallingManager.builder().build(),
                new LocalProcessCommandExecutor(),
                props
        );

        TaskRequest request = new TaskRequest("TEST-2", "Infinite loop test", "", tempDir, "");
        AgentResult result = agent.runCodingLoop(request);

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_ITERATIONS_REACHED);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(180);
    }

    private static ChatResponse buildToolCallResponse(
            List<AssistantMessage.ToolCall> toolCalls,
            int promptTokens,
            int completionTokens
    ) {
        AssistantMessage message = new AssistantMessage("", java.util.Map.of(), toolCalls);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    private static ChatResponse buildFinalResponse(String text, int promptTokens, int completionTokens) {
        AssistantMessage message = new AssistantMessage(text);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }
}
