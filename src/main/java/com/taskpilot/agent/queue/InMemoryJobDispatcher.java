package com.taskpilot.agent.queue;

import com.taskpilot.agent.job.AsyncJobPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-memory fallback dispatcher for tests or local REST testing without RabbitMQ.
 * Activated via {@code taskpilot.queue.mode=in-memory}.
 */
@Component
@ConditionalOnProperty(prefix = "taskpilot.queue", name = "mode", havingValue = "in-memory")
public class InMemoryJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryJobDispatcher.class);

    private final AsyncJobPipelineService pipelineService;
    private final boolean sync;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public InMemoryJobDispatcher(
            AsyncJobPipelineService pipelineService,
            @Value("${taskpilot.queue.in-memory-sync:false}") boolean sync
    ) {
        this.pipelineService = pipelineService;
        this.sync = sync;
    }

    @Override
    public void dispatch(Long jobId) {
        log.info("Dispatching jobId={} via in-memory dispatcher (sync={})", jobId, sync);
        if (sync) {
            pipelineService.processJob(jobId);
        } else {
            executor.submit(() -> pipelineService.processJob(jobId));
        }
    }
}
