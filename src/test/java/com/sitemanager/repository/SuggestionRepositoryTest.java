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

    @Test
    void findStatusCountsByAuthorId_aggregatesCorrectly() {
        Suggestion s1 = new Suggestion();
        s1.setTitle("Alice Merged");
        s1.setDescription("d");
        s1.setStatus(SuggestionStatus.MERGED);
        s1.setAuthorName("alice");
        s1.setAuthorId(1L);
        suggestionRepository.save(s1);

        Suggestion s2 = new Suggestion();
        s2.setTitle("Alice Draft");
        s2.setDescription("d");
        s2.setStatus(SuggestionStatus.DRAFT);
        s2.setAuthorName("alice");
        s2.setAuthorId(1L);
        suggestionRepository.save(s2);

        Suggestion s3 = new Suggestion();
        s3.setTitle("Bob Approved");
        s3.setDescription("d");
        s3.setStatus(SuggestionStatus.APPROVED);
        s3.setAuthorName("bob");
        s3.setAuthorId(2L);
        suggestionRepository.save(s3);

        // No authorId — should be excluded
        Suggestion s4 = new Suggestion();
        s4.setTitle("Anonymous");
        s4.setDescription("d");
        s4.setStatus(SuggestionStatus.DRAFT);
        s4.setAuthorName("anon");
        suggestionRepository.save(s4);

        List<Object[]> rows = suggestionRepository.findStatusCountsByAuthorId();
        // alice has 2 rows (MERGED + DRAFT), bob has 1 row (APPROVED)
        assertEquals(3, rows.size());

        long aliceMergedCount = rows.stream()
                .filter(r -> Long.valueOf(1L).equals(r[0]) && SuggestionStatus.MERGED.equals(r[2]))
                .mapToLong(r -> (Long) r[3])
                .sum();
        assertEquals(1L, aliceMergedCount);
    }

    @Test
    void findStatusCountsByAuthorId_excludesNullAuthorId() {
        Suggestion s = new Suggestion();
        s.setTitle("No Author");
        s.setDescription("d");
        s.setStatus(SuggestionStatus.APPROVED);
        s.setAuthorName("someone");
        suggestionRepository.save(s);

        List<Object[]> rows = suggestionRepository.findStatusCountsByAuthorId();
        assertTrue(rows.isEmpty());
    }

    @Test
    void findByAuthorIdOrderByCreatedAtDesc_returnsInDescOrder() {
        Suggestion old = new Suggestion();
        old.setTitle("Old");
        old.setDescription("d");
        old.setStatus(SuggestionStatus.MERGED);
        old.setAuthorName("alice");
        old.setAuthorId(1L);
        old.setCreatedAt(Instant.now().minusSeconds(100));
        suggestionRepository.save(old);

        Suggestion recent = new Suggestion();
        recent.setTitle("Recent");
        recent.setDescription("d");
        recent.setStatus(SuggestionStatus.DRAFT);
        recent.setAuthorName("alice");
        recent.setAuthorId(1L);
        suggestionRepository.save(recent);

        Suggestion other = new Suggestion();
        other.setTitle("Other User");
        other.setDescription("d");
        other.setStatus(SuggestionStatus.DRAFT);
        other.setAuthorName("bob");
        other.setAuthorId(2L);
        suggestionRepository.save(other);

        List<Suggestion> aliceHistory = suggestionRepository.findByAuthorIdOrderByCreatedAtDesc(1L);
        assertEquals(2, aliceHistory.size());
        assertEquals("Recent", aliceHistory.get(0).getTitle());
        assertEquals("Old", aliceHistory.get(1).getTitle());
    }

    @Test
    void findByAuthorIdOrderByCreatedAtDesc_returnsEmptyForUnknownAuthor() {
        List<Suggestion> result = suggestionRepository.findByAuthorIdOrderByCreatedAtDesc(999L);
        assertTrue(result.isEmpty());
    }

    @Test
    void findDistinctAuthorIdAndName_returnsUniqueAuthors() {
        for (int i = 0; i < 3; i++) {
            Suggestion s = new Suggestion();
            s.setTitle("Alice " + i);
            s.setDescription("d");
            s.setStatus(SuggestionStatus.DRAFT);
            s.setAuthorName("alice");
            s.setAuthorId(1L);
            suggestionRepository.save(s);
        }

        Suggestion bobSuggestion = new Suggestion();
        bobSuggestion.setTitle("Bob");
        bobSuggestion.setDescription("d");
        bobSuggestion.setStatus(SuggestionStatus.DRAFT);
        bobSuggestion.setAuthorName("bob");
        bobSuggestion.setAuthorId(2L);
        suggestionRepository.save(bobSuggestion);

        // null authorId should be excluded
        Suggestion anon = new Suggestion();
        anon.setTitle("Anon");
        anon.setDescription("d");
        anon.setStatus(SuggestionStatus.DRAFT);
        anon.setAuthorName("anon");
        suggestionRepository.save(anon);

        List<Object[]> pairs = suggestionRepository.findDistinctAuthorIdAndName();
        assertEquals(2, pairs.size());

        boolean hasAlice = pairs.stream().anyMatch(r -> Long.valueOf(1L).equals(r[0]) && "alice".equals(r[1]));
        boolean hasBob = pairs.stream().anyMatch(r -> Long.valueOf(2L).equals(r[0]) && "bob".equals(r[1]));
        assertTrue(hasAlice);
        assertTrue(hasBob);
    }
}
