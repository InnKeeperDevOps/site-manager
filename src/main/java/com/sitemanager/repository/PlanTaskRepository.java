package com.sitemanager.repository;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanTaskRepository extends JpaRepository<PlanTask, Long> {
    List<PlanTask> findBySuggestionIdOrderByTaskOrder(Long suggestionId);
    Optional<PlanTask> findBySuggestionIdAndTaskOrder(Long suggestionId, int taskOrder);
    List<PlanTask> findBySuggestionIdAndStatus(Long suggestionId, TaskStatus status);
    void deleteBySuggestionId(Long suggestionId);
}
