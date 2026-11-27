package com.taskpilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "taskpilot.sandbox")
public class SandboxProperties {

    /**
     * Execution mode: "local" (default MVP) or "docker" (isolated container).
     */
    private String mode = "local";
    private String image = "taskpilot-sandbox:java21";
    private String cpus = "2.0";
    private String memory = "2g";
    private int pidsLimit = 256;
    private String networkMode = "bridge";
    private String mavenCacheVolume = "";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getCpus() {
        return cpus;
    }

    public void setCpus(String cpus) {
        this.cpus = cpus;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public int getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(int pidsLimit) {
        this.pidsLimit = pidsLimit;
    }

    public String getNetworkMode() {
        return networkMode;
    }

    public void setNetworkMode(String networkMode) {
        this.networkMode = networkMode;
    }

    public String getMavenCacheVolume() {
        return mavenCacheVolume;
    }

    public void setMavenCacheVolume(String mavenCacheVolume) {
        this.mavenCacheVolume = mavenCacheVolume;
    }
}
