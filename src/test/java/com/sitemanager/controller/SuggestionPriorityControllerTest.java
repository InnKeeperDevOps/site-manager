package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.SuggestionRequest;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.Priority;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SuggestionPriorityControllerTest {

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

        SiteSettings settings = settingsService.getSettings();
        settings.setAllowAnonymousSuggestions(true);
        settingsRepository.save(settings);
    }

    // --- Priority defaults ---

    @Test
    void createSuggestion_withoutPriority_defaultsMedium() throws Exception {
        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Default priority test");
        request.setDescription("No priority set, should default to MEDIUM.");

        mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
    }

    @Test
    void createSuggestion_withHighPriority_persistsCorrectly() throws Exception {
        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("High priority feature");
        request.setDescription("This is urgent and should be HIGH priority.");
        request.setPriority(Priority.HIGH);

        mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void createSuggestion_withLowPriority_persistsCorrectly() throws Exception {
        SuggestionRequest request = new SuggestionRequest();
        request.setTitle("Low priority feature");
        request.setDescription("Nice to have, but not urgent.");
        request.setPriority(Priority.LOW);

        mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("LOW"));
    }

    // --- PATCH /priority endpoint ---

    @Test
    void updatePriority_asAdmin_succeeds() throws Exception {
        Long id = createSuggestion("Priority patch test", "Testing admin priority change.");

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(patch("/api/suggestions/" + id + "/priority")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void updatePriority_asAuthor_succeeds() throws Exception {
        User author = createApprovedUser("author1");
        Long id = createSuggestionAsUser(author, "Author priority test", "Author changes own suggestion priority.");

        MockHttpSession authorSession = new MockHttpSession();
        authorSession.setAttribute("username", author.getUsername());
        authorSession.setAttribute("userId", author.getId());
        authorSession.setAttribute("role", "USER");

        mockMvc.perform(patch("/api/suggestions/" + id + "/priority")
                        .session(authorSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("LOW"));
    }

    @Test
    void updatePriority_asNonAuthorUser_returns403() throws Exception {
        User author = createApprovedUser("originalAuthor");
        Long id = createSuggestionAsUser(author, "Owned suggestion", "Only the author can change this.");

        User other = createApprovedUser("otherUser");
        MockHttpSession otherSession = new MockHttpSession();
        otherSession.setAttribute("username", other.getUsername());
        otherSession.setAttribute("userId", other.getId());
        otherSession.setAttribute("role", "USER");

        mockMvc.perform(patch("/api/suggestions/" + id + "/priority")
                        .session(otherSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"HIGH\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatePriority_unauthenticated_returns403() throws Exception {
        Long id = createSuggestion("Unauth priority test", "No session.");

        mockMvc.perform(patch("/api/suggestions/" + id + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"HIGH\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatePriority_invalidValue_returns400() throws Exception {
        Long id = createSuggestion("Invalid priority test", "Bad priority value.");

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(patch("/api/suggestions/" + id + "/priority")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"URGENT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePriority_nonExistentSuggestion_returns404() throws Exception {
        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(patch("/api/suggestions/99999/priority")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"HIGH\"}"))
                .andExpect(status().isNotFound());
    }

    // --- Filter by priority ---

    @Test
    void listSuggestions_filterByPriority_returnsOnlyMatchingPriority() throws Exception {
        Suggestion high = buildSuggestion("High priority suggestion", Priority.HIGH);
        Suggestion medium = buildSuggestion("Medium priority suggestion", Priority.MEDIUM);
        Suggestion low = buildSuggestion("Low priority suggestion", Priority.LOW);
        suggestionRepository.save(high);
        suggestionRepository.save(medium);
        suggestionRepository.save(low);

        mockMvc.perform(get("/api/suggestions").param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("High priority suggestion"));

        mockMvc.perform(get("/api/suggestions").param("priority", "LOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Low priority suggestion"));
    }

    @Test
    void listSuggestions_filterByUnknownPriority_returnsAll() throws Exception {
        Suggestion s = buildSuggestion("Some suggestion", Priority.MEDIUM);
        suggestionRepository.save(s);

        mockMvc.perform(get("/api/suggestions").param("priority", "CRITICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // --- Sort by priority ---

    @Test
    void listSuggestions_sortByPriorityDesc_returnsHighFirst() throws Exception {
        suggestionRepository.save(buildSuggestion("Low", Priority.LOW));
        suggestionRepository.save(buildSuggestion("High", Priority.HIGH));
        suggestionRepository.save(buildSuggestion("Medium", Priority.MEDIUM));

        mockMvc.perform(get("/api/suggestions")
                        .param("sortBy", "priority")
                        .param("sortDir", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].priority").value("HIGH"))
                .andExpect(jsonPath("$[1].priority").value("MEDIUM"))
                .andExpect(jsonPath("$[2].priority").value("LOW"));
    }

    @Test
    void listSuggestions_sortByPriorityAsc_returnsLowFirst() throws Exception {
        suggestionRepository.save(buildSuggestion("Medium", Priority.MEDIUM));
        suggestionRepository.save(buildSuggestion("High", Priority.HIGH));
        suggestionRepository.save(buildSuggestion("Low", Priority.LOW));

        mockMvc.perform(get("/api/suggestions")
                        .param("sortBy", "priority")
                        .param("sortDir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].priority").value("LOW"))
                .andExpect(jsonPath("$[1].priority").value("MEDIUM"))
                .andExpect(jsonPath("$[2].priority").value("HIGH"));
    }

    @Test
    void listSuggestions_priorityFilterAndStatusFilter_returnsIntersection() throws Exception {
        Suggestion highDraft = buildSuggestion("High Draft", Priority.HIGH);
        Suggestion highMerged = buildSuggestion("High Merged", Priority.HIGH);
        highMerged.setStatus(com.sitemanager.model.enums.SuggestionStatus.MERGED);
        Suggestion lowDraft = buildSuggestion("Low Draft", Priority.LOW);
        suggestionRepository.save(highDraft);
        suggestionRepository.save(highMerged);
        suggestionRepository.save(lowDraft);

        mockMvc.perform(get("/api/suggestions")
                        .param("priority", "HIGH")
                        .param("status", "MERGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("High Merged"));
    }

    // --- helpers ---

    private Long createSuggestion(String title, String description) throws Exception {
        SuggestionRequest req = new SuggestionRequest();
        req.setTitle(title);
        req.setDescription(description);
        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long createSuggestionAsUser(User user, String title, String description) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", user.getUsername());
        session.setAttribute("userId", user.getId());
        session.setAttribute("role", "USER");

        SuggestionRequest req = new SuggestionRequest();
        req.setTitle(title);
        req.setDescription(description);
        MvcResult result = mockMvc.perform(post("/api/suggestions")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private User createApprovedUser(String username) {
        UserGroup group = userGroupRepository.save(
                new UserGroup("Group-" + username, true, true, true, false, false, false));
        User user = new User(username, "hash", UserRole.USER);
        user.setGroup(group);
        user.setApproved(true);
        return userRepository.save(user);
    }

    private Suggestion buildSuggestion(String title, Priority priority) {
        Suggestion s = new Suggestion();
        s.setTitle(title);
        s.setDescription("Test description for " + title);
        s.setAuthorName("Test");
        s.setStatus(com.sitemanager.model.enums.SuggestionStatus.DRAFT);
        s.setPriority(priority);
        return s;
    }
}
