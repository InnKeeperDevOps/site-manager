package com.sitemanager.service;

import com.sitemanager.dto.ContributorStatsDto;
import com.sitemanager.dto.SuggestionSummaryDto;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.Vote;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.VoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ContributorServiceTest {

    @Autowired
    private ContributorService contributorService;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private SuggestionMessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        messageRepository.deleteAll();
        suggestionRepository.deleteAll();
    }

    private Suggestion save(String title, Long authorId, String authorName, SuggestionStatus status) {
        Suggestion s = new Suggestion();
        s.setTitle(title);
        s.setDescription("d");
        s.setAuthorId(authorId);
        s.setAuthorName(authorName);
        s.setStatus(status);
        return suggestionRepository.save(s);
    }

    // ── getLeaderboard ─────────────────────────────────────────────────────────

    @Test
    void getLeaderboard_empty_returnsEmptyList() {
        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertTrue(result.isEmpty());
    }

    @Test
    void getLeaderboard_excludesNullAuthorId() {
        Suggestion s = new Suggestion();
        s.setTitle("Anon");
        s.setDescription("d");
        s.setAuthorName("anon");
        s.setStatus(SuggestionStatus.APPROVED);
        suggestionRepository.save(s);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertTrue(result.isEmpty());
    }

    @Test
    void getLeaderboard_singleContributor_basicFields() {
        save("Alice Draft", 1L, "alice", SuggestionStatus.DRAFT);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(1, result.size());

        ContributorStatsDto dto = result.get(0);
        assertEquals("1", dto.getAuthorId());
        assertEquals("alice", dto.getUsername());
        assertEquals(1, dto.getTotalSubmissions());
        assertEquals(0, dto.getMergedSuggestions());
        assertEquals(0, dto.getApprovedSuggestions());
        assertEquals(1, dto.getRank());
    }

    @Test
    void getLeaderboard_mergedCountedInBothMergedAndApproved() {
        save("Alice Merged", 1L, "alice", SuggestionStatus.MERGED);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(1, result.size());

        ContributorStatsDto dto = result.get(0);
        assertEquals(1, dto.getMergedSuggestions());
        assertEquals(1, dto.getApprovedSuggestions());
    }

    @Test
    void getLeaderboard_scoreFormula_mergedGivesPlusSeven() {
        // merged*5 + approved*2 + totalSubmissions = 5 + 2 + 1 = 8
        save("Alice Merged", 1L, "alice", SuggestionStatus.MERGED);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(8, result.get(0).getScore());
    }

    @Test
    void getLeaderboard_scoreFormula_approvedGivesPlusThree() {
        // merged*5 + approved*2 + totalSubmissions = 0 + 2 + 1 = 3
        save("Alice Approved", 1L, "alice", SuggestionStatus.APPROVED);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(3, result.get(0).getScore());
    }

    @Test
    void getLeaderboard_approvedStatusesAllCountAsApproved() {
        save("In Progress", 1L, "alice", SuggestionStatus.IN_PROGRESS);
        save("Testing", 1L, "alice", SuggestionStatus.TESTING);
        save("Dev Complete", 1L, "alice", SuggestionStatus.DEV_COMPLETE);
        save("Final Review", 1L, "alice", SuggestionStatus.FINAL_REVIEW);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(1, result.size());
        assertEquals(4, result.get(0).getApprovedSuggestions());
        assertEquals(4, result.get(0).getTotalSubmissions());
    }

    @Test
    void getLeaderboard_rankedByScoreDescending() {
        save("Alice Merged", 1L, "alice", SuggestionStatus.MERGED); // score 8
        save("Bob Draft", 2L, "bob", SuggestionStatus.DRAFT);       // score 1

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).getUsername());
        assertEquals(1, result.get(0).getRank());
        assertEquals("bob", result.get(1).getUsername());
        assertEquals(2, result.get(1).getRank());
    }

    @Test
    void getLeaderboard_upvotesIncluded() {
        Suggestion s = save("Alice Approved", 1L, "alice", SuggestionStatus.APPROVED);
        voteRepository.save(new Vote(s.getId(), "voter1", 1));
        voteRepository.save(new Vote(s.getId(), "voter2", 1));

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(2, result.get(0).getTotalUpvotesReceived());
    }

    @Test
    void getLeaderboard_downvotesNotCounted() {
        Suggestion s = save("Alice Approved", 1L, "alice", SuggestionStatus.APPROVED);
        voteRepository.save(new Vote(s.getId(), "voter1", -1));
        voteRepository.save(new Vote(s.getId(), "voter2", -1));

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(0, result.get(0).getTotalUpvotesReceived());
    }

    @Test
    void getLeaderboard_multipleContributors_correctCounts() {
        save("Alice Merged", 1L, "alice", SuggestionStatus.MERGED);
        save("Alice Draft", 1L, "alice", SuggestionStatus.DRAFT);
        save("Bob Approved", 2L, "bob", SuggestionStatus.APPROVED);

        List<ContributorStatsDto> result = contributorService.getLeaderboard();
        assertEquals(2, result.size());

        ContributorStatsDto alice = result.stream()
                .filter(d -> "alice".equals(d.getUsername())).findFirst().orElseThrow();
        assertEquals(2, alice.getTotalSubmissions());
        assertEquals(1, alice.getMergedSuggestions());
        assertEquals(1, alice.getApprovedSuggestions());

        ContributorStatsDto bob = result.stream()
                .filter(d -> "bob".equals(d.getUsername())).findFirst().orElseThrow();
        assertEquals(1, bob.getTotalSubmissions());
        assertEquals(0, bob.getMergedSuggestions());
        assertEquals(1, bob.getApprovedSuggestions());
    }

    // ── getUserHistory ──────────────────────────────────────────────────────────

    @Test
    void getUserHistory_unknownAuthor_returnsEmpty() {
        List<SuggestionSummaryDto> result = contributorService.getUserHistory("9999");
        assertTrue(result.isEmpty());
    }

    @Test
    void getUserHistory_returnsOnlyThatAuthorsSuggestions() {
        save("Alice S1", 1L, "alice", SuggestionStatus.MERGED);
        save("Bob S1", 2L, "bob", SuggestionStatus.DRAFT);

        List<SuggestionSummaryDto> result = contributorService.getUserHistory("1");
        assertEquals(1, result.size());
        assertEquals("Alice S1", result.get(0).getTitle());
    }

    @Test
    void getUserHistory_returnedInDescendingOrder() {
        Suggestion old = save("Old", 1L, "alice", SuggestionStatus.DRAFT);
        old.setCreatedAt(Instant.now().minusSeconds(100));
        suggestionRepository.save(old);

        save("Recent", 1L, "alice", SuggestionStatus.APPROVED);

        List<SuggestionSummaryDto> result = contributorService.getUserHistory("1");
        assertEquals(2, result.size());
        assertEquals("Recent", result.get(0).getTitle());
        assertEquals("Old", result.get(1).getTitle());
    }

    @Test
    void getUserHistory_mapsFieldsCorrectly() {
        Suggestion s = save("My Feature", 1L, "alice", SuggestionStatus.MERGED);
        s.setPrUrl("https://github.com/org/repo/pull/42");
        suggestionRepository.save(s);

        List<SuggestionSummaryDto> result = contributorService.getUserHistory("1");
        SuggestionSummaryDto dto = result.get(0);
        assertEquals("My Feature", dto.getTitle());
        assertEquals(SuggestionStatus.MERGED, dto.getStatus());
        assertEquals("https://github.com/org/repo/pull/42", dto.getPrUrl());
        assertNotNull(dto.getCreatedAt());
    }
}
