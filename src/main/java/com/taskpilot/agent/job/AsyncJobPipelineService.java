package com.taskpilot.agent.job;

import com.taskpilot.agent.config.AgentProperties;
import com.taskpilot.agent.core.CodingAgent;
import com.taskpilot.agent.executor.CommandExecutor;
import com.taskpilot.agent.executor.SandboxLifecycle;
import com.taskpilot.agent.model.AgentResult;
import com.taskpilot.agent.model.CommandResult;
import com.taskpilot.agent.model.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Orchestrates the full 7-step TaskPilot pipeline for an asynchronous Job stored in PostgreSQL.
 * Steps 2, 3, and 4 execute real sandbox/agent/test logic; Steps 1, 5, 6, and 7 are pluggable slots
 * ready for Phase 4B Jira/GitHub integrations.
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

    public AsyncJobPipelineService(
            JobRunRepository jobRunRepository,
            JobStepRepository jobStepRepository,
            CodingAgent codingAgent,
            CommandExecutor commandExecutor,
            SandboxLifecycle sandboxLifecycle,
            AgentProperties agentProperties
    ) {
        this.jobRunRepository = jobRunRepository;
        this.jobStepRepository = jobStepRepository;
        this.codingAgent = codingAgent;
        this.commandExecutor = commandExecutor;
        this.sandboxLifecycle = sandboxLifecycle;
        this.agentProperties = agentProperties;
    }

    public List<JobStepEntity> initializeSteps(Long jobId) {
        List<JobStepEntity> steps = new java.util.ArrayList<>();
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
        jobRunRepository.save(job);

        Path workspacePath = Path.of(job.getRepoPath()).toAbsolutePath().normalize();

        // Step 1: FETCH_TICKET (local payload mode in Phase 4A; Jira/GitHub Issue fetcher in Phase 4B)
        long step1Start = System.currentTimeMillis();
        updateStep(jobId, 1, StepStatus.RUNNING, "Loading ticket context...", 0);
        updateStep(jobId, 1, StepStatus.COMPLETED,
                "Loaded ticket " + job.getTicketId() + " (local request mode)",
                System.currentTimeMillis() - step1Start);

        // Step 2: SETUP_SANDBOX
        long step2Start = System.currentTimeMillis();
        updateStep(jobId, 2, StepStatus.RUNNING, "Opening sandbox for " + workspacePath, 0);

        try (AutoCloseable ignored = sandboxLifecycle.openSession(job.getTicketId(), workspacePath)) {
            updateStep(jobId, 2, StepStatus.COMPLETED,
                    "Sandbox ready for workspace: " + workspacePath,
                    System.currentTimeMillis() - step2Start);

            // Step 3: AI_CODING_LOOP
            long step3Start = System.currentTimeMillis();
            updateStep(jobId, 3, StepStatus.RUNNING, "Running Explicit ReAct CodingAgent...", 0);

            TaskRequest taskRequest = new TaskRequest(
                    job.getTicketId(),
                    job.getTaskDescription(),
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

            // Step 4: RUN_TESTS (independent verification)
            JobStatus finalStatus = mapStatus(agentResult.status());
            long step4Start = System.currentTimeMillis();
            if (job.getTestCommand() != null && !job.getTestCommand().isBlank()) {
                updateStep(jobId, 4, StepStatus.RUNNING, "Executing: " + job.getTestCommand(), 0);
                CommandResult verifyResult = commandExecutor.execute(
                        workspacePath,
                        job.getTestCommand(),
                        agentProperties.getCommandTimeout()
                );
                updateStep(jobId, 4,
                        verifyResult.isSuccess() ? StepStatus.COMPLETED : StepStatus.FAILED,
                        verifyResult.combinedOutput(),
                        System.currentTimeMillis() - step4Start);
                if (!verifyResult.isSuccess() && finalStatus == JobStatus.SUCCEEDED) {
                    finalStatus = JobStatus.VERIFICATION_FAILED;
                }
            } else {
                updateStep(jobId, 4, StepStatus.SKIPPED, "No testCommand configured", 0);
            }

            // Step 5: GIT_COMMIT_PUSH (capture git diff stat now; remote push in Phase 4B)
            long step5Start = System.currentTimeMillis();
            CommandResult diffResult = commandExecutor.execute(
                    workspacePath,
                    "git status --short && git diff --stat",
                    Duration.ofSeconds(15)
            );
            job.setGitDiffStat(diffResult.combinedOutput());
            updateStep(jobId, 5, StepStatus.SKIPPED,
                    "Captured git diff stat. Remote commit/push pending Phase 4B.\n" + diffResult.combinedOutput(),
                    System.currentTimeMillis() - step5Start);

            // Step 6 & 7: CREATE_PR & REPORT_TICKET (Phase 4B hooks)
            updateStep(jobId, 6, StepStatus.SKIPPED, "GitHub Pull Request creation pending Phase 4B", 0);
            updateStep(jobId, 7, StepStatus.SKIPPED, "Jira/Ticket comment & transition pending Phase 4B", 0);

            job.setStatus(finalStatus);
            job.setFinishedAt(Instant.now());
            jobRunRepository.save(job);
        } catch (Exception e) {
            log.error("Job {} failed during pipeline execution", jobId, e);
            updateStep(jobId, 2, StepStatus.FAILED, "Pipeline error: " + e.getMessage(),
                    System.currentTimeMillis() - step2Start);
            job.setStatus(JobStatus.FAILED);
            job.setSummary("Pipeline failed: " + e.getMessage());
            job.setFinishedAt(Instant.now());
            jobRunRepository.save(job);
        }
    }

    private void updateStep(Long jobId, int stepNumber, StepStatus status, String logOutput, long durationMs) {
        jobStepRepository.findByJobIdAndStepNumber(jobId, stepNumber).ifPresent(step -> {
            step.setStatus(status);
            step.setLogOutput(logOutput);
            step.setDurationMs(durationMs);
            jobStepRepository.save(step);
        });
    }

    private static JobStatus mapStatus(AgentResult.Status status) {
        return switch (status) {
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case MAX_ITERATIONS_REACHED -> JobStatus.MAX_ITERATIONS_REACHED;
            case VERIFICATION_FAILED -> JobStatus.VERIFICATION_FAILED;
            case FAILED -> JobStatus.FAILED;
        };
    }
}
