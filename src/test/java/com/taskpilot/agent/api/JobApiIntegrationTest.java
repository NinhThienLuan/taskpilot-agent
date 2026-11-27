package com.taskpilot.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ollama")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobapitest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "taskpilot.queue.mode=in-memory",
        "taskpilot.queue.in-memory-sync=true"
})
class JobApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatModel chatModel;

    @TempDir
    Path tempDir;

    @Test
    void postJobExecutesSevenStepPipelinePersistsProgressAndReturnsDetail() throws Exception {
        Path file = tempDir.resolve("README.txt");
        Files.writeString(file, "version=1\n");

        AtomicInteger turn = new AtomicInteger(0);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            if (turn.incrementAndGet() == 1) {
                var call = new AssistantMessage.ToolCall(
                        "call-1", "function", "edit_file",
                        "{\"relativePath\":\"README.txt\",\"oldText\":\"version=1\",\"newText\":\"version=2\"}"
                );
                AssistantMessage msg = new AssistantMessage("", Map.of(), List.of(call));
                return new ChatResponse(List.of(new Generation(msg)),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(120, 25, 145)).build());
            }
            AssistantMessage done = new AssistantMessage("Updated README.txt to version=2.");
            return new ChatResponse(List.of(new Generation(done)),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(160, 30, 190)).build());
        });

        String body = objectMapper.writeValueAsString(new CreateJobRequest(
                "JB-10",
                "Bump version to 2 in README.txt",
                "Keep changes minimal",
                tempDir.toString(),
                "echo tests-ok"
        ));

        MvcResult postResult = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ticketId").value("JB-10"))
                .andReturn();

        JsonNode created = objectMapper.readTree(postResult.getResponse().getContentAsString());
        long jobId = created.get("id").asLong();

        mockMvc.perform(get("/api/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.iterations").value(2))
                .andExpect(jsonPath("$.tokenUsage.totalTokens").value(335))
                .andExpect(jsonPath("$.steps.length()").value(7))
                .andExpect(jsonPath("$.steps[0].stepName").value("FETCH_TICKET"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[3].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[4].status").value("SKIPPED"))
                .andExpect(jsonPath("$.steps[5].status").value("SKIPPED"))
                .andExpect(jsonPath("$.steps[6].status").value("SKIPPED"));

        assertThat(Files.readString(file)).contains("version=2");

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }
}
