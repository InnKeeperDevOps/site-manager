package com.sitemanager.repository;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SuggestionRepositoryTest {

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private SuggestionMessageRepository messageRepository;

    @Autowired
    private VoteRepository voteRepository;

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        messageRepository.deleteAll();
        suggestionRepository.deleteAll();
    }

    @Test
    void findAllByOrderByCreatedAtDesc_returnsInOrder() {
        Suggestion s1 = new Suggestion();
        s1.setTitle("First");
        s1.setDescription("desc");
        s1.setStatus(SuggestionStatus.DRAFT);
        s1.setAuthorName("a");
        s1.setCreatedAt(Instant.now().minusSeconds(10));
        suggestionRepository.save(s1);

        Suggestion s2 = new Suggestion();
        s2.setTitle("Second");
        s2.setDescription("desc");
        s2.setStatus(SuggestionStatus.DRAFT);
        s2.setAuthorName("b");
        suggestionRepository.save(s2);

        List<Suggestion> list = suggestionRepository.findAllByOrderByCreatedAtDesc();
        assertEquals(2, list.size());
        assertEquals("Second", list.get(0).getTitle());
    }

    @Test
    void findByStatus_filtersCorrectly() {
        Suggestion s1 = new Suggestion();
        s1.setTitle("Draft");
        s1.setDescription("d");
        s1.setStatus(SuggestionStatus.DRAFT);
        s1.setAuthorName("a");
        suggestionRepository.save(s1);

        Suggestion s2 = new Suggestion();
        s2.setTitle("Approved");
        s2.setDescription("d");
        s2.setStatus(SuggestionStatus.APPROVED);
        s2.setAuthorName("b");
        suggestionRepository.save(s2);

        List<Suggestion> drafts = suggestionRepository.findByStatus(SuggestionStatus.DRAFT);
        assertEquals(1, drafts.size());
        assertEquals("Draft", drafts.get(0).getTitle());
    }

    @Test
    void findByStatusInAndLastActivityAtBefore_findsStale() {
        Suggestion stale = new Suggestion();
        stale.setTitle("Stale");
        stale.setDescription("d");
        stale.setStatus(SuggestionStatus.DISCUSSING);
        stale.setAuthorName("a");
        stale.setLastActivityAt(Instant.now().minus(2, ChronoUnit.DAYS));
        suggestionRepository.save(stale);

        Suggestion fresh = new Suggestion();
        fresh.setTitle("Fresh");
        fresh.setDescription("d");
        fresh.setStatus(SuggestionStatus.DISCUSSING);
        fresh.setAuthorName("b");
        suggestionRepository.save(fresh);

        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        List<Suggestion> staleList = suggestionRepository.findByStatusInAndLastActivityAtBefore(
                List.of(SuggestionStatus.DRAFT, SuggestionStatus.DISCUSSING), cutoff);

        assertEquals(1, staleList.size());
        assertEquals("Stale", staleList.get(0).getTitle());
    }

    @Test
    void findByStatusAndAuthorName_returnsMatchingDrafts() {
        Suggestion draft1 = new Suggestion();
        draft1.setTitle("My Draft");
        draft1.setDescription("d");
        draft1.setStatus(SuggestionStatus.DRAFT);
        draft1.setAuthorName("alice");
        suggestionRepository.save(draft1);

        Suggestion draft2 = new Suggestion();
        draft2.setTitle("Other Draft");
        draft2.setDescription("d");
        draft2.setStatus(SuggestionStatus.DRAFT);
        draft2.setAuthorName("bob");
        suggestionRepository.save(draft2);

        Suggestion approved = new Suggestion();
        approved.setTitle("Approved by alice");
        approved.setDescription("d");
        approved.setStatus(SuggestionStatus.APPROVED);
        approved.setAuthorName("alice");
        suggestionRepository.save(approved);

        List<Suggestion> aliceDrafts = suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "alice");
        assertEquals(1, aliceDrafts.size());
        assertEquals("My Draft", aliceDrafts.get(0).getTitle());
    }

    @Test
    void findAllExcludingOthersDrafts_excludesOtherUsersDrafts() {
        Suggestion aliceDraft = new Suggestion();
        aliceDraft.setTitle("Alice Draft");
        aliceDraft.setDescription("d");
        aliceDraft.setStatus(SuggestionStatus.DRAFT);
        aliceDraft.setAuthorName("alice");
        suggestionRepository.save(aliceDraft);

        Suggestion bobDraft = new Suggestion();
        bobDraft.setTitle("Bob Draft");
        bobDraft.setDescription("d");
        bobDraft.setStatus(SuggestionStatus.DRAFT);
        bobDraft.setAuthorName("bob");
        suggestionRepository.save(bobDraft);

        Suggestion bobApproved = new Suggestion();
        bobApproved.setTitle("Bob Approved");
        bobApproved.setDescription("d");
        bobApproved.setStatus(SuggestionStatus.APPROVED);
        bobApproved.setAuthorName("bob");
        suggestionRepository.save(bobApproved);

        // Alice should see her own draft + bob's approved, but NOT bob's draft
        List<Suggestion> aliceView = suggestionRepository.findAllExcludingOthersDrafts("alice");
        assertEquals(2, aliceView.size());
        List<String> titles = aliceView.stream().map(Suggestion::getTitle).toList();
        assertTrue(titles.contains("Alice Draft"));
        assertTrue(titles.contains("Bob Approved"));
        assertFalse(titles.contains("Bob Draft"));
    }

    @Test
    void findAllExcludingOthersDrafts_returnsAllNonDrafts() {
        Suggestion s1 = new Suggestion();
        s1.setTitle("Discussing");
        s1.setDescription("d");
        s1.setStatus(SuggestionStatus.DISCUSSING);
        s1.setAuthorName("alice");
        suggestionRepository.save(s1);

        Suggestion s2 = new Suggestion();
        s2.setTitle("Approved");
        s2.setDescription("d");
        s2.setStatus(SuggestionStatus.APPROVED);
        s2.setAuthorName("bob");
        suggestionRepository.save(s2);

        // Any user should see all non-draft suggestions
        List<Suggestion> result = suggestionRepository.findAllExcludingOthersDrafts("charlie");
        assertEquals(2, result.size());
    }

    @Test
    void save_setsTimestamps() {
        Suggestion s = new Suggestion();
        s.setTitle("Test");
        s.setDescription("d");
        s.setStatus(SuggestionStatus.DRAFT);
        s.setAuthorName("a");

        Suggestion saved = suggestionRepository.save(s);
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertNotNull(saved.getLastActivityAt());
    }
}
