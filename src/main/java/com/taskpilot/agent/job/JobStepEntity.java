package com.taskpilot.agent.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_steps")
public class JobStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false)
    private int stepNumber;

    @Column(nullable = false, length = 64)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StepStatus status = StepStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String logOutput;

    private long durationMs;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public JobStepEntity() {
    }

    public JobStepEntity(Long jobId, int stepNumber, String stepName) {
        this.jobId = jobId;
        this.stepNumber = stepNumber;
        this.stepName = stepName;
        this.status = StepStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getJobId() {
        return jobId;
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public String getStepName() {
        return stepName;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getLogOutput() {
        return logOutput;
    }

    public void setLogOutput(String logOutput) {
        this.logOutput = logOutput;
        this.updatedAt = Instant.now();
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
