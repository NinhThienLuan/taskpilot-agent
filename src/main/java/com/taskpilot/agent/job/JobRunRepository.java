package com.taskpilot.agent.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {

    List<JobRunEntity> findTop20ByOrderByCreatedAtDesc();
}
