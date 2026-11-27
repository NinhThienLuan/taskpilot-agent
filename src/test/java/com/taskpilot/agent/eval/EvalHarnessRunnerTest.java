package com.taskpilot.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.core.CodingAgent;
import com.taskpilot.agent.executor.LocalProcessCommandExecutor;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvalHarnessRunnerTest {

    @TempDir
    Path reportDir;

    @Test
    void evalHarnessGradesHiddenTestsAndWritesMarkdownAndJsonReports() throws Exception {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalProcessCommandExecutor executor = new LocalProcessCommandExecutor();
        AgentProperties props = new AgentProperties();

        // Mock model that implements divide() properly on EVAL-01
        AtomicInteger turn = new AtomicInteger(0);
        ChatModel solvingModel = (Prompt prompt) -> {
            if (turn.incrementAndGet() == 1) {
                String newCode = "    public int subtract(int a, int b) {\n"
                        + "        return a - b;\n"
                        + "    }\n\n"
                        + "    public int divide(int a, int b) {\n"
                        + "        if (b == 0) throw new IllegalArgumentException(\"Division by zero\");\n"
                        + "        return a / b;\n"
                        + "    }";
                String args = mapper.createObjectNode()
                        .put("relativePath", "src/main/java/com/example/calc/Calculator.java")
                        .put("oldText", "    public int subtract(int a, int b) {\n        return a - b;\n    }")
                        .put("newText", newCode)
                        .toString();
                var call = new AssistantMessage.ToolCall("c1", "function", "edit_file", args);
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage("", Map.of(), List.of(call)))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(100, 40, 140)).build()
                );
            }
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("Implemented divide with zero check."))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(120, 20, 140)).build()
            );
        };

        CodingAgent agent = new CodingAgent(solvingModel, ToolCallingManager.builder().build(), executor, props);
        EvalHarnessRunner runner = new EvalHarnessRunner(agent, executor, executor, props, mapper);

        List<EvalCase> allCases = runner.loadCases(projectRoot.resolve("eval/cases/cases.json"));
        assertThat(allCases).hasSize(5);

        EvalCase eval01 = allCases.stream().filter(c -> c.id().equals("EVAL-01")).findFirst().orElseThrow();
        EvalReport report = runner.runSuite("mock-ollama", List.of(eval01), projectRoot);

        assertThat(report.totalCases()).isEqualTo(1);
        assertThat(report.passedCases()).isEqualTo(1);
        assertThat(report.passAt1Rate()).isEqualTo(100.0);
        assertThat(report.results().get(0).hiddenTestsPassed()).isTrue();
        assertThat(report.results().get(0).antiRewardHackingClean()).isTrue();

        Path mdPath = runner.writeReportFiles(report, reportDir);
        assertThat(Files.exists(mdPath)).isTrue();
        assertThat(Files.readString(mdPath)).contains("EVAL-01").contains("PASS").contains("100.0%");
    }
}
