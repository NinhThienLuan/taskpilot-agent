package com.taskpilot.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskpilot.agent.config.IntegrationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class GitHubPrClient {

    public record PullRequestInfo(int number, String htmlUrl) {
    }

    private final IntegrationProperties properties;
    private final RestClient restClient;

    @Autowired
    public GitHubPrClient(IntegrationProperties properties) {
        this(properties, RestClient.builder().build());
    }

    GitHubPrClient(IntegrationProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public boolean isConfigured() {
        var gh = properties.getGithub();
        return gh.isEnabled()
                && !isBlank(gh.getToken())
                && !isBlank(gh.getOwner())
                && !isBlank(gh.getRepo());
    }

    public PullRequestInfo createPullRequest(String headBranch, String title, String body) {
        var gh = properties.getGithub();
        String url = stripTrailingSlash(gh.getApiBaseUrl())
                + "/repos/" + gh.getOwner().trim() + "/" + gh.getRepo().trim() + "/pulls";

        Map<String, Object> payload = Map.of(
                "title", title,
                "head", headBranch,
                "base", gh.getBaseBranch(),
                "body", body == null ? "" : body,
                "draft", gh.isDraftPr()
        );

        JsonNode response = restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gh.getToken().trim())
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("html_url")) {
            throw new IllegalStateException("GitHub PR response did not include html_url");
        }
        return new PullRequestInfo(response.path("number").asInt(0), response.get("html_url").asText());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
