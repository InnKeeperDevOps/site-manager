package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.RegisterRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Autowired
    private SiteSettingsRepository siteSettingsRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // Ensure the default 'Registered User' group exists (another test class may have deleted it)
        if (userGroupRepository.findByName("Registered User").isEmpty()) {
            userGroupRepository.save(new UserGroup("Registered User", true, true, true, false, false, false));
        }
        // Reset requireRegistrationApproval to false (default) before each test
        SiteSettings settings = siteSettingsRepository.findAll().stream()
                .findFirst().orElseGet(SiteSettings::new);
        settings.setRequireRegistrationApproval(false);
        siteSettingsRepository.save(settings);
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
    void login_whenUserPendingApproval_returns401WithMessage() throws Exception {
        User pending = new User("pendinguser", passwordEncoder.encode("pass123"), UserRole.USER);
        pending.setApproved(false);
        pending.setDenied(false);
        userRepository.save(pending);

        LoginRequest login = new LoginRequest();
        login.setUsername("pendinguser");
        login.setPassword("pass123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Account pending admin approval"));
    }

    @Test
    void login_whenUserDenied_returns401WithMessage() throws Exception {
        User denied = new User("denieduser", passwordEncoder.encode("pass123"), UserRole.USER);
        denied.setApproved(false);
        denied.setDenied(true);
        userRepository.save(denied);

        LoginRequest login = new LoginRequest();
        login.setUsername("denieduser");
        login.setPassword("pass123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Account registration was denied"));
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

    @Test
    void register_withApprovalNotRequired_returnsApprovedUser() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.pending").value(false));
    }

    @Test
    void register_withApprovalRequired_returnsPendingUser() throws Exception {
        SiteSettings settings = siteSettingsRepository.findAll().stream()
                .findFirst().orElseGet(SiteSettings::new);
        settings.setRequireRegistrationApproval(true);
        siteSettingsRepository.save(settings);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("pendinguser");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pendinguser"))
                .andExpect(jsonPath("$.pending").value(true));
    }

    @Test
    void register_withDuplicateUsername_returns400() throws Exception {
        RegisterRequest first = new RegisterRequest();
        first.setUsername("dupuser");
        first.setPassword("password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)));

        RegisterRequest second = new RegisterRequest();
        second.setUsername("dupuser");
        second.setPassword("different123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Username already taken"));
    }

    @Test
    void register_withShortUsername_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("ab");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_withShortPassword_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("validuser");
        request.setPassword("short");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
