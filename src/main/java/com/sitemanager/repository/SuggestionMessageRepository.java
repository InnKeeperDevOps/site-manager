package com.sitemanager.repository;

import com.sitemanager.model.SuggestionMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SuggestionMessageRepository extends JpaRepository<SuggestionMessage, Long> {
    List<SuggestionMessage> findBySuggestionIdOrderByCreatedAtAsc(Long suggestionId);
}
