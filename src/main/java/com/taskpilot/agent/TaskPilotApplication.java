package com.taskpilot.agent;

import com.taskpilot.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class TaskPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskPilotApplication.class, args);
    }
}
