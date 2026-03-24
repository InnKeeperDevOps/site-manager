package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.repository.SiteSettingsRepository;
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
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @BeforeEach
    void setUp() {
        settingsRepository.deleteAll();
    }

    @Test
    void getSettings_returnsDefaults() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousSuggestions").value(true))
                .andExpect(jsonPath("$.allowVoting").value(true));
    }

    @Test
    void updateSettings_asAdmin_succeeds() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(put("/api/settings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":false,\"allowVoting\":false," +
                                "\"targetRepoUrl\":\"https://github.com/test/repo\"," +
                                "\"suggestionTimeoutMinutes\":60,\"requireApproval\":true," +
                                "\"siteName\":\"Test Site\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousSuggestions").value(false))
                .andExpect(jsonPath("$.siteName").value("Test Site"));
    }

    @Test
    void updateSettings_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowVoting\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateSettings_asRegularAdmin_succeeds() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin2");
        session.setAttribute("role", "ADMIN");

        mockMvc.perform(put("/api/settings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                                "\"siteName\":\"Updated\"}"))
                .andExpect(status().isOk());
    }
}
