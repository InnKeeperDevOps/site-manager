package com.sitemanager.repository;

import com.sitemanager.model.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VoteRepository extends JpaRepository<Vote, Long> {
    Optional<Vote> findBySuggestionIdAndVoterIdentifier(Long suggestionId, String voterIdentifier);
    int countBySuggestionIdAndValue(Long suggestionId, int value);
}
