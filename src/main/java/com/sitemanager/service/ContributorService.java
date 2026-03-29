package com.sitemanager.service;

import com.sitemanager.dto.ContributorStatsDto;
import com.sitemanager.dto.SuggestionSummaryDto;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.VoteRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContributorService {

    private final SuggestionRepository suggestionRepository;
    private final VoteRepository voteRepository;

    public ContributorService(SuggestionRepository suggestionRepository, VoteRepository voteRepository) {
        this.suggestionRepository = suggestionRepository;
        this.voteRepository = voteRepository;
    }

    public List<ContributorStatsDto> getLeaderboard() {
        Map<String, ContributorStatsDto> byAuthorId = new LinkedHashMap<>();

        // [authorId(Long), authorName(String), status(SuggestionStatus), count(Long)]
        List<Object[]> statusCounts = suggestionRepository.findStatusCountsByAuthorId();
        for (Object[] row : statusCounts) {
            Long authorId = (Long) row[0];
            String authorName = (String) row[1];
            SuggestionStatus status = (SuggestionStatus) row[2];
            long count = (Long) row[3];

            String key = authorId.toString();
            ContributorStatsDto dto = byAuthorId.computeIfAbsent(key, k -> {
                ContributorStatsDto d = new ContributorStatsDto();
                d.setAuthorId(key);
                d.setUsername(authorName);
                return d;
            });

            dto.setTotalSubmissions(dto.getTotalSubmissions() + (int) count);

            if (status == SuggestionStatus.MERGED) {
                dto.setMergedSuggestions(dto.getMergedSuggestions() + (int) count);
            }

            // MERGED is intentionally also counted here (+2), giving +7 total for merged
            // — reflecting that merged work passed the full pipeline
            if (status == SuggestionStatus.APPROVED
                    || status == SuggestionStatus.IN_PROGRESS
                    || status == SuggestionStatus.TESTING
                    || status == SuggestionStatus.DEV_COMPLETE
                    || status == SuggestionStatus.FINAL_REVIEW
                    || status == SuggestionStatus.MERGED) {
                dto.setApprovedSuggestions(dto.getApprovedSuggestions() + (int) count);
            }
        }

        // [authorId(Long), upvoteCount(Long)]
        List<Object[]> upvoteCounts = voteRepository.findUpvoteCountsByAuthorId();
        for (Object[] row : upvoteCounts) {
            Long authorId = (Long) row[0];
            long upvotes = (Long) row[1];
            ContributorStatsDto dto = byAuthorId.get(authorId.toString());
            if (dto != null) {
                dto.setTotalUpvotesReceived((int) upvotes);
            }
        }

        // score = merged*5 + approved*2 + totalSubmissions
        // MERGED contributes +5; MERGED is also included in approvedSuggestions (+2) intentionally
        // for a total of +7 per merged suggestion — reflecting that merged work passed the full pipeline
        for (ContributorStatsDto dto : byAuthorId.values()) {
            int score = dto.getMergedSuggestions() * 5
                    + dto.getApprovedSuggestions() * 2
                    + dto.getTotalSubmissions();
            dto.setScore(score);
        }

        List<ContributorStatsDto> sorted = byAuthorId.values().stream()
                .sorted(Comparator.comparingInt(ContributorStatsDto::getScore).reversed())
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setRank(i + 1);
        }

        return sorted;
    }

    public List<SuggestionSummaryDto> getUserHistory(String authorId) {
        Long id = Long.parseLong(authorId);
        List<Suggestion> suggestions = suggestionRepository.findByAuthorIdOrderByCreatedAtDesc(id);
        return suggestions.stream()
                .map(SuggestionSummaryDto::from)
                .collect(Collectors.toList());
    }
}
