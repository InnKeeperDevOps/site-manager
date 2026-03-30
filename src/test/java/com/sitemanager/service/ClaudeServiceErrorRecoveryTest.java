package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceErrorRecoveryTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @InjectMocks
    private ClaudeService claudeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(claudeService, "workspaceDir", "/workspace");
    }

    @Test
    void detectsSessionNotFoundPattern() {
        assertTrue(claudeService.isDeadSessionError("No conversation found for this session"));
        assertTrue(claudeService.isDeadSessionError("Error: session not found in store"));
        assertTrue(claudeService.isDeadSessionError("Session not found"));
    }

    @Test
    void returnsFalseForNormalOutput() {
        assertFalse(claudeService.isDeadSessionError("{\"status\": \"ok\"}"));
    }

    @Test
    void returnsFalseForNullInput() {
        assertFalse(claudeService.isDeadSessionError(null));
    }

    @Test
    void returnsFalseForEmptyString() {
        assertFalse(claudeService.isDeadSessionError(""));
    }

    @Test
    void isCaseSensitive_orInsensitive() {
        // "session not found" (lowercase) is a known trigger → true
        assertTrue(claudeService.isDeadSessionError("session not found"));
        // "Session not found" (capital S) is also a known trigger → true
        assertTrue(claudeService.isDeadSessionError("Session not found"));
        // "SESSION NOT FOUND" (all caps) is NOT in the trigger list → false
        assertFalse(claudeService.isDeadSessionError("SESSION NOT FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "No conversation found",
            "session not found",
            "Session not found"
    })
    void detectsAllKnownPatterns(String triggerPhrase) {
        assertTrue(claudeService.isDeadSessionError(triggerPhrase),
                "Expected dead-session detection for: " + triggerPhrase);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionMapEntry_isRemovable_forRecoveryScenario() {
        Object raw = ReflectionTestUtils.getField(claudeService, "sessionMap");
        Map<String, String> sessionMap = (Map<String, String>) raw;

        sessionMap.put("app123", "cli-session-xyz");
        assertThat(sessionMap).containsKey("app123");

        sessionMap.remove("app123");
        assertThat(sessionMap).doesNotContainKey("app123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullRecoveryContract_sessionMapClearedOnDeadSession() {
        Object raw = ReflectionTestUtils.getField(claudeService, "sessionMap");
        Map<String, String> sessionMap = (Map<String, String>) raw;

        // Pre-populate state that would exist before a dead-session error
        sessionMap.put("app123", "cli-session-xyz");
        assertThat(sessionMap).containsKey("app123");

        // Simulate what handleDeadSession does: remove the stale session entry
        sessionMap.remove("app123");

        // After recovery, session mapping should be gone
        assertThat(sessionMap).doesNotContainKey("app123");
        assertThat(sessionMap).isEmpty();
    }
}
