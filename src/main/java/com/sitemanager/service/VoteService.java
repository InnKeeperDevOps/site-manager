package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.Vote;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.VoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class VoteService {

    private final VoteRepository voteRepository;
    private final SuggestionRepository suggestionRepository;
    private final SiteSettingsService settingsService;

    public VoteService(VoteRepository voteRepository, SuggestionRepository suggestionRepository,
                       SiteSettingsService settingsService) {
        this.voteRepository = voteRepository;
        this.suggestionRepository = suggestionRepository;
        this.settingsService = settingsService;
    }

    @Transactional
    public Suggestion vote(Long suggestionId, String voterIdentifier, int value) {
        if (!settingsService.getSettings().isAllowVoting()) {
            throw new IllegalStateException("Voting is disabled");
        }
        if (value != 1 && value != -1) {
            throw new IllegalArgumentException("Vote value must be 1 or -1");
        }

        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        Optional<Vote> existing = voteRepository.findBySuggestionIdAndVoterIdentifier(suggestionId, voterIdentifier);

        if (existing.isPresent()) {
            Vote vote = existing.get();
            if (vote.getValue() == value) {
                // Remove vote (toggle off)
                voteRepository.delete(vote);
            } else {
                vote.setValue(value);
                voteRepository.save(vote);
            }
        } else {
            voteRepository.save(new Vote(suggestionId, voterIdentifier, value));
        }

        // Recalculate counts
        suggestion.setUpVotes(voteRepository.countBySuggestionIdAndValue(suggestionId, 1));
        suggestion.setDownVotes(voteRepository.countBySuggestionIdAndValue(suggestionId, -1));
        return suggestionRepository.save(suggestion);
    }
}
