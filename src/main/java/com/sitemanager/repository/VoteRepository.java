package com.sitemanager.repository;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoteRepository extends JpaRepository<Vote, Long> {
    Optional<Vote> findBySuggestionIdAndVoterIdentifier(Long suggestionId, String voterIdentifier);
    int countBySuggestionIdAndValue(Long suggestionId, int value);

    @Query("SELECT s.authorId, COUNT(v) FROM Vote v, Suggestion s WHERE v.suggestionId = s.id AND v.value > 0 AND s.authorId IS NOT NULL GROUP BY s.authorId")
    List<Object[]> findUpvoteCountsByAuthorId();
}
