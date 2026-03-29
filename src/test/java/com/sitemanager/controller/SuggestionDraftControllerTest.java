package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.UpdateDraftRequest;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.Priority;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.repository.VoteRepository;
import com.sitemanager.service.UserGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SuggestionDraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private SuggestionMessageRepository messageRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

    private User alice;
    private User bob;
    private MockHttpSession aliceSession;
    private MockHttpSession bobSession;

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        messageRepository.deleteAll();
        suggestionRepository.deleteAll();
        userRepository.deleteAll();
        userGroupRepository.findAll().stream()
                .filter(g -> !UserGroupService.DEFAULT_GROUP_NAME.equals(g.getName()))
                .forEach(userGroupRepository::delete);

        UserGroup group = userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME)
                .orElseGet(() -> userGroupRepository.save(
                        new UserGroup(UserGroupService.DEFAULT_GROUP_NAME, true, true, true, false, false, false)));

        alice = new User("alice", "hash", UserRole.USER);
        alice.setGroup(group);
        alice.setApproved(true);
        alice = userRepository.save(alice);

        bob = new User("bob", "hash", UserRole.USER);
        bob.setGroup(group);
        bob.setApproved(true);
        bob = userRepository.save(bob);

        aliceSession = new MockHttpSession();
        aliceSession.setAttribute("username", alice.getUsername());
        aliceSession.setAttribute("userId", alice.getId());
        aliceSession.setAttribute("role", "USER");

        bobSession = new MockHttpSession();
        bobSession.setAttribute("username", bob.getUsername());
        bobSession.setAttribute("userId", bob.getId());
        bobSession.setAttribute("role", "USER");
    }

    // --- POST /api/suggestions with isDraft=true ---

    @Test
    void createDraft_withSession_returns200AndIsDraft() throws Exception {
        // Send isDraft as JSON key to match what the frontend sends
        String json = "{\"title\":\"My draft idea\",\"description\":\"I will finish this later.\",\"isDraft\":true}";

        mockMvc.perform(post("/api/suggestions")
                        .session(aliceSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My draft idea"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void createDraft_withoutSession_returns401() throws Exception {
        String json = "{\"title\":\"My draft idea\",\"description\":\"Needs login.\",\"isDraft\":true}";

        mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());
    }

    // --- GET /api/suggestions/my-drafts ---

    @Test
    void getMyDrafts_withoutSession_returns401() throws Exception {
        mockMvc.perform(get("/api/suggestions/my-drafts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyDrafts_withSession_returnsOnlyUserDrafts() throws Exception {
        // Alice creates a draft
        Suggestion aliceDraft = new Suggestion();
        aliceDraft.setTitle("Alice draft");
        aliceDraft.setDescription("Alice's draft");
        aliceDraft.setAuthorName("alice");
        aliceDraft.setAuthorId(alice.getId());
        aliceDraft.setStatus(SuggestionStatus.DRAFT);
        suggestionRepository.save(aliceDraft);

        // Bob creates a draft
        Suggestion bobDraft = new Suggestion();
        bobDraft.setTitle("Bob draft");
        bobDraft.setDescription("Bob's draft");
        bobDraft.setAuthorName("bob");
        bobDraft.setAuthorId(bob.getId());
        bobDraft.setStatus(SuggestionStatus.DRAFT);
        suggestionRepository.save(bobDraft);

        mockMvc.perform(get("/api/suggestions/my-drafts").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Alice draft"));
    }

    @Test
    void getMyDrafts_withSession_returnsEmptyListWhenNoDrafts() throws Exception {
        mockMvc.perform(get("/api/suggestions/my-drafts").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- PATCH /api/suggestions/{id}/draft ---

    @Test
    void updateDraft_withoutSession_returns401() throws Exception {
        Suggestion draft = saveDraft("alice", alice.getId());

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Updated title");
        req.setDescription("Updated description");

        mockMvc.perform(patch("/api/suggestions/" + draft.getId() + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateDraft_byOwner_returnsUpdatedSuggestion() throws Exception {
        Suggestion draft = saveDraft("alice", alice.getId());

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Improved title");
        req.setDescription("Improved description");
        req.setPriority(Priority.HIGH);

        mockMvc.perform(patch("/api/suggestions/" + draft.getId() + "/draft")
                        .session(aliceSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Improved title"))
                .andExpect(jsonPath("$.description").value("Improved description"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void updateDraft_byDifferentUser_returns403() throws Exception {
        Suggestion draft = saveDraft("alice", alice.getId());

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Hacked title");
        req.setDescription("Sneaky edit");

        mockMvc.perform(patch("/api/suggestions/" + draft.getId() + "/draft")
                        .session(bobSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateDraft_nonExistentId_returns404() throws Exception {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Description");

        mockMvc.perform(patch("/api/suggestions/99999/draft")
                        .session(aliceSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateDraft_onNonDraftSuggestion_returns409() throws Exception {
        Suggestion submitted = new Suggestion();
        submitted.setTitle("Already submitted");
        submitted.setDescription("No longer a draft");
        submitted.setAuthorName("alice");
        submitted.setAuthorId(alice.getId());
        submitted.setStatus(SuggestionStatus.DISCUSSING);
        submitted = suggestionRepository.save(submitted);

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("New title");
        req.setDescription("New description");

        mockMvc.perform(patch("/api/suggestions/" + submitted.getId() + "/draft")
                        .session(aliceSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    // --- POST /api/suggestions/{id}/submit ---

    @Test
    void submitDraft_withoutSession_returns401() throws Exception {
        Suggestion draft = saveDraft("alice", alice.getId());

        mockMvc.perform(post("/api/suggestions/" + draft.getId() + "/submit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitDraft_byOwner_returns200() throws Exception {
        Suggestion draft = saveDraft("alice", alice.getId());

        mockMvc.perform(post("/api/suggestions/" + draft.getId() + "/submit")
                        .session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.not("DRAFT")));
    }

    @Test
    void submitDraft_byDifferentUser_returns403() throws Exception {
        Suggestion draft = saveDraft("alice", alice.getId());

        mockMvc.perform(post("/api/suggestions/" + draft.getId() + "/submit")
                        .session(bobSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitDraft_nonExistentId_returns404() throws Exception {
        mockMvc.perform(post("/api/suggestions/99999/submit")
                        .session(aliceSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitDraft_onNonDraftSuggestion_returns409() throws Exception {
        Suggestion submitted = new Suggestion();
        submitted.setTitle("Already submitted");
        submitted.setDescription("No longer a draft");
        submitted.setAuthorName("alice");
        submitted.setAuthorId(alice.getId());
        submitted.setStatus(SuggestionStatus.DISCUSSING);
        submitted = suggestionRepository.save(submitted);

        mockMvc.perform(post("/api/suggestions/" + submitted.getId() + "/submit")
                        .session(aliceSession))
                .andExpect(status().isConflict());
    }

    // --- helpers ---

    private Suggestion saveDraft(String authorName, Long authorId) {
        Suggestion draft = new Suggestion();
        draft.setTitle("Draft title");
        draft.setDescription("Draft description");
        draft.setAuthorName(authorName);
        draft.setAuthorId(authorId);
        draft.setStatus(SuggestionStatus.DRAFT);
        return suggestionRepository.save(draft);
    }
}
