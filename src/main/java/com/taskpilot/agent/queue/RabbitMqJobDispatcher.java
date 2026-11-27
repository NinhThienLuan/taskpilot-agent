package com.taskpilot.agent.queue;

import com.taskpilot.agent.job.AsyncJobPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ producer & consumer following the Claim-Check pattern:
 * publishes only the lightweight {@code jobId} to RabbitMQ while full state lives in PostgreSQL.
 */
@Component
@ConditionalOnProperty(prefix = "taskpilot.queue", name = "mode", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqJobDispatcher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AsyncJobPipelineService pipelineService;
    private final String queueName;

    public RabbitMqJobDispatcher(
            RabbitTemplate rabbitTemplate,
            AsyncJobPipelineService pipelineService,
            @Value("${taskpilot.queue.name:taskpilot.jobs.queue}") String queueName
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.pipelineService = pipelineService;
        this.queueName = queueName;
    }

    @Override
    public void dispatch(Long jobId) {
        log.info("Publishing jobId={} to RabbitMQ queue '{}'", jobId, queueName);
        rabbitTemplate.convertAndSend(queueName, String.valueOf(jobId));
    }

    @RabbitListener(queues = "${taskpilot.queue.name:taskpilot.jobs.queue}")
    public void onJobMessage(String jobIdPayload) {
        Long jobId = Long.parseLong(jobIdPayload.trim());
        log.info("Consumed jobId={} from RabbitMQ queue '{}'", jobId, queueName);
        pipelineService.processJob(jobId);
    }
}
