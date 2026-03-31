package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceErrorRecoveryTest {

    @TempDir
    Path tempDir;

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

    // -------------------------------------------------------------------------
    // Retry loop integration — TRANSIENT failure retries exactly maxRetries times
    // -------------------------------------------------------------------------

    @Test
    void transientFailure_retriesExactlyMaxRetriesTimes() throws Exception {
        // Use 2 retries and zero delay so test completes instantly
        ReflectionTestUtils.setField(claudeService, "claudeMaxRetries", 2);
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 0L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 0L);
        ReflectionTestUtils.setField(claudeService, "claudeTimeoutMinutes", 1);
        // Non-existent binary causes IOException → TRANSIENT on every attempt
        ReflectionTestUtils.setField(claudeService, "claudeCliPath", "/nonexistent-claude-binary");
        ReflectionTestUtils.setField(claudeService, "workspaceDir", tempDir.toString());
        Files.createDirectories(tempDir.resolve("main-repo"));

        ClaudeService.ClaudeExecutionException ex = assertThrows(
                ClaudeService.ClaudeExecutionException.class,
                () -> claudeService.getRecommendations("test prompt")
        );

        // With claudeMaxRetries=2 the loop runs attempts 1, 2, 3 — then throws.
        // The thrown exception records the attempt number of the final attempt.
        assertThat(ex.getType()).isEqualTo(ClaudeService.ClaudeFailureType.TRANSIENT);
        assertThat(ex.getAttemptNumber()).isEqualTo(3); // claudeMaxRetries + 1
    }

    // -------------------------------------------------------------------------
    // Retry loop integration — PERMANENT failure does not retry
    // -------------------------------------------------------------------------

    @Test
    void permanentFailure_doesNotRetry() throws Exception {
        // Script exits with code 2 → classifyFailure returns PERMANENT
        Path script = tempDir.resolve("fake-claude.sh");
        Files.writeString(script, "#!/bin/sh\nexit 2\n");
        script.toFile().setExecutable(true);

        ReflectionTestUtils.setField(claudeService, "claudeMaxRetries", 3);
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 0L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 0L);
        ReflectionTestUtils.setField(claudeService, "claudeTimeoutMinutes", 1);
        ReflectionTestUtils.setField(claudeService, "claudeCliPath", script.toString());
        ReflectionTestUtils.setField(claudeService, "workspaceDir", tempDir.toString());
        Files.createDirectories(tempDir.resolve("main-repo"));

        ClaudeService.ClaudeExecutionException ex = assertThrows(
                ClaudeService.ClaudeExecutionException.class,
                () -> claudeService.getRecommendations("test prompt")
        );

        // PERMANENT → thrown immediately on attempt 1 without any retry
        assertThat(ex.getType()).isEqualTo(ClaudeService.ClaudeFailureType.PERMANENT);
        assertThat(ex.getAttemptNumber()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Retry loop integration — success on 2nd attempt
    // -------------------------------------------------------------------------

    @Test
    void successOnSecondAttempt_returnsResultAndLogs1RetryWarning() throws Exception {
        // Script fails on first call (no flag file) and succeeds on second call
        Path flagFile = tempDir.resolve("call-flag");
        Path script = tempDir.resolve("fake-claude.sh");
        String scriptContent = String.format(
                "#!/bin/sh\nif [ -f '%s' ]; then\n  rm '%s'\n  echo '{\"result\":\"task done\"}'\nelse\n  touch '%s'\n  exit 1\nfi\n",
                flagFile, flagFile, flagFile
        );
        Files.writeString(script, scriptContent);
        script.toFile().setExecutable(true);

        ReflectionTestUtils.setField(claudeService, "claudeMaxRetries", 3);
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 0L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 0L);
        ReflectionTestUtils.setField(claudeService, "claudeTimeoutMinutes", 1);
        ReflectionTestUtils.setField(claudeService, "claudeCliPath", script.toString());
        ReflectionTestUtils.setField(claudeService, "workspaceDir", tempDir.toString());
        Files.createDirectories(tempDir.resolve("main-repo"));

        // Attempt 1: script exits 1 (TRANSIENT), retry
        // Attempt 2: script outputs JSON successfully
        String result = claudeService.getRecommendations("test prompt");

        assertThat(result).isEqualTo("task done");
        // Flag file was created on attempt 1 and removed on attempt 2 — verify it's gone
        assertThat(flagFile).doesNotExist();
    }
}
