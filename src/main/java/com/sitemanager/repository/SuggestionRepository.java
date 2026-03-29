package com.sitemanager.repository;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SuggestionRepository extends JpaRepository<Suggestion, Long>, JpaSpecificationExecutor<Suggestion> {
    List<Suggestion> findAllByOrderByCreatedAtDesc();
    List<Suggestion> findByStatus(SuggestionStatus status);
    List<Suggestion> findByStatusInAndLastActivityAtBefore(List<SuggestionStatus> statuses, Instant before);
    List<Suggestion> findByStatusIn(List<SuggestionStatus> statuses);
    List<Suggestion> findByStatusAndAuthorName(SuggestionStatus status, String authorName);

    @Query("SELECT s FROM Suggestion s WHERE s.status <> com.sitemanager.model.enums.SuggestionStatus.DRAFT OR s.authorName = :authorName ORDER BY s.createdAt DESC")
    List<Suggestion> findAllExcludingOthersDrafts(@Param("authorName") String authorName);
}
