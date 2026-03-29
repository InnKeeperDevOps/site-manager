package com.sitemanager.controller;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.Vote;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.VoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ContributorControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
        s.setDescription("desc");
        s.setAuthorId(authorId);
        s.setAuthorName(authorName);
        s.setStatus(status);
        return suggestionRepository.save(s);
    }

    // ── GET /api/contributors/leaderboard ───────────────────────────────────────

    @Test
    void leaderboard_noAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/contributors/leaderboard"))
                .andExpect(status().isOk());
    }

    @Test
    void leaderboard_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/contributors/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void leaderboard_returnsContributorFields() throws Exception {
        save("Feature A", 1L, "alice", SuggestionStatus.MERGED);

        mockMvc.perform(get("/api/contributors/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].authorId").value("1"))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].score").isNumber())
                .andExpect(jsonPath("$[0].totalSubmissions").value(1))
                .andExpect(jsonPath("$[0].mergedSuggestions").value(1));
    }

    @Test
    void leaderboard_rankedByScoreDescending() throws Exception {
        save("Alice Merged", 1L, "alice", SuggestionStatus.MERGED);
        save("Bob Draft", 2L, "bob", SuggestionStatus.DRAFT);

        mockMvc.perform(get("/api/contributors/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[1].username").value("bob"));
    }

    @Test
    void leaderboard_includesUpvoteCount() throws Exception {
        Suggestion s = save("Alice Feature", 1L, "alice", SuggestionStatus.APPROVED);
        voteRepository.save(new Vote(s.getId(), "voter1", 1));
        voteRepository.save(new Vote(s.getId(), "voter2", 1));

        mockMvc.perform(get("/api/contributors/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalUpvotesReceived").value(2));
    }

    @Test
    void leaderboard_excludesAnonymousSuggestions() throws Exception {
        Suggestion anon = new Suggestion();
        anon.setTitle("Anon");
        anon.setDescription("d");
        anon.setAuthorName("anon");
        anon.setStatus(SuggestionStatus.APPROVED);
        suggestionRepository.save(anon);

        mockMvc.perform(get("/api/contributors/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /api/contributors/{authorId}/history ────────────────────────────────

    @Test
    void history_noAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/contributors/9999/history"))
                .andExpect(status().isOk());
    }

    @Test
    void history_unknownAuthor_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/contributors/9999/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void history_returnsOnlyThatAuthorsEntries() throws Exception {
        save("Alice S1", 1L, "alice", SuggestionStatus.MERGED);
        save("Bob S1", 2L, "bob", SuggestionStatus.DRAFT);

        mockMvc.perform(get("/api/contributors/1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Alice S1"));
    }

    @Test
    void history_returnsSummaryFields() throws Exception {
        Suggestion s = save("My Feature", 1L, "alice", SuggestionStatus.MERGED);
        s.setPrUrl("https://github.com/org/repo/pull/1");
        suggestionRepository.save(s);

        mockMvc.perform(get("/api/contributors/1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("My Feature"))
                .andExpect(jsonPath("$[0].status").value("MERGED"))
                .andExpect(jsonPath("$[0].prUrl").value("https://github.com/org/repo/pull/1"))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }
}
