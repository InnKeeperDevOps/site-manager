package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.SiteSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SiteSettingsService settingsService;

    @MockBean
    private ClaudeService claudeService;

    private static final String VALID_RESPONSE =
            "[{\"title\":\"Add email notifications\",\"description\":\"Notify users when their suggestions are approved.\"}," +
            "{\"title\":\"Improve search\",\"description\":\"Allow filtering suggestions by keyword.\"}," +
            "{\"title\":\"Add tagging\",\"description\":\"Let users tag suggestions by category.\"}," +
            "{\"title\":\"Bulk actions\",\"description\":\"Allow admins to approve multiple suggestions at once.\"}," +
            "{\"title\":\"Dashboard metrics\",\"description\":\"Show a summary of suggestion activity on the main page.\"}]";

    @BeforeEach
    void setUp() throws Exception {
        suggestionRepository.deleteAll();
        settingsRepository.deleteAll();
        userRepository.deleteAll();
        when(claudeService.getMainRepoDir()).thenReturn("/tmp/test-main-repo");
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "ADMIN");
        session.setAttribute("username", "admin");
        session.setAttribute("userId", 1L);
        return session;
    }

    private MockHttpSession rootAdminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "ROOT_ADMIN");
        session.setAttribute("username", "root");
        session.setAttribute("userId", 2L);
        return session;
    }

    @Test
    void getRecommendations_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getRecommendations_asAdmin_returnsRecommendations() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].title").value("Add email notifications"))
                .andExpect(jsonPath("$[0].description").value("Notify users when their suggestions are approved."));
    }

    @Test
    void getRecommendations_asRootAdmin_returnsRecommendations() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        mockMvc.perform(post("/api/recommendations")
                        .session(rootAdminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void getRecommendations_onTimeout_returns504() throws Exception {
        when(claudeService.getRecommendations(any())).thenThrow(new RuntimeException("Claude CLI timed out after 30 minutes"));

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("The AI took too long to respond. Please try again in a moment."));
    }

    @Test
    void getRecommendations_onMalformedResponse_returns500() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("This is not JSON at all");

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("The AI returned an unexpected response. Please try again."));
    }

    @Test
    void getRecommendations_onEmptyResponse_returns500() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("");

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getRecommendations_onUnexpectedException_returns500() throws Exception {
        when(claudeService.getRecommendations(any())).thenThrow(new RuntimeException("API error: status 503"));

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getRecommendations_promptIncludesSiteName() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setSiteName("My Awesome Site");
        settingsRepository.save(settings);

        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Verify ClaudeService was called (captured indirectly via result)
        org.mockito.Mockito.verify(claudeService).getRecommendations(
                org.mockito.ArgumentMatchers.contains("My Awesome Site"));
    }

    @Test
    void getRecommendations_withPartialValidResponse_returnsValidItems() throws Exception {
        // Response with only some items having titles — still parses what it can
        String partialResponse = "[{\"title\":\"Valid title\",\"description\":\"Valid desc\"}," +
                "{\"description\":\"Missing title - should be skipped\"}]";
        when(claudeService.getRecommendations(any())).thenReturn(partialResponse);

        mockMvc.perform(post("/api/recommendations")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Valid title"));
    }

    @Test
    void buildPrompt_includesAllSiteInfo() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        SiteSettings settings = new SiteSettings();
        settings.setSiteName("Test Site");
        settings.setTargetRepoUrl("https://github.com/example/repo");
        settings.setAllowAnonymousSuggestions(true);
        settings.setAllowVoting(false);
        settings.setRequireApproval(true);
        settings.setAutoMergePr(false);

        Map<SuggestionStatus, Long> statusCounts = Map.of(
                SuggestionStatus.DRAFT, 3L,
                SuggestionStatus.APPROVED, 2L
        );

        String prompt = controller.buildPrompt(settings, statusCounts, 5, null);

        assert prompt.contains("Test Site");
        assert prompt.contains("https://github.com/example/repo");
        assert prompt.contains("DRAFT");
        assert prompt.contains("APPROVED");
        assert prompt.contains("5");
    }

    @Test
    void buildPrompt_withProjectDefinition_includesDefinitionAndComparisonInstructions() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        SiteSettings settings = new SiteSettings();
        settings.setSiteName("Test Site");

        String definition = "# Project Definition\n## Overview\nA task management tool.";
        String prompt = controller.buildPrompt(settings, Map.of(), 0, definition);

        assert prompt.contains("PROJECT DEFINITION");
        assert prompt.contains("A task management tool.");
        assert prompt.contains("Compare the PROJECT DEFINITION");
        assert prompt.contains("gaps");
    }

    @Test
    void buildPrompt_withoutProjectDefinition_suggestsCodebaseImprovements() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        SiteSettings settings = new SiteSettings();
        settings.setSiteName("Test Site");

        String prompt = controller.buildPrompt(settings, Map.of(), 0, null);

        assert !prompt.contains("PROJECT DEFINITION");
        assert prompt.contains("no PROJECT_DEFINITION.md");
        assert prompt.contains("Analyze the codebase");
    }

    @Test
    void parseRecommendations_withValidJson_returnsList() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        var result = controller.parseRecommendations(VALID_RESPONSE);

        assert result.size() == 5;
        assert result.get(0).get("title").equals("Add email notifications");
        assert result.get(0).get("description").equals("Notify users when their suggestions are approved.");
    }

    @Test
    void parseRecommendations_withPreambleBeforeJson_extractsArray() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        String responseWithPreamble = "Here are my recommendations:\n" +
                "[{\"title\":\"Improve search\",\"description\":\"Add search functionality.\"}]";

        var result = controller.parseRecommendations(responseWithPreamble);

        assert result.size() == 1;
        assert result.get(0).get("title").equals("Improve search");
    }

    @Test
    void parseRecommendations_withNoJsonArray_throwsIllegalStateException() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        try {
            controller.parseRecommendations("No JSON here at all.");
            assert false : "Expected IllegalStateException";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    void parseRecommendations_withNullInput_throwsIllegalStateException() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper);

        try {
            controller.parseRecommendations(null);
            assert false : "Expected IllegalStateException";
        } catch (IllegalStateException e) {
            // expected
        }
    }
}
