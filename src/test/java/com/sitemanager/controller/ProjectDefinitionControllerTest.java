package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.ProjectDefinitionStateResponse;
import com.sitemanager.model.enums.ProjectDefinitionStatus;
import com.sitemanager.repository.ProjectDefinitionSessionRepository;
import com.sitemanager.service.ProjectDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectDefinitionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProjectDefinitionService projectDefinitionService;

    @Autowired
    private ProjectDefinitionSessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
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

    private MockHttpSession userSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        session.setAttribute("username", "someuser");
        session.setAttribute("userId", 3L);
        return session;
    }

    private ProjectDefinitionStateResponse activeState() {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        resp.setSessionId(1L);
        resp.setStatus(ProjectDefinitionStatus.ACTIVE);
        return resp;
    }

    // ---- POST /start ----

    @Test
    void start_asAdmin_returns200() throws Exception {
        when(projectDefinitionService.startSession()).thenReturn(activeState());

        mockMvc.perform(post("/api/project-definition/start")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void start_asRootAdmin_returns200() throws Exception {
        when(projectDefinitionService.startSession()).thenReturn(activeState());

        mockMvc.perform(post("/api/project-definition/start")
                        .session(rootAdminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void start_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/project-definition/start"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void start_asRegularUser_returns403() throws Exception {
        mockMvc.perform(post("/api/project-definition/start")
                        .session(userSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void start_whenSessionAlreadyActive_returns409() throws Exception {
        when(projectDefinitionService.startSession())
                .thenThrow(new IllegalStateException("A project definition session is already in progress"));

        mockMvc.perform(post("/api/project-definition/start")
                        .session(adminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---- POST /{id}/answer ----

    @Test
    void answer_asAdmin_returns200() throws Exception {
        ProjectDefinitionStateResponse resp = activeState();
        when(projectDefinitionService.submitAnswer(eq(1L), eq("My answer"))).thenReturn(resp);

        mockMvc.perform(post("/api/project-definition/1/answer")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"My answer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(1));
    }

    @Test
    void answer_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/project-definition/1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"My answer\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void answer_withBlankAnswer_returns400() throws Exception {
        mockMvc.perform(post("/api/project-definition/1/answer")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answer_withOversizedAnswer_returns400() throws Exception {
        String oversized = "x".repeat(10001);
        mockMvc.perform(post("/api/project-definition/1/answer")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"" + oversized + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answer_sessionNotFound_returns404() throws Exception {
        when(projectDefinitionService.submitAnswer(eq(99L), anyString()))
                .thenThrow(new IllegalArgumentException("Session not found: 99"));

        mockMvc.perform(post("/api/project-definition/99/answer")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"Something\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void answer_sessionNotActive_returns409() throws Exception {
        when(projectDefinitionService.submitAnswer(eq(1L), anyString()))
                .thenThrow(new IllegalStateException("Session is not active: COMPLETED"));

        mockMvc.perform(post("/api/project-definition/1/answer")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"Something\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---- GET /state ----

    @Test
    void getState_asAuthenticatedUser_returns200WithState() throws Exception {
        when(projectDefinitionService.getState()).thenReturn(activeState());

        mockMvc.perform(get("/api/project-definition/state")
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(1));
    }

    @Test
    void getState_asAdmin_returns200() throws Exception {
        when(projectDefinitionService.getState()).thenReturn(activeState());

        mockMvc.perform(get("/api/project-definition/state")
                        .session(adminSession()))
                .andExpect(status().isOk());
    }

    @Test
    void getState_withoutSession_returns401() throws Exception {
        mockMvc.perform(get("/api/project-definition/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getState_whenNoSessionExists_returns204() throws Exception {
        when(projectDefinitionService.getState()).thenReturn(null);

        mockMvc.perform(get("/api/project-definition/state")
                        .session(userSession()))
                .andExpect(status().isNoContent());
    }

    // ---- POST /reset ----

    @Test
    void reset_asAdmin_returns200() throws Exception {
        doNothing().when(projectDefinitionService).resetSession();

        mockMvc.perform(post("/api/project-definition/reset")
                        .session(adminSession()))
                .andExpect(status().isOk());

        verify(projectDefinitionService).resetSession();
    }

    @Test
    void reset_asRootAdmin_returns200() throws Exception {
        doNothing().when(projectDefinitionService).resetSession();

        mockMvc.perform(post("/api/project-definition/reset")
                        .session(rootAdminSession()))
                .andExpect(status().isOk());
    }

    @Test
    void reset_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/project-definition/reset"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void reset_asRegularUser_returns403() throws Exception {
        mockMvc.perform(post("/api/project-definition/reset")
                        .session(userSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---- GET /download ----

    @Test
    void download_asAdmin_withDefinition_returnsFile() throws Exception {
        when(projectDefinitionService.readExistingProjectDefinition())
                .thenReturn("# Project Definition\n## Overview\nTest project.");

        mockMvc.perform(get("/api/project-definition/download")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"PROJECT_DEFINITION.md\""));
    }

    @Test
    void download_asAdmin_withNoDefinition_returns404() throws Exception {
        when(projectDefinitionService.readExistingProjectDefinition()).thenReturn(null);

        mockMvc.perform(get("/api/project-definition/download")
                        .session(adminSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void download_withoutSession_returns403() throws Exception {
        mockMvc.perform(get("/api/project-definition/download"))
                .andExpect(status().isForbidden());
    }

    @Test
    void download_asRegularUser_returns403() throws Exception {
        mockMvc.perform(get("/api/project-definition/download")
                        .session(userSession()))
                .andExpect(status().isForbidden());
    }

    // ---- POST /import ----

    @Test
    void import_asAdmin_withContent_returns200() throws Exception {
        doNothing().when(projectDefinitionService).importProjectDefinition(anyString());

        mockMvc.perform(multipart("/api/project-definition/import")
                        .param("content", "# My Project\n## Overview\nA test.")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        verify(projectDefinitionService).importProjectDefinition(org.mockito.ArgumentMatchers.contains("My Project"));
    }

    @Test
    void import_asAdmin_withFile_returns200() throws Exception {
        doNothing().when(projectDefinitionService).importProjectDefinition(anyString());

        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "PROJECT_DEFINITION.md", "text/markdown", "# Uploaded\nContent here.".getBytes());

        mockMvc.perform(multipart("/api/project-definition/import")
                        .file(file)
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        verify(projectDefinitionService).importProjectDefinition(org.mockito.ArgumentMatchers.contains("Uploaded"));
    }

    @Test
    void import_asAdmin_withNoContent_returns400() throws Exception {
        mockMvc.perform(multipart("/api/project-definition/import")
                        .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void import_withoutSession_returns403() throws Exception {
        mockMvc.perform(multipart("/api/project-definition/import")
                        .param("content", "# Test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void import_asRegularUser_returns403() throws Exception {
        mockMvc.perform(multipart("/api/project-definition/import")
                        .param("content", "# Test")
                        .session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    void import_whenServiceThrows_returns500() throws Exception {
        doThrow(new IllegalStateException("The target repository has not been cloned yet."))
                .when(projectDefinitionService).importProjectDefinition(anyString());

        mockMvc.perform(multipart("/api/project-definition/import")
                        .param("content", "# Test Content")
                        .session(adminSession()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }
}
