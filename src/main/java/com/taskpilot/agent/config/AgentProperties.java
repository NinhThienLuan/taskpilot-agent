package com.taskpilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "taskpilot.agent")
public class AgentProperties {

    private int maxIterations = 15;
    private int commandTimeoutSeconds = 90;
    private int maxReadLines = 200;
    private int maxGrepMatches = 40;
    private int maxCommandOutputLines = 90;

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public Duration getCommandTimeout() {
        return Duration.ofSeconds(commandTimeoutSeconds);
    }

    public int getMaxReadLines() {
        return maxReadLines;
    }

    public void setMaxReadLines(int maxReadLines) {
        this.maxReadLines = maxReadLines;
    }

    public int getMaxGrepMatches() {
        return maxGrepMatches;
    }

    public void setMaxGrepMatches(int maxGrepMatches) {
        this.maxGrepMatches = maxGrepMatches;
    }

    public int getMaxCommandOutputLines() {
        return maxCommandOutputLines;
    }

    public void setMaxCommandOutputLines(int maxCommandOutputLines) {
        this.maxCommandOutputLines = maxCommandOutputLines;
    }
}
