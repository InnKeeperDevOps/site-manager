package com.sitemanager.repository;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SuggestionRepository extends JpaRepository<Suggestion, Long> {
    List<Suggestion> findAllByOrderByCreatedAtDesc();
    List<Suggestion> findByStatus(SuggestionStatus status);
    List<Suggestion> findByStatusInAndLastActivityAtBefore(List<SuggestionStatus> statuses, Instant before);
    List<Suggestion> findByStatusIn(List<SuggestionStatus> statuses);
}
