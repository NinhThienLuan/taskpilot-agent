package com.taskpilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "taskpilot.integrations")
public class IntegrationProperties {

    private final GitHub github = new GitHub();
    private final Jira jira = new Jira();

    public GitHub getGithub() {
        return github;
    }

    public Jira getJira() {
        return jira;
    }

    public static class GitHub {
        private boolean enabled = false;
        private String apiBaseUrl = "https://api.github.com";
        private String token = "";
        private String owner = "";
        private String repo = "";
        private String baseBranch = "main";
        private boolean draftPr = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getRepo() {
            return repo;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }

        public String getBaseBranch() {
            return baseBranch;
        }

        public void setBaseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
        }

        public boolean isDraftPr() {
            return draftPr;
        }

        public void setDraftPr(boolean draftPr) {
            this.draftPr = draftPr;
        }
    }

    public static class Jira {
        private boolean enabled = false;
        private String baseUrl = "";
        private String email = "";
        private String apiToken = "";
        private String transitionStatus = "In Review";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }

        public String getTransitionStatus() {
            return transitionStatus;
        }

        public void setTransitionStatus(String transitionStatus) {
            this.transitionStatus = transitionStatus;
        }
    }
}
