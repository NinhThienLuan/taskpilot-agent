package com.taskpilot.agent;

import com.taskpilot.agent.core.CodingAgent;
import com.taskpilot.agent.core.LocalPipelineRunner;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.executor.docker.DockerSandboxCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the real application.yml + profile YAMLs wire exactly one ChatModel bean
 * for both 'gemini' and 'ollama', plus Docker sandbox mode, without needing live Postgres/RabbitMQ.
 */
class SpringProfileWiringTest {

    private static final String[] TEST_INFRA_OVERRIDES = {
            "spring.datasource.url=jdbc:h2:mem:wiringtest;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "taskpilot.queue.mode=in-memory"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TaskPilotApplication.class)
            .withPropertyValues(TEST_INFRA_OVERRIDES);

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
                    assertThat(context).hasSingleBean(CommandExecutor.class);
                    assertThat(context.getBean(CommandExecutor.class))
                            .isInstanceOf(DockerSandboxCommandExecutor.class);
                });
    }
}
