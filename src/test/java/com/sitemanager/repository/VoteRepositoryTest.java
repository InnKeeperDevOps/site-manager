package com.sitemanager.repository;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.Vote;
import com.sitemanager.model.enums.SuggestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VoteRepositoryTest {

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private SuggestionMessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        messageRepository.deleteAll();
        suggestionRepository.deleteAll();
    }

    private Suggestion savedSuggestion(String title, Long authorId, String authorName) {
        Suggestion s = new Suggestion();
        s.setTitle(title);
        s.setDescription("d");
        s.setStatus(SuggestionStatus.APPROVED);
        s.setAuthorId(authorId);
        s.setAuthorName(authorName);
        return suggestionRepository.save(s);
    }

    @Test
    void findUpvoteCountsByAuthorId_countsUpvotesPerAuthor() {
        Suggestion alice1 = savedSuggestion("Alice S1", 1L, "alice");
        Suggestion alice2 = savedSuggestion("Alice S2", 1L, "alice");
        Suggestion bob1 = savedSuggestion("Bob S1", 2L, "bob");

        voteRepository.save(new Vote(alice1.getId(), "voter1", 1));
        voteRepository.save(new Vote(alice1.getId(), "voter2", 1));
        voteRepository.save(new Vote(alice2.getId(), "voter3", 1));
        // downvote on alice — should not count
        voteRepository.save(new Vote(alice2.getId(), "voter4", -1));
        voteRepository.save(new Vote(bob1.getId(), "voter5", 1));

        List<Object[]> rows = voteRepository.findUpvoteCountsByAuthorId();
        assertEquals(2, rows.size());

        long aliceUpvotes = rows.stream()
                .filter(r -> Long.valueOf(1L).equals(r[0]))
                .mapToLong(r -> (Long) r[1])
                .findFirst()
                .orElse(0L);
        assertEquals(3L, aliceUpvotes);

        long bobUpvotes = rows.stream()
                .filter(r -> Long.valueOf(2L).equals(r[0]))
                .mapToLong(r -> (Long) r[1])
                .findFirst()
                .orElse(0L);
        assertEquals(1L, bobUpvotes);
    }

    @Test
    void findUpvoteCountsByAuthorId_excludesDownvotes() {
        Suggestion s = savedSuggestion("Alice S", 1L, "alice");
        voteRepository.save(new Vote(s.getId(), "voter1", -1));
        voteRepository.save(new Vote(s.getId(), "voter2", -1));

        List<Object[]> rows = voteRepository.findUpvoteCountsByAuthorId();
        assertTrue(rows.isEmpty());
    }

    @Test
    void findUpvoteCountsByAuthorId_excludesSuggestionsWithNullAuthorId() {
        Suggestion anon = new Suggestion();
        anon.setTitle("Anon");
        anon.setDescription("d");
        anon.setStatus(SuggestionStatus.APPROVED);
        anon.setAuthorName("anon");
        anon = suggestionRepository.save(anon);

        voteRepository.save(new Vote(anon.getId(), "voter1", 1));

        List<Object[]> rows = voteRepository.findUpvoteCountsByAuthorId();
        assertTrue(rows.isEmpty());
    }

    @Test
    void findUpvoteCountsByAuthorId_returnsEmptyWhenNoVotes() {
        savedSuggestion("Alice S", 1L, "alice");
        List<Object[]> rows = voteRepository.findUpvoteCountsByAuthorId();
        assertTrue(rows.isEmpty());
    }

    @Test
    void findBySuggestionIdAndVoterIdentifier_findsExistingVote() {
        Suggestion s = savedSuggestion("Test", 1L, "alice");
        voteRepository.save(new Vote(s.getId(), "voter1", 1));

        Optional<Vote> found = voteRepository.findBySuggestionIdAndVoterIdentifier(s.getId(), "voter1");
        assertTrue(found.isPresent());
        assertEquals(1, found.get().getValue());
    }

    @Test
    void countBySuggestionIdAndValue_countsCorrectly() {
        Suggestion s = savedSuggestion("Test", 1L, "alice");
        voteRepository.save(new Vote(s.getId(), "voter1", 1));
        voteRepository.save(new Vote(s.getId(), "voter2", 1));
        voteRepository.save(new Vote(s.getId(), "voter3", -1));

        assertEquals(2, voteRepository.countBySuggestionIdAndValue(s.getId(), 1));
        assertEquals(1, voteRepository.countBySuggestionIdAndValue(s.getId(), -1));
    }
}
