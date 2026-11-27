package com.taskpilot.agent;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.config.IntegrationProperties;
import com.taskpilot.agent.config.SandboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AgentProperties.class, SandboxProperties.class, IntegrationProperties.class})
public class TaskPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskPilotApplication.class, args);
    }
}
