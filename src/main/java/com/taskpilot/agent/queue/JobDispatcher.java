package com.taskpilot.agent.queue;

/**
 * Dispatches a persisted job ID to an asynchronous execution queue.
 */
public interface JobDispatcher {

    void dispatch(Long jobId);
}
