package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.repository.UserRepository;
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
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void status_whenNoAdmin_showsSetupRequired() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.loggedIn").value(false));
    }

    @Test
    void setup_createsRootAdmin() throws Exception {
        SetupRequest request = new SetupRequest();
        request.setUsername("admin");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ROOT_ADMIN"));
    }

    @Test
    void setup_whenAlreadyExists_returns400() throws Exception {
        SetupRequest first = new SetupRequest();
        first.setUsername("admin");
        first.setPassword("password123");

        mockMvc.perform(post("/api/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)));

        SetupRequest second = new SetupRequest();
        second.setUsername("admin2");
        second.setPassword("password456");

        mockMvc.perform(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_withValidCredentials_succeeds() throws Exception {
        // Setup admin first
        SetupRequest setup = new SetupRequest();
        setup.setUsername("admin");
        setup.setPassword("password123");
        mockMvc.perform(post("/api/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(setup)));

        // Login
        LoginRequest login = new LoginRequest();
        login.setUsername("admin");
        login.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void login_withInvalidCredentials_returns401() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("nobody");
        login.setPassword("wrong");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void status_afterLogin_showsLoggedIn() throws Exception {
        // Setup
        SetupRequest setup = new SetupRequest();
        setup.setUsername("admin");
        setup.setPassword("password123");
        mockMvc.perform(post("/api/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(setup)));

        // Login with session
        MockHttpSession session = new MockHttpSession();
        LoginRequest login = new LoginRequest();
        login.setUsername("admin");
        login.setPassword("password123");
        mockMvc.perform(post("/api/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)));

        // Check status
        mockMvc.perform(get("/api/auth/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(true))
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void logout_clearsSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk());
    }
}
