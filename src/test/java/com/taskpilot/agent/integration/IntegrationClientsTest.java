package com.taskpilot.agent.integration;

import com.taskpilot.agent.config.IntegrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IntegrationClientsTest {

    @Test
    void gitHubPrClientCreatesDraftPullRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        IntegrationProperties props = new IntegrationProperties();
        props.getGithub().setEnabled(true);
        props.getGithub().setApiBaseUrl("https://api.github.test");
        props.getGithub().setToken("ghp_secret");
        props.getGithub().setOwner("NinhThienLuan");
        props.getGithub().setRepo("taskpilot-agent");
        props.getGithub().setBaseBranch("main");
        props.getGithub().setDraftPr(true);

        server.expect(requestTo("https://api.github.test/repos/NinhThienLuan/taskpilot-agent/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ghp_secret"))
                .andExpect(jsonPath("$.head").value("feature/JB-4-1"))
                .andExpect(jsonPath("$.base").value("main"))
                .andExpect(jsonPath("$.draft").value(true))
                .andRespond(withSuccess(
                        "{\"number\":7,\"html_url\":\"https://github.com/NinhThienLuan/taskpilot-agent/pull/7\"}",
                        MediaType.APPLICATION_JSON
                ));

        GitHubPrClient client = new GitHubPrClient(props, builder.build());
        assertThat(client.isConfigured()).isTrue();

        GitHubPrClient.PullRequestInfo pr = client.createPullRequest("feature/JB-4-1", "JB-4: Add JWT", "Summary");
        assertThat(pr.number()).isEqualTo(7);
        assertThat(pr.htmlUrl()).isEqualTo("https://github.com/NinhThienLuan/taskpilot-agent/pull/7");
        server.verify();
    }

    @Test
    void jiraClientFetchesIssueAddsCommentAndTransitionsStatus() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        IntegrationProperties props = new IntegrationProperties();
        props.getJira().setEnabled(true);
        props.getJira().setBaseUrl("https://example.atlassian.net");
        props.getJira().setEmail("dev@example.com");
        props.getJira().setApiToken("jira-token");

        server.expect(requestTo("https://example.atlassian.net/rest/api/3/issue/JB-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "key": "JB-4",
                          "fields": {
                            "summary": "Implement JWT Auth",
                            "description": {
                              "type": "doc",
                              "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Use HS256"}]}]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://example.atlassian.net/rest/api/3/issue/JB-4/comment"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://example.atlassian.net/rest/api/3/issue/JB-4/transitions"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"transitions": [{"id": "31", "name": "In Review", "to": {"name": "In Review"}}]}
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://example.atlassian.net/rest/api/3/issue/JB-4/transitions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transition.id").value("31"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        JiraClient client = new JiraClient(props, builder.build());
        assertThat(client.isConfigured()).isTrue();

        JiraClient.JiraIssue issue = client.fetchIssue("JB-4");
        assertThat(issue.summary()).isEqualTo("Implement JWT Auth");
        assertThat(issue.description()).contains("Use HS256");

        client.addComment("JB-4", "TaskPilot report");
        assertThat(client.transitionIssue("JB-4", "In Review")).isTrue();
        server.verify();
    }
}
