package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.VoteRepository;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VoteServiceTest {

    @Autowired
    private VoteService voteService;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @Autowired
    private SiteSettingsService settingsService;

    private Suggestion testSuggestion;

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        suggestionRepository.deleteAll();
        settingsRepository.deleteAll();

        testSuggestion = new Suggestion();
        testSuggestion.setTitle("Test Suggestion");
        testSuggestion.setDescription("Test desc");
        testSuggestion.setStatus(SuggestionStatus.DISCUSSING);
        testSuggestion.setAuthorName("tester");
        testSuggestion = suggestionRepository.save(testSuggestion);

        // Ensure voting is enabled
        var settings = settingsService.getSettings();
        settings.setAllowVoting(true);
        settingsRepository.save(settings);
    }

    @Test
    void vote_upvote_incrementsCount() {
        Suggestion result = voteService.vote(testSuggestion.getId(), "voter1", 1);
        assertEquals(1, result.getUpVotes());
        assertEquals(0, result.getDownVotes());
    }

    @Test
    void vote_downvote_incrementsCount() {
        Suggestion result = voteService.vote(testSuggestion.getId(), "voter1", -1);
        assertEquals(0, result.getUpVotes());
        assertEquals(1, result.getDownVotes());
    }

    @Test
    void vote_toggleOff_removesVote() {
        voteService.vote(testSuggestion.getId(), "voter1", 1);
        Suggestion result = voteService.vote(testSuggestion.getId(), "voter1", 1);
        assertEquals(0, result.getUpVotes());
    }

    @Test
    void vote_changeVote_switchesDirection() {
        voteService.vote(testSuggestion.getId(), "voter1", 1);
        Suggestion result = voteService.vote(testSuggestion.getId(), "voter1", -1);
        assertEquals(0, result.getUpVotes());
        assertEquals(1, result.getDownVotes());
    }

    @Test
    void vote_multipleVoters_countCorrectly() {
        voteService.vote(testSuggestion.getId(), "voter1", 1);
        voteService.vote(testSuggestion.getId(), "voter2", 1);
        Suggestion result = voteService.vote(testSuggestion.getId(), "voter3", -1);
        assertEquals(2, result.getUpVotes());
        assertEquals(1, result.getDownVotes());
    }

    @Test
    void vote_invalidValue_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> voteService.vote(testSuggestion.getId(), "voter1", 2));
    }

    @Test
    void vote_nonExistentSuggestion_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> voteService.vote(9999L, "voter1", 1));
    }

    @Test
    void vote_whenVotingDisabled_throwsException() {
        var settings = settingsService.getSettings();
        settings.setAllowVoting(false);
        settingsRepository.save(settings);

        assertThrows(IllegalStateException.class,
                () -> voteService.vote(testSuggestion.getId(), "voter1", 1));
    }
}
