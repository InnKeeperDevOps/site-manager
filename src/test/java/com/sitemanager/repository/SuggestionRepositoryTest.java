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
