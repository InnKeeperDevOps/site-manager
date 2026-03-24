package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.SuggestionRequest;
import com.sitemanager.dto.VoteRequest;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.*;
import com.sitemanager.service.SiteSettingsService;
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

    @BeforeEach
    void setUp() {
        voteRepository.deleteAll();
        messageRepository.deleteAll();
        suggestionRepository.deleteAll();
        settingsRepository.deleteAll();
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
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "testuser");
        session.setAttribute("userId", 1L);
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

        VoteRequest voteReq = new VoteRequest();
        voteReq.setValue(1);
        voteReq.setVoterIdentifier("voter1");

        mockMvc.perform(post("/api/suggestions/" + id + "/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upVotes").value(1));
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
}
