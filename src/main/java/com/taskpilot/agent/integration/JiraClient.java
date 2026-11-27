package com.taskpilot.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskpilot.agent.config.IntegrationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class JiraClient {

    public record JiraIssue(String key, String summary, String description) {
    }

    private final IntegrationProperties properties;
    private final RestClient restClient;

    @Autowired
    public JiraClient(IntegrationProperties properties) {
        this(properties, RestClient.builder().build());
    }

    JiraClient(IntegrationProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public boolean isConfigured() {
        var jira = properties.getJira();
        return jira.isEnabled()
                && !isBlank(jira.getBaseUrl())
                && !isBlank(jira.getEmail())
                && !isBlank(jira.getApiToken());
    }

    public JiraIssue fetchIssue(String ticketId) {
        String url = stripTrailingSlash(properties.getJira().getBaseUrl())
                + "/rest/api/3/issue/" + ticketId.trim();

        JsonNode response = restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Empty Jira response for " + ticketId);
        }
        JsonNode fields = response.path("fields");
        String summary = fields.path("summary").asText("");
        String description = extractPlainText(fields.path("description"));
        return new JiraIssue(response.path("key").asText(ticketId), summary, description);
    }

    public void addComment(String ticketId, String reportText) {
        String url = stripTrailingSlash(properties.getJira().getBaseUrl())
                + "/rest/api/3/issue/" + ticketId.trim() + "/comment";

        Map<String, Object> adfBody = Map.of(
                "body", Map.of(
                        "type", "doc",
                        "version", 1,
                        "content", List.of(
                                Map.of(
                                        "type", "paragraph",
                                        "content", List.of(Map.of("type", "text", "text", reportText))
                                )
                        )
                )
        );

        restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(adfBody)
                .retrieve()
                .toBodilessEntity();
    }

    public boolean transitionIssue(String ticketId, String targetStatusName) {
        if (isBlank(targetStatusName)) {
            return false;
        }
        String url = stripTrailingSlash(properties.getJira().getBaseUrl())
                + "/rest/api/3/issue/" + ticketId.trim() + "/transitions";

        JsonNode transitionsResp = restClient.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(JsonNode.class);

        if (transitionsResp == null || !transitionsResp.has("transitions")) {
            return false;
        }
        for (JsonNode transition : transitionsResp.get("transitions")) {
            String name = transition.path("name").asText("");
            String toName = transition.path("to").path("name").asText("");
            if (name.equalsIgnoreCase(targetStatusName) || toName.equalsIgnoreCase(targetStatusName)) {
                String transitionId = transition.path("id").asText();
                restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("transition", Map.of("id", transitionId)))
                        .retrieve()
                        .toBodilessEntity();
                return true;
            }
        }
        return false;
    }

    private String basicAuthHeader() {
        String creds = properties.getJira().getEmail().trim() + ":" + properties.getJira().getApiToken().trim();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private static String extractPlainText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString().trim();
    }

    private static void collectText(JsonNode node, StringBuilder sb) {
        if (node.has("text") && node.get("text").isTextual()) {
            sb.append(node.get("text").asText()).append(" ");
        }
        if (node.has("content") && node.get("content").isArray()) {
            for (JsonNode child : node.get("content")) {
                collectText(child, sb);
            }
            if ("paragraph".equals(node.path("type").asText())) {
                sb.append("\n");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
