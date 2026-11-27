package com.taskpilot.agent.api;

import com.taskpilot.agent.config.SandboxProperties;
import com.taskpilot.agent.job.AsyncJobPipelineService;
import com.taskpilot.agent.job.JobRunEntity;
import com.taskpilot.agent.job.JobRunRepository;
import com.taskpilot.agent.job.JobStatus;
import com.taskpilot.agent.job.JobStepEntity;
import com.taskpilot.agent.job.JobStepRepository;
import com.taskpilot.agent.queue.JobDispatcher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class JobController {

    private final JobRunRepository jobRunRepository;
    private final JobStepRepository jobStepRepository;
    private final AsyncJobPipelineService pipelineService;
    private final JobDispatcher jobDispatcher;
    private final SandboxProperties sandboxProperties;
    private final Environment environment;
    private final String queueMode;

    public JobController(
            JobRunRepository jobRunRepository,
            JobStepRepository jobStepRepository,
            AsyncJobPipelineService pipelineService,
            JobDispatcher jobDispatcher,
            SandboxProperties sandboxProperties,
            Environment environment,
            @Value("${taskpilot.queue.mode:rabbitmq}") String queueMode
    ) {
        this.jobRunRepository = jobRunRepository;
        this.jobStepRepository = jobStepRepository;
        this.pipelineService = pipelineService;
        this.jobDispatcher = jobDispatcher;
        this.sandboxProperties = sandboxProperties;
        this.environment = environment;
        this.queueMode = queueMode;
    }

    @PostMapping("/jobs")
    public ResponseEntity<JobResponses.JobSummaryDto> createJob(@Valid @RequestBody CreateJobRequest request) {
        JobRunEntity job = new JobRunEntity();
        job.setTicketId(request.ticketId().trim());
        job.setTaskDescription(request.taskDescription().trim());
        job.setExtraPrompt(request.extraPrompt() == null ? "" : request.extraPrompt().trim());
        job.setRepoPath(request.repoPath().trim());
        job.setTestCommand(request.testCommand() == null ? "" : request.testCommand().trim());
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(Instant.now());

        JobRunEntity saved = jobRunRepository.save(job);
        pipelineService.initializeSteps(saved.getId());
        jobDispatcher.dispatch(saved.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponses.JobSummaryDto.from(saved));
    }

    @GetMapping("/jobs")
    public List<JobResponses.JobSummaryDto> listRecentJobs() {
        return jobRunRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(JobResponses.JobSummaryDto::from)
                .toList();
    }

    @GetMapping("/jobs/{id}")
    public JobResponses.JobDetailDto getJob(@PathVariable Long id) {
        JobRunEntity job = jobRunRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));
        List<JobStepEntity> steps = jobStepRepository.findByJobIdOrderByStepNumberAsc(id);
        return JobResponses.JobDetailDto.from(job, steps);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<String> profiles = environment.getActiveProfiles().length > 0
                ? Arrays.asList(environment.getActiveProfiles())
                : Arrays.asList(environment.getDefaultProfiles());
        return Map.of(
                "status", "READY",
                "profiles", profiles,
                "sandboxMode", sandboxProperties.getMode(),
                "queueMode", queueMode
        );
    }
}
