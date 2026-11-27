package com.taskpilot.agent.job;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.config.IntegrationProperties;
import com.taskpilot.agent.core.CodingAgent;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.executor.SandboxLifecycle;
import com.taskpilot.agent.integration.GitHubPrClient;
import com.taskpilot.agent.integration.JiraClient;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.CommandResult;
import com.taskpilot.agent.model.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full 7-step TaskPilot pipeline for an asynchronous Job stored in PostgreSQL:
 * 1. FETCH_TICKET -> 2. SETUP_SANDBOX -> 3. AI_CODING_LOOP -> 4. RUN_TESTS
 * -> 5. GIT_COMMIT_PUSH -> 6. CREATE_PR -> 7. REPORT_TICKET
 */
@Service
public class AsyncJobPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobPipelineService.class);

    public static final List<String> PIPELINE_STEP_NAMES = List.of(
            "FETCH_TICKET",
            "SETUP_SANDBOX",
            "AI_CODING_LOOP",
            "RUN_TESTS",
            "GIT_COMMIT_PUSH",
            "CREATE_PR",
            "REPORT_TICKET"
    );

    private final JobRunRepository jobRunRepository;
    private final JobStepRepository jobStepRepository;
    private final CodingAgent codingAgent;
    private final CommandExecutor commandExecutor;
    private final SandboxLifecycle sandboxLifecycle;
    private final AgentProperties agentProperties;
    private final IntegrationProperties integrationProperties;
    private final JiraClient jiraClient;
    private final GitHubPrClient gitHubPrClient;

    public AsyncJobPipelineService(
            JobRunRepository jobRunRepository,
            JobStepRepository jobStepRepository,
            CodingAgent codingAgent,
            CommandExecutor commandExecutor,
            SandboxLifecycle sandboxLifecycle,
            AgentProperties agentProperties,
            IntegrationProperties integrationProperties,
            JiraClient jiraClient,
            GitHubPrClient gitHubPrClient
    ) {
        this.jobRunRepository = jobRunRepository;
        this.jobStepRepository = jobStepRepository;
        this.codingAgent = codingAgent;
        this.commandExecutor = commandExecutor;
        this.sandboxLifecycle = sandboxLifecycle;
        this.agentProperties = agentProperties;
        this.integrationProperties = integrationProperties;
        this.jiraClient = jiraClient;
        this.gitHubPrClient = gitHubPrClient;
    }

    public List<JobStepEntity> initializeSteps(Long jobId) {
        List<JobStepEntity> steps = new ArrayList<>();
        for (int i = 0; i < PIPELINE_STEP_NAMES.size(); i++) {
            steps.add(new JobStepEntity(jobId, i + 1, PIPELINE_STEP_NAMES.get(i)));
        }
        return jobStepRepository.saveAll(steps);
    }

    public void processJob(Long jobId) {
        JobRunEntity job = jobRunRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Received queue signal for unknown jobId={}", jobId);
            return;
        }

        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        String branchName = buildBranchName(job.getTicketId(), jobId);
        job.setBranchName(branchName);
        jobRunRepository.save(job);

        Path workspacePath = Path.of(job.getRepoPath()).toAbsolutePath().normalize();

        // Step 1: FETCH_TICKET
        long step1Start = System.currentTimeMillis();
        updateStep(jobId, 1, StepStatus.RUNNING, "Fetching ticket " + job.getTicketId() + "...", 0);
        String effectiveTaskDescription = job.getTaskDescription();
        try {
            if (jiraClient.isConfigured()) {
                JiraClient.JiraIssue issue = jiraClient.fetchIssue(job.getTicketId());
                String jiraText = issue.summary() + (issue.description().isBlank() ? "" : "\n\n" + issue.description());
                effectiveTaskDescription = (effectiveTaskDescription == null || effectiveTaskDescription.isBlank())
                        ? jiraText
                        : jiraText + "\n\nAdditional task notes:\n" + effectiveTaskDescription;
                job.setTaskDescription(effectiveTaskDescription);
                jobRunRepository.save(job);
                updateStep(jobId, 1, StepStatus.COMPLETED,
                        "Fetched Jira ticket " + issue.key() + ": " + issue.summary(),
                        System.currentTimeMillis() - step1Start);
            } else {
                updateStep(jobId, 1, StepStatus.COMPLETED,
                        "Loaded ticket " + job.getTicketId() + " (local request mode)",
                        System.currentTimeMillis() - step1Start);
            }
        } catch (Exception e) {
            updateStep(jobId, 1, StepStatus.FAILED, "Failed to fetch Jira ticket: " + e.getMessage(),
                    System.currentTimeMillis() - step1Start);
            failJob(job, "Failed to fetch Jira ticket: " + e.getMessage());
            return;
        }

        // Step 2: SETUP_SANDBOX
        long step2Start = System.currentTimeMillis();
        updateStep(jobId, 2, StepStatus.RUNNING, "Opening sandbox for " + workspacePath, 0);

        try (AutoCloseable ignored = sandboxLifecycle.openSession(job.getTicketId(), workspacePath)) {
            updateStep(jobId, 2, StepStatus.COMPLETED,
                    "Sandbox ready for workspace: " + workspacePath + " (target branch: " + branchName + ")",
                    System.currentTimeMillis() - step2Start);

            // Step 3: AI_CODING_LOOP
            long step3Start = System.currentTimeMillis();
            updateStep(jobId, 3, StepStatus.RUNNING, "Running Explicit ReAct CodingAgent...", 0);

            TaskRequest taskRequest = new TaskRequest(
                    job.getTicketId(),
                    effectiveTaskDescription,
                    job.getExtraPrompt(),
                    workspacePath,
                    job.getTestCommand()
            );
            AgentResult agentResult = codingAgent.runCodingLoop(taskRequest);

            job.setIterations(agentResult.iterations());
            job.setPromptTokens(agentResult.tokenUsage().promptTokens());
            job.setCompletionTokens(agentResult.tokenUsage().generationTokens());
            job.setTotalTokens(agentResult.tokenUsage().totalTokens());
            job.setSummary(agentResult.summary());
            jobRunRepository.save(job);

            boolean agentSucceeded = agentResult.status() == AgentResult.Status.SUCCEEDED;
            updateStep(jobId, 3,
                    agentSucceeded ? StepStatus.COMPLETED : StepStatus.FAILED,
                    String.join("\n", agentResult.stepLogs()),
                    System.currentTimeMillis() - step3Start);

            // Step 4: RUN_TESTS + Anti-Reward-Hacking check
            JobStatus finalStatus = mapStatus(agentResult.status());
            long step4Start = System.currentTimeMillis();
            if (job.getTestCommand() != null && !job.getTestCommand().isBlank()) {
                updateStep(jobId, 4, StepStatus.RUNNING, "Executing: " + job.getTestCommand(), 0);
                CommandResult verifyResult = commandExecutor.execute(
                        workspacePath,
                        job.getTestCommand(),
                        agentProperties.getCommandTimeout()
                );

                CommandResult fullDiff = commandExecutor.execute(
                        workspacePath, "git diff HEAD", Duration.ofSeconds(15)
                );
                var hackCheck = com.taskpilot.agent.harness.AntiRewardHackingChecker
                        .inspectUnifiedDiff(fullDiff.stdout());

                String step4Log = verifyResult.combinedOutput()
                        + "\n--- Harness Anti-Reward-Hacking Check ---\n"
                        + hackCheck.summary();

                boolean step4Passed = verifyResult.isSuccess() && hackCheck.clean();
                updateStep(jobId, 4,
                        step4Passed ? StepStatus.COMPLETED : StepStatus.FAILED,
                        step4Log,
                        System.currentTimeMillis() - step4Start);

                if (!hackCheck.clean()) {
                    finalStatus = JobStatus.REWARD_HACKING;
                } else if (!verifyResult.isSuccess() && finalStatus == JobStatus.SUCCEEDED) {
                    finalStatus = JobStatus.VERIFICATION_FAILED;
                }
            } else {
                updateStep(jobId, 4, StepStatus.SKIPPED, "No testCommand configured", 0);
            }

            // Step 5: GIT_COMMIT_PUSH
            long step5Start = System.currentTimeMillis();
            CommandResult diffResult = commandExecutor.execute(
                    workspacePath,
                    "git status --short && git diff --stat",
                    Duration.ofSeconds(15)
            );
            String diffOutput = diffResult.combinedOutput();
            job.setGitDiffStat(diffOutput);
            jobRunRepository.save(job);

            boolean pushedBranch = false;
            if (gitHubPrClient.isConfigured() && finalStatus == JobStatus.SUCCEEDED && !diffOutput.isBlank()) {
                updateStep(jobId, 5, StepStatus.RUNNING, "Committing and pushing branch " + branchName, 0);
                String safeMsg = (job.getTicketId() + ": automated TaskPilot implementation").replace("\"", "'");
                String gitCmd = "git checkout -B " + branchName
                        + " && git add -A"
                        + " && git commit -m \"" + safeMsg + "\""
                        + " && git push -u origin " + branchName;
                CommandResult pushResult = commandExecutor.execute(workspacePath, gitCmd, Duration.ofSeconds(30));
                if (pushResult.isSuccess()) {
                    pushedBranch = true;
                    updateStep(jobId, 5, StepStatus.COMPLETED,
                            "Pushed branch " + branchName + "\n" + pushResult.combinedOutput(),
                            System.currentTimeMillis() - step5Start);
                } else {
                    updateStep(jobId, 5, StepStatus.FAILED,
                            "Git commit/push failed:\n" + pushResult.combinedOutput(),
                            System.currentTimeMillis() - step5Start);
                }
            } else {
                updateStep(jobId, 5, StepStatus.SKIPPED,
                        "GitHub integration disabled or no diff to push.\n" + diffOutput,
                        System.currentTimeMillis() - step5Start);
            }

            // Step 6: CREATE_PR
            long step6Start = System.currentTimeMillis();
            if (pushedBranch) {
                updateStep(jobId, 6, StepStatus.RUNNING, "Opening GitHub Pull Request...", 0);
                try {
                    String prTitle = job.getTicketId() + ": " + firstLine(effectiveTaskDescription, 80);
                    String prBody = buildPrBody(job);
                    GitHubPrClient.PullRequestInfo pr = gitHubPrClient.createPullRequest(branchName, prTitle, prBody);
                    job.setPrUrl(pr.htmlUrl());
                    jobRunRepository.save(job);
                    updateStep(jobId, 6, StepStatus.COMPLETED,
                            "Created Pull Request #" + pr.number() + ": " + pr.htmlUrl(),
                            System.currentTimeMillis() - step6Start);
                } catch (Exception e) {
                    updateStep(jobId, 6, StepStatus.FAILED, "Failed to create PR: " + e.getMessage(),
                            System.currentTimeMillis() - step6Start);
                }
            } else {
                updateStep(jobId, 6, StepStatus.SKIPPED, "Skipped PR creation (branch was not pushed)", 0);
            }

            // Step 7: REPORT_TICKET
            long step7Start = System.currentTimeMillis();
            if (jiraClient.isConfigured()) {
                updateStep(jobId, 7, StepStatus.RUNNING, "Posting implementation report to Jira...", 0);
                try {
                    String report = buildJiraReport(job);
                    jiraClient.addComment(job.getTicketId(), report);
                    boolean transitioned = jiraClient.transitionIssue(
                            job.getTicketId(),
                            integrationProperties.getJira().getTransitionStatus()
                    );
                    updateStep(jobId, 7, StepStatus.COMPLETED,
                            "Posted Jira comment" + (transitioned ? " and transitioned status." : "."),
                            System.currentTimeMillis() - step7Start);
                } catch (Exception e) {
                    updateStep(jobId, 7, StepStatus.FAILED, "Failed to report on Jira: " + e.getMessage(),
                            System.currentTimeMillis() - step7Start);
                }
            } else {
                updateStep(jobId, 7, StepStatus.SKIPPED, "Jira integration disabled", 0);
            }

            job.setStatus(finalStatus);
            job.setFinishedAt(Instant.now());
            jobRunRepository.save(job);
        } catch (Exception e) {
            log.error("Job {} failed during pipeline execution", jobId, e);
            updateStep(jobId, 2, StepStatus.FAILED, "Pipeline error: " + e.getMessage(),
                    System.currentTimeMillis() - step2Start);
            failJob(job, "Pipeline failed: " + e.getMessage());
        }
    }

    private void failJob(JobRunEntity job, String reason) {
        job.setStatus(JobStatus.FAILED);
        job.setSummary(reason);
        job.setFinishedAt(Instant.now());
        jobRunRepository.save(job);
    }

    private void updateStep(Long jobId, int stepNumber, StepStatus status, String logOutput, long durationMs) {
        jobStepRepository.findByJobIdAndStepNumber(jobId, stepNumber).ifPresent(step -> {
            step.setStatus(status);
            step.setLogOutput(logOutput);
            step.setDurationMs(durationMs);
            jobStepRepository.save(step);
        });
    }

    private static String buildBranchName(String ticketId, Long jobId) {
        String safeTicket = (ticketId == null ? "task" : ticketId).replaceAll("[^a-zA-Z0-9-]", "-");
        return "feature/" + safeTicket + "-" + jobId;
    }

    private static String firstLine(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "Automated Implementation";
        }
        String line = text.split("\\R")[0].trim();
        return line.length() > maxLen ? line.substring(0, maxLen) + "..." : line;
    }

    private static String buildPrBody(JobRunEntity job) {
        return "## TaskPilot Automation Summary\n\n"
                + "**Ticket:** " + job.getTicketId() + "\n"
                + "**Iterations:** " + job.getIterations() + " | **Total Tokens:** " + job.getTotalTokens() + "\n\n"
                + "### AI Summary\n" + (job.getSummary() == null ? "" : job.getSummary()) + "\n\n"
                + "### Git Diff Stat\n```\n" + (job.getGitDiffStat() == null ? "" : job.getGitDiffStat()) + "\n```";
    }

    private static String buildJiraReport(JobRunEntity job) {
        StringBuilder sb = new StringBuilder();
        sb.append("TaskPilot - Automation Implementation Report\n\n");
        sb.append("1. Reason (Objective):\n").append(firstLine(job.getTaskDescription(), 200)).append("\n\n");
        sb.append("2. Solution:\n").append(job.getSummary() == null ? "Completed." : job.getSummary()).append("\n\n");
        if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
            sb.append("3. Pull Request: ").append(job.getPrUrl()).append("\n");
        }
        sb.append("Tokens used: ").append(job.getTotalTokens())
                .append(" (iterations: ").append(job.getIterations()).append(")");
        return sb.toString();
    }

    private static JobStatus mapStatus(AgentResult.Status status) {
        return switch (status) {
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case MAX_ITERATIONS_REACHED -> JobStatus.MAX_ITERATIONS_REACHED;
            case TOKEN_BUDGET_EXCEEDED -> JobStatus.TOKEN_BUDGET_EXCEEDED;
            case STUCK_LOOP -> JobStatus.STUCK_LOOP;
            case VERIFICATION_FAILED -> JobStatus.VERIFICATION_FAILED;
            case FAILED -> JobStatus.FAILED;
        };
    }
}
