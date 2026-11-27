package com.taskpilot.agent.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobStepRepository extends JpaRepository<JobStepEntity, Long> {

    List<JobStepEntity> findByJobIdOrderByStepNumberAsc(Long jobId);

    Optional<JobStepEntity> findByJobIdAndStepNumber(Long jobId, int stepNumber);
}
