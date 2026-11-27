package com.taskpilot.agent.queue;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "taskpilot.queue", name = "mode", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqConfig {

    @Bean
    public Queue taskpilotJobsQueue(@Value("${taskpilot.queue.name:taskpilot.jobs.queue}") String queueName) {
        return new Queue(queueName, true);
    }
}
