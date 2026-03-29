package com.sitemanager.repository;

import com.sitemanager.model.ProjectDefinitionSession;
import com.sitemanager.model.enums.ProjectDefinitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectDefinitionSessionRepository extends JpaRepository<ProjectDefinitionSession, Long> {
    Optional<ProjectDefinitionSession> findFirstByStatusIn(List<ProjectDefinitionStatus> statuses);
    Optional<ProjectDefinitionSession> findTopByOrderByCreatedAtDesc();
}
