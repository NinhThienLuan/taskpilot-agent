package com.taskpilot.agent;

import com.taskpilot.agent.core.CodingAgent;
import com.taskpilot.agent.core.LocalPipelineRunner;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the real application.yml + profile YAMLs wire exactly one ChatModel bean
 * for both 'gemini' and 'ollama' without bean ambiguity or missing API key errors.
 */
class SpringProfileWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TaskPilotApplication.class);

    @Test
    void geminiProfileWiresSingleChatModelAndAgent() {
        contextRunner
                .withPropertyValues("spring.profiles.active=gemini")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(CodingAgent.class);
                    assertThat(context).hasSingleBean(LocalPipelineRunner.class);
                });
    }

    @Test
    void ollamaProfileWiresSingleChatModelAndAgent() {
        contextRunner
                .withPropertyValues("spring.profiles.active=ollama")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(CodingAgent.class);
                    assertThat(context).hasSingleBean(LocalPipelineRunner.class);
                });
    }

    @Test
    void dockerSandboxModeWiresDockerExecutor() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=ollama",
                        "taskpilot.sandbox.mode=docker"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.taskpilot.agent.executor.CommandExecutor.class);
                    assertThat(context.getBean(com.taskpilot.agent.executor.CommandExecutor.class))
                            .isInstanceOf(com.taskpilot.agent.executor.docker.DockerSandboxCommandExecutor.class);
                });
    }
}
