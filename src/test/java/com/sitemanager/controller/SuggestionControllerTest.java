package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.SuggestionRequest;
import com.sitemanager.dto.VoteRequest;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.*;
import com.sitemanager.service.SiteSettingsService;
import com.sitemanager.service.UserGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SuggestionControllerTest {

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
    private SiteSettingsRepository settingsRepository;

    @Autowired
    private SiteSettingsService settingsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

    private UserGroup defaultGroup;

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        messageRepository.deleteAll();
        suggestionRepository.deleteAll();
        settingsRepository.deleteAll();
        userRepository.deleteAll();
        userGroupRepository.findAll().stream()
                .filter(g -> !UserGroupService.DEFAULT_GROUP_NAME.equals(g.getName()))
                .forEach(userGroupRepository::delete);

        defaultGroup = userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME)
                .orElseGet(() -> userGroupRepository.save(
                        new UserGroup(UserGroupService.DEFAULT_GROUP_NAME, true, true, true, false, false, false)));
    }

    @Test
    void listSuggestions_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createSuggestion_asAnonymous_whenAllowed() throws Exception {
        // Ensure anonymous suggestions allowed
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Add dark mode");
        request.setDescription("The site should support dark mode for better readability at night.");
        request.setAuthorName("John");

        mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Add dark mode"))
                .andExpect(jsonPath("$.authorName").value("John"));
    }

    @Test
    void createSuggestion_asAnonymous_whenDisallowed_returns403() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(false);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Add dark mode");
        request.setDescription("Some description");

        mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSuggestion_asLoggedInUser_succeeds() throws Exception {
        User user = createApprovedUser("testuser", true, false, false, false, false);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", user.getUsername());
        session.setAttribute("userId", user.getId());
        session.setAttribute("role", "USER");

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Improve navigation");
        request.setDescription("The navigation bar should be sticky and have better mobile support.");

        mockMvc.perform(post("/api/suggestions")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName").value("testuser"));
    }

    @Test
    void createSuggestion_asLoggedInUser_withoutPermission_returns403() throws Exception {
        // User in a group with canCreateSuggestions=false
        UserGroup restrictedGroup = userGroupRepository.save(
                new UserGroup("Restricted", false, false, false, false, false, false));
        User user = new User("restricteduser", "hash", UserRole.USER);
        user.setGroup(restrictedGroup);
        user.setApproved(true);
        user = userRepository.save(user);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", user.getUsername());
        session.setAttribute("userId", user.getId());
        session.setAttribute("role", "USER");

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Cannot create");
        request.setDescription("This should be blocked.");

        mockMvc.perform(post("/api/suggestions")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSuggestion_byId_returnsDetail() throws Exception {
        // Create one first
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Test suggestion");
        request.setDescription("Test description");

        MvcResult createResult = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/suggestions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Test suggestion"));
    }

    @Test
    void getSuggestion_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/suggestions/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void approveSuggestion_asAdmin_succeeds() throws Exception {
        // Create suggestion
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Test");
        request.setDescription("Test desc");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        // Approve as admin
        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(post("/api/suggestions/" + id + "/approve")
                        .session(adminSession))
                .andExpect(status().isOk());
    }

    @Test
    void approveSuggestion_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/suggestions/1/approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    void denySuggestion_asAdmin_succeeds() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Test");
        request.setDescription("Test desc");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ADMIN");

        mockMvc.perform(post("/api/suggestions/" + id + "/deny")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not feasible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));
    }

    @Test
    void vote_onSuggestion_updatesVotes() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settings.setAllowVoting(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Vote test");
        request.setDescription("Test voting");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        User voter = createApprovedUser("voter1", false, true, false, false, false);
        MockHttpSession voterSession = new MockHttpSession();
        voterSession.setAttribute("username", voter.getUsername());
        voterSession.setAttribute("userId", voter.getId());
        voterSession.setAttribute("role", "USER");

        VoteRequest voteReq = new VoteRequest();
        voteReq.setValue(1);
        voteReq.setVoterIdentifier("voter1");

        mockMvc.perform(post("/api/suggestions/" + id + "/vote")
                        .session(voterSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upVotes").value(1));
    }

    @Test
    void vote_onSuggestion_withoutPermission_returns403() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settings.setAllowVoting(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Vote test");
        request.setDescription("Test voting");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        VoteRequest voteReq = new VoteRequest();
        voteReq.setValue(1);

        // No session = unauthenticated
        mockMvc.perform(post("/api/suggestions/" + id + "/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getMessages_returnsMessagesForSuggestion() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Message test");
        request.setDescription("Test messages");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/suggestions/" + id + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").isNumber());
    }

    @Test
    void getReviewSummary_returnsNotFound_forMissingSuggestion() throws Exception {
        mockMvc.perform(get("/api/suggestions/99999/review-summary"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReviewSummary_returnsEmptyArray_whenNoReviewNotes() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Review summary test");
        request.setDescription("Testing review summary endpoint");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        // No expert review notes — summary still returns all experts as PENDING
        mockMvc.perform(get("/api/suggestions/" + id + "/review-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getReviewSummary_returnsPendingEntries_forAllExperts() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Pending summary test");
        request.setDescription("Verify PENDING entries returned for all experts");

        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/suggestions/" + id + "/review-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].expertName").isString())
                .andExpect(jsonPath("$[0].keyPoint").value("No review yet"));
    }

    @Test
    void getReviewSummary_returnsCorrectStatusForReviewedExperts() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Expert notes test");
        request.setDescription("Testing expert notes parsing");

        MvcResult createResult = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // Directly set expert review notes on the suggestion
        com.sitemanager.model.Suggestion suggestion = suggestionRepository.findById(Long.parseLong(id)).orElseThrow();
        suggestion.setExpertReviewNotes("**Software Architect**: The plan looks solid and well-structured.\n\n**Security Engineer**: There is a concern about input validation.");
        suggestion.setExpertReviewStep(2);
        suggestionRepository.save(suggestion);

        // Software Architect is first in review order, Security Engineer is second
        mockMvc.perform(get("/api/suggestions/" + id + "/review-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].expertName").value("Software Architect"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[1].expertName").value("Security Engineer"))
                .andExpect(jsonPath("$[1].status").value("FLAGGED"));
    }

    // --- Search and filter tests ---

    @Test
    void listSuggestions_searchByTitle_returnsMatchingResults() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest r1 = new SuggestionRequest();
        r1.setTitle("Add dark mode feature");
        r1.setDescription("Support dark theme for the site.");
        mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andReturn();

        SuggestionRequest r2 = new SuggestionRequest();
        r2.setTitle("Improve search speed");
        r2.setDescription("Search is too slow on large data.");
        mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r2))).andReturn();

        mockMvc.perform(get("/api/suggestions").param("search", "dark mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Add dark mode feature"));
    }

    @Test
    void listSuggestions_searchByDescription_returnsMatchingResults() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest r1 = new SuggestionRequest();
        r1.setTitle("Feature A");
        r1.setDescription("This is about performance optimization.");
        mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andReturn();

        SuggestionRequest r2 = new SuggestionRequest();
        r2.setTitle("Feature B");
        r2.setDescription("This is about user experience improvements.");
        mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r2))).andReturn();

        mockMvc.perform(get("/api/suggestions").param("search", "performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Feature A"));
    }

    @Test
    void listSuggestions_filterByStatus_returnsOnlyMatchingStatus() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest r1 = new SuggestionRequest();
        r1.setTitle("Merged suggestion");
        r1.setDescription("This one has been merged.");
        MvcResult res1 = mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andReturn();
        String id1 = objectMapper.readTree(res1.getResponse().getContentAsString()).get("id").asText();

        SuggestionRequest r2 = new SuggestionRequest();
        r2.setTitle("Denied suggestion");
        r2.setDescription("This one has been denied.");
        MvcResult res2 = mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r2))).andReturn();
        String id2 = objectMapper.readTree(res2.getResponse().getContentAsString()).get("id").asText();

        // Explicitly set both to distinct terminal statuses
        com.sitemanager.model.Suggestion s1 = suggestionRepository.findById(Long.parseLong(id1)).orElseThrow();
        s1.setStatus(com.sitemanager.model.enums.SuggestionStatus.MERGED);
        suggestionRepository.save(s1);

        com.sitemanager.model.Suggestion s2 = suggestionRepository.findById(Long.parseLong(id2)).orElseThrow();
        s2.setStatus(com.sitemanager.model.enums.SuggestionStatus.DENIED);
        suggestionRepository.save(s2);

        mockMvc.perform(get("/api/suggestions").param("status", "MERGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Merged suggestion"));

        mockMvc.perform(get("/api/suggestions").param("status", "DENIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Denied suggestion"));
    }

    @Test
    void listSuggestions_filterByUnknownStatus_returnsAllResults() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest r1 = new SuggestionRequest();
        r1.setTitle("Test suggestion");
        r1.setDescription("Testing unknown status filter.");
        mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andReturn();

        mockMvc.perform(get("/api/suggestions").param("status", "INVALID_STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listSuggestions_sortByVotesDesc_returnsHighestVotesFirst() throws Exception {
        // Use direct repository saves to avoid async AI evaluation race conditions
        com.sitemanager.model.Suggestion s1 = new com.sitemanager.model.Suggestion();
        s1.setTitle("Low votes suggestion");
        s1.setDescription("This has fewer votes.");
        s1.setAuthorName("Test");
        s1.setUpVotes(2);
        s1.setStatus(com.sitemanager.model.enums.SuggestionStatus.DENIED);
        suggestionRepository.save(s1);

        com.sitemanager.model.Suggestion s2 = new com.sitemanager.model.Suggestion();
        s2.setTitle("High votes suggestion");
        s2.setDescription("This has more votes.");
        s2.setAuthorName("Test");
        s2.setUpVotes(10);
        s2.setStatus(com.sitemanager.model.enums.SuggestionStatus.DENIED);
        suggestionRepository.save(s2);

        mockMvc.perform(get("/api/suggestions").param("sortBy", "votes").param("sortDir", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("High votes suggestion"))
                .andExpect(jsonPath("$[1].title").value("Low votes suggestion"));
    }

    @Test
    void listSuggestions_sortByVotesAsc_returnsLowestVotesFirst() throws Exception {
        // Use direct repository saves to avoid async AI evaluation race conditions
        com.sitemanager.model.Suggestion s1 = new com.sitemanager.model.Suggestion();
        s1.setTitle("Low votes");
        s1.setDescription("Fewest upvotes.");
        s1.setAuthorName("Test");
        s1.setUpVotes(1);
        s1.setStatus(com.sitemanager.model.enums.SuggestionStatus.DENIED);
        suggestionRepository.save(s1);

        com.sitemanager.model.Suggestion s2 = new com.sitemanager.model.Suggestion();
        s2.setTitle("High votes");
        s2.setDescription("Most upvotes.");
        s2.setAuthorName("Test");
        s2.setUpVotes(5);
        s2.setStatus(com.sitemanager.model.enums.SuggestionStatus.DENIED);
        suggestionRepository.save(s2);

        mockMvc.perform(get("/api/suggestions").param("sortBy", "votes").param("sortDir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Low votes"));
    }

    @Test
    void listSuggestions_searchAndFilterCombined_returnsIntersection() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        SuggestionRequest r1 = new SuggestionRequest();
        r1.setTitle("Search feature draft");
        r1.setDescription("Add a search bar to the site.");
        mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r1))).andReturn();

        SuggestionRequest r2 = new SuggestionRequest();
        r2.setTitle("Search feature merged");
        r2.setDescription("Already merged search implementation.");
        MvcResult res2 = mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(r2))).andReturn();
        String id2 = objectMapper.readTree(res2.getResponse().getContentAsString()).get("id").asText();

        com.sitemanager.model.Suggestion s2 = suggestionRepository.findById(Long.parseLong(id2)).orElseThrow();
        s2.setStatus(com.sitemanager.model.enums.SuggestionStatus.MERGED);
        suggestionRepository.save(s2);

        mockMvc.perform(get("/api/suggestions").param("search", "search feature").param("status", "MERGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Search feature merged"));
    }

    @Test
    void listSuggestions_noParams_returnsAllSortedByCreatedDesc() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);

        for (int i = 1; i <= 3; i++) {
            SuggestionRequest r = new SuggestionRequest();
            r.setTitle("Suggestion " + i);
            r.setDescription("Description " + i);
            mockMvc.perform(post("/api/suggestions").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(r))).andReturn();
        }

        // No params — fallback to getAllSuggestions (createdAt desc)
        mockMvc.perform(get("/api/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].title").value("Suggestion 3"));
    }

    // --- helpers ---

    /**
     * Creates a user in the default group with the given permissions, approved=true.
     */
    private User createApprovedUser(String username, boolean canCreate, boolean canVote,
                                    boolean canReply, boolean canApproveDeny, boolean canManageUsers) {
        UserGroup group = userGroupRepository.save(new UserGroup(
                "Group-" + username, canCreate, canVote, canReply, canApproveDeny, false, canManageUsers));
        User user = new User(username, "hash", UserRole.USER);
        user.setGroup(group);
        user.setApproved(true);
        return userRepository.save(user);
    }
}
