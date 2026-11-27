package com.taskpilot.agent.api;

import com.taskpilot.agent.job.JobRunEntity;
import com.taskpilot.agent.job.JobStatus;
import com.taskpilot.agent.job.JobStepEntity;
import com.taskpilot.agent.job.StepStatus;

import java.time.Instant;
import java.util.List;

public final class JobResponses {

    private JobResponses() {
    }

    public record TokenUsageDto(long promptTokens, long completionTokens, long totalTokens) {
    }

    public record JobStepDto(
            int stepNumber,
            String stepName,
            StepStatus status,
            long durationMs,
            String logOutput,
            Instant updatedAt
    ) {
        public static JobStepDto from(JobStepEntity entity) {
            return new JobStepDto(
                    entity.getStepNumber(),
                    entity.getStepName(),
                    entity.getStatus(),
                    entity.getDurationMs(),
                    entity.getLogOutput(),
                    entity.getUpdatedAt()
            );
        }
    }

    public record JobSummaryDto(
            Long id,
            String ticketId,
            String taskDescription,
            String branchName,
            String prUrl,
            JobStatus status,
            int iterations,
            TokenUsageDto tokenUsage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) {
        public static JobSummaryDto from(JobRunEntity entity) {
            return new JobSummaryDto(
                    entity.getId(),
                    entity.getTicketId(),
                    entity.getTaskDescription(),
                    entity.getBranchName(),
                    entity.getPrUrl(),
                    entity.getStatus(),
                    entity.getIterations(),
                    new TokenUsageDto(entity.getPromptTokens(), entity.getCompletionTokens(), entity.getTotalTokens()),
                    entity.getCreatedAt(),
                    entity.getStartedAt(),
                    entity.getFinishedAt()
            );
        }
    }

    public record JobDetailDto(
            Long id,
            String ticketId,
            String taskDescription,
            String extraPrompt,
            String repoPath,
            String testCommand,
            String branchName,
            String prUrl,
            JobStatus status,
            String summary,
            String gitDiffStat,
            int iterations,
            TokenUsageDto tokenUsage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            List<JobStepDto> steps
    ) {
        public static JobDetailDto from(JobRunEntity entity, List<JobStepEntity> steps) {
            return new JobDetailDto(
                    entity.getId(),
                    entity.getTicketId(),
                    entity.getTaskDescription(),
                    entity.getExtraPrompt(),
                    entity.getRepoPath(),
                    entity.getTestCommand(),
                    entity.getBranchName(),
                    entity.getPrUrl(),
                    entity.getStatus(),
                    entity.getSummary(),
                    entity.getGitDiffStat(),
                    entity.getIterations(),
                    new TokenUsageDto(entity.getPromptTokens(), entity.getCompletionTokens(), entity.getTotalTokens()),
                    entity.getCreatedAt(),
                    entity.getStartedAt(),
                    entity.getFinishedAt(),
                    steps.stream().map(JobStepDto::from).toList()
            );
        }
    }
}
