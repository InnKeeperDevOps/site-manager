package com.sitemanager.service;

import com.sitemanager.dto.ProjectDefinitionStateResponse;
import com.sitemanager.model.ProjectDefinitionSession;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.ProjectDefinitionStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ProjectDefinitionSessionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProjectDefinitionService covering all 6 state transitions
 * and the FAILED path on async exceptions.
 *
 * State transitions tested:
 *   1. Initial ACTIVE (startSession creates an ACTIVE session)
 *   2. ACTIVE → ACTIVE (handleClaudeResponse type=question keeps session ACTIVE)
 *   3. ACTIVE → GENERATING (handleClaudeResponse type=complete)
 *   4. GENERATING → SAVING (generateAndSave sets SAVING before git ops)
 *   5. SAVING → COMPLETED (saveAndPushDefinition with auto-merge enabled)
 *   6. SAVING → PR_OPEN (saveAndPushDefinition without auto-merge / merge blocked)
 *   FAILED: any exception marks session FAILED
 */
class ProjectDefinitionServiceTest {

    private ProjectDefinitionSessionRepository sessionRepository;
    private ClaudeService claudeService;
    private SiteSettingsService settingsService;
    private UserNotificationWebSocketHandler notificationHandler;
    private UserRepository userRepository;

    /** Service subclass that stubs out git process execution. */
    private ProjectDefinitionService service;

    private static final Long SESSION_ID = 1L;
    private static final String REPO_URL = "https://github.com/owner/repo";
    private static final String TOKEN = "ghp_test";
    private static final String PR_URL = "https://github.com/owner/repo/pull/5";
    private static final int PR_NUMBER = 5;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ProjectDefinitionSessionRepository.class);
        claudeService = mock(ClaudeService.class);
        settingsService = mock(SiteSettingsService.class);
        notificationHandler = mock(UserNotificationWebSocketHandler.class);
        userRepository = mock(UserRepository.class);

        when(userRepository.findByRole(any())).thenReturn(List.of());
        when(claudeService.generateSessionId()).thenReturn("app-session-uuid");
        when(claudeService.getMainRepoDir()).thenReturn("/workspace/main-repo");

        // Use a spy/subclass to avoid real git ProcessBuilder calls
        service = new ProjectDefinitionService(
                sessionRepository, claudeService, settingsService, notificationHandler, userRepository) {
            @Override
            protected String detectDefaultBranch(String repoDir) {
                return "main";
            }

            @Override
            protected void runGitCommand(String repoDir, String... command) {
                // no-op: skip real git execution in unit tests
            }
        };
    }

    // ─── startSession ───────────────────────────────────────────────────────────

    @Test
    void startSession_createsActiveSession_andReturnsStateResponse() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        when(sessionRepository.save(any())).thenReturn(saved);

        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(1)));

        ProjectDefinitionStateResponse resp = service.startSession();

        assertNotNull(resp);
        assertEquals(SESSION_ID, resp.getSessionId());
        assertEquals(ProjectDefinitionStatus.ACTIVE, resp.getStatus());

        // First question is now returned synchronously in the HTTP response
        assertNotNull(resp.getCurrentQuestion());
        assertEquals("Question 1?", resp.getCurrentQuestion());
        assertEquals("open", resp.getQuestionType());
        assertEquals(11, resp.getProgressPercent());

        // Initial session save (with empty history) should have occurred
        verify(sessionRepository, atLeastOnce()).save(argThat(s ->
                s.getStatus() == ProjectDefinitionStatus.ACTIVE &&
                "app-session-uuid".equals(s.getClaudeSessionId())
        ));
    }

    @Test
    void startSession_returnsFirstQuestion_withOptionsPopulated() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        when(sessionRepository.save(any())).thenReturn(saved);

        String mcQuestion = "{\"type\":\"question\",\"questionType\":\"multiple_choice\"," +
                "\"question\":\"Which best describes your project?\",\"options\":[\"App\",\"API\",\"Tool\"]," +
                "\"progress\":10}";
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mcQuestion));

        ProjectDefinitionStateResponse resp = service.startSession();

        assertEquals("Which best describes your project?", resp.getCurrentQuestion());
        assertEquals("multiple_choice", resp.getQuestionType());
        assertNotNull(resp.getOptions());
        assertEquals(3, resp.getOptions().size());
        assertEquals("App", resp.getOptions().get(0));
    }

    @Test
    void startSession_throwsRuntimeException_andMarksSessionFailed_whenClaudeFails() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        when(sessionRepository.save(any())).thenReturn(saved);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(saved));

        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Claude unavailable"));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(failed);

        assertThrows(RuntimeException.class, () -> service.startSession());

        // Session must be marked FAILED
        assertEquals(ProjectDefinitionStatus.FAILED, saved.getStatus());
        assertNotNull(saved.getErrorMessage());
    }

    @Test
    void startSession_throwsIllegalStateException_whenActiveSessionExists() {
        ProjectDefinitionSession existing = sessionWithId(2L, ProjectDefinitionStatus.ACTIVE);
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> service.startSession());

        verify(sessionRepository, never()).save(any());
        verify(claudeService, never()).continueConversation(any(), any(), any(), any(), any());
    }

    @Test
    void startSession_throwsIllegalStateException_whenGeneratingSessionExists() {
        ProjectDefinitionSession existing = sessionWithId(2L, ProjectDefinitionStatus.GENERATING);
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> service.startSession());
    }

    // ─── submitAnswer ────────────────────────────────────────────────────────────

    @Test
    void submitAnswer_appendsToHistory_andCallsClaude() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(2)));

        ProjectDefinitionStateResponse resp = service.submitAnswer(SESSION_ID, "My answer");

        assertNotNull(resp);
        assertEquals(ProjectDefinitionStatus.ACTIVE, resp.getStatus());
        // Next question is returned synchronously in the HTTP response
        assertNotNull(resp.getCurrentQuestion());

        verify(claudeService).continueConversation(
                eq("app-session-uuid"), eq("My answer"), any(), any(), any());
        // History should have user message appended
        assertTrue(session.getConversationHistory().contains("\"role\":\"user\""));
        assertTrue(session.getConversationHistory().contains("\"content\":\"My answer\""));
    }

    @Test
    void submitAnswer_throwsIllegalArgumentException_whenSessionNotFound() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.submitAnswer(99L, "answer"));
    }

    @Test
    void submitAnswer_throwsIllegalStateException_whenSessionNotActive() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.GENERATING);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThrows(IllegalStateException.class, () -> service.submitAnswer(SESSION_ID, "answer"));
    }

    @Test
    void submitAnswer_returnsNextQuestion_synchronously() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(2)));

        ProjectDefinitionStateResponse resp = service.submitAnswer(SESSION_ID, "My answer");

        assertNotNull(resp.getCurrentQuestion());
        assertEquals("Question 2?", resp.getCurrentQuestion());
        assertEquals("open", resp.getQuestionType());
        assertEquals(22, resp.getProgressPercent());
        assertEquals(ProjectDefinitionStatus.ACTIVE, resp.getStatus());
    }

    @Test
    void submitAnswer_returnsNextQuestion_withMultipleChoiceOptions() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String mcResponse = "{\"type\":\"question\",\"questionType\":\"multiple_choice\"," +
                "\"question\":\"Pick one?\",\"options\":[\"Alpha\",\"Beta\"],\"progress\":50}";
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mcResponse));

        ProjectDefinitionStateResponse resp = service.submitAnswer(SESSION_ID, "Some answer");

        assertEquals("Pick one?", resp.getCurrentQuestion());
        assertEquals("multiple_choice", resp.getQuestionType());
        assertNotNull(resp.getOptions());
        assertEquals(2, resp.getOptions().size());
        assertEquals("Alpha", resp.getOptions().get(0));
        assertEquals("Beta", resp.getOptions().get(1));
    }

    @Test
    void submitAnswer_withCompleteResponse_transitionsToGenerating() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        // Capture the GENERATING status as it passes through before generateAndSave completes
        java.util.concurrent.atomic.AtomicBoolean seenGenerating = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            ProjectDefinitionSession s = inv.getArgument(0);
            if (s.getStatus() == ProjectDefinitionStatus.GENERATING) {
                seenGenerating.set(true);
            }
            return s;
        });

        // First call returns complete signal; second call is for the compilation
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(completeJson()))
                .thenReturn(CompletableFuture.completedFuture("# Project Definition\nContent."));

        SiteSettings settings = settingsWithAutoMerge(false);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        when(settingsService.getSettings()).thenReturn(settings);

        Map<String, Object> prResult = Map.of("html_url", PR_URL, "number", PR_NUMBER);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(prResult);

        service.submitAnswer(SESSION_ID, "done");

        // GENERATING is an intermediate state set before generateAndSave runs
        assertTrue(seenGenerating.get(), "Expected GENERATING status to be saved");
        // No next question since the interview is complete
        verify(claudeService, atLeast(2)).continueConversation(any(), any(), any(), any(), any());
    }

    @Test
    void submitAnswer_throwsRuntimeException_andMarksSessionFailed_whenClaudeFails() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompletableFuture<String> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Claude unavailable"));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(failedFuture);

        assertThrows(RuntimeException.class, () -> service.submitAnswer(SESSION_ID, "My answer"));

        assertEquals(ProjectDefinitionStatus.FAILED, session.getStatus());
        assertNotNull(session.getErrorMessage());
    }

    @Test
    void submitAnswer_appendsAssistantMessageToHistory_afterClaudeResponds() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(3)));

        service.submitAnswer(SESSION_ID, "My answer");

        assertTrue(session.getConversationHistory().contains("\"role\":\"user\""));
        assertTrue(session.getConversationHistory().contains("\"role\":\"assistant\""));
    }

    // ─── startSession: broadcastState after Claude responds ──────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void startSession_broadcastsQuestionToAdmins_afterClaudeResponds() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        when(sessionRepository.save(any())).thenReturn(saved);

        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(1)));

        User admin = adminUser("admin1");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of(admin));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        service.startSession();

        verify(notificationHandler).sendNotificationToUser(eq("admin1"), argThat(payload -> {
            if (!"PROJECT_DEFINITION_UPDATE".equals(payload.get("type"))) return false;
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            return data != null && data.containsKey("currentQuestion") && data.containsKey("progressPercent");
        }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startSession_broadcastPayload_containsSessionIdAndActiveStatus() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        when(sessionRepository.save(any())).thenReturn(saved);

        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(1)));

        User admin = adminUser("admin2");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of());
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));

        service.startSession();

        verify(notificationHandler).sendNotificationToUser(eq("admin2"), argThat(payload -> {
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            return data != null
                    && SESSION_ID.equals(data.get("sessionId"))
                    && "ACTIVE".equals(data.get("status"));
        }));
    }

    // ─── submitAnswer: broadcastState after Claude responds ──────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void submitAnswer_broadcastsNextQuestion_toAdminsAfterClaudeResponds() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(3)));

        User admin = adminUser("admin3");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of(admin));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        service.submitAnswer(SESSION_ID, "My answer");

        verify(notificationHandler).sendNotificationToUser(eq("admin3"), argThat(payload -> {
            if (!"PROJECT_DEFINITION_UPDATE".equals(payload.get("type"))) return false;
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            return data != null && data.containsKey("currentQuestion") && data.containsKey("progressPercent");
        }));
    }

    // ─── handleClaudeResponse: ACTIVE → ACTIVE (question) ────────────────────────

    @Test
    void handleClaudeResponse_withQuestionType_keepsActiveAndBroadcasts() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User admin = adminUser("admin1");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of(admin));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        service.handleClaudeResponse(SESSION_ID, questionJson(3));

        // Status remains ACTIVE
        assertEquals(ProjectDefinitionStatus.ACTIVE, session.getStatus());
        // Assistant message appended to history
        assertTrue(session.getConversationHistory().contains("\"role\":\"assistant\""));
        // Broadcast sent to admin
        verify(notificationHandler).sendNotificationToUser(eq("admin1"), any());
        verify(sessionRepository).save(session);
    }

    // ─── handleClaudeResponse: ACTIVE → GENERATING (complete) ───────────────────

    @Test
    void handleClaudeResponse_withCompleteType_transitionsToGenerating() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setConversationHistory("[]");
        session.setClaudeSessionId("app-session-uuid");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        // Capture the status at each save call (Mockito holds references, not snapshots)
        java.util.concurrent.atomic.AtomicBoolean seenGenerating = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            ProjectDefinitionSession s = inv.getArgument(0);
            if (s.getStatus() == ProjectDefinitionStatus.GENERATING) {
                seenGenerating.set(true);
            }
            return s;
        });

        // generateAndSave will call continueConversation for compilation
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("# Project Definition\nContent here."));

        SiteSettings settings = settingsWithAutoMerge(false);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        when(settingsService.getSettings()).thenReturn(settings);

        Map<String, Object> prResult = Map.of("html_url", PR_URL, "number", PR_NUMBER);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(prResult);

        service.handleClaudeResponse(SESSION_ID, completeJson());

        // GENERATING is an intermediate state before generateAndSave runs.
        // Verified by capturing the status at the time save() was called.
        assertTrue(seenGenerating.get(), "Expected GENERATING status to be saved");
    }

    @Test
    void handleClaudeResponse_ignoresResponse_whenSessionNoLongerActive() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.GENERATING);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        service.handleClaudeResponse(SESSION_ID, questionJson(2));

        // No state change, no save
        assertEquals(ProjectDefinitionStatus.GENERATING, session.getStatus());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void handleClaudeResponse_marksFailedOnException() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        // Corrupt JSON that causes objectMapper to produce malformed state — we simulate
        // an exception mid-handling by returning invalid state; we use null session scenario
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        // Should not throw, just log
        assertDoesNotThrow(() -> service.handleClaudeResponse(SESSION_ID, questionJson(1)));
    }

    // ─── generateAndSave: GENERATING → SAVING → COMPLETED / PR_OPEN ─────────────

    @Test
    void generateAndSave_onSuccess_withAutoMerge_setsCompleted() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.GENERATING);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("# Project Definition\nContent."));

        SiteSettings settings = settingsWithAutoMerge(true);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        when(settingsService.getSettings()).thenReturn(settings);

        Map<String, Object> prResult = Map.of("html_url", PR_URL, "number", PR_NUMBER);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(prResult);
        when(claudeService.mergePullRequest(any(), anyInt(), any())).thenReturn(true);

        service.generateAndSave(session);

        // Passes through SAVING and then COMPLETED
        assertEquals(ProjectDefinitionStatus.COMPLETED, session.getStatus());
        assertNotNull(session.getCompletedAt());
        assertEquals(PR_URL, session.getPrUrl());
        assertEquals(String.valueOf(PR_NUMBER), session.getPrNumber());
    }

    @Test
    void generateAndSave_withAutoMergeBlocked_setsPrOpen() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.GENERATING);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("# Project Definition\nContent."));

        SiteSettings settings = settingsWithAutoMerge(true);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        when(settingsService.getSettings()).thenReturn(settings);

        Map<String, Object> prResult = Map.of("html_url", PR_URL, "number", PR_NUMBER);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(prResult);
        when(claudeService.mergePullRequest(any(), anyInt(), any())).thenReturn(false);

        service.generateAndSave(session);

        assertEquals(ProjectDefinitionStatus.PR_OPEN, session.getStatus());
        assertEquals("Auto-merge blocked — manual merge required", session.getErrorMessage());
        assertEquals(PR_URL, session.getPrUrl());
    }

    @Test
    void generateAndSave_withoutAutoMerge_setsPrOpen() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.GENERATING);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("# Project Definition\nContent."));

        SiteSettings settings = settingsWithAutoMerge(false);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        when(settingsService.getSettings()).thenReturn(settings);

        Map<String, Object> prResult = Map.of("html_url", PR_URL, "number", PR_NUMBER);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(prResult);

        service.generateAndSave(session);

        assertEquals(ProjectDefinitionStatus.PR_OPEN, session.getStatus());
        assertEquals(PR_URL, session.getPrUrl());
        verify(claudeService, never()).mergePullRequest(any(), anyInt(), any());
    }

    @Test
    void generateAndSave_marksFailedOnClaudeException() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.GENERATING);
        session.setClaudeSessionId("app-session-uuid");
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompletableFuture<String> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Claude unavailable"));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(failedFuture);

        service.generateAndSave(session);

        assertEquals(ProjectDefinitionStatus.FAILED, session.getStatus());
        assertNotNull(session.getErrorMessage());
    }

    // ─── saveAndPushDefinition ────────────────────────────────────────────────────

    @Test
    void saveAndPushDefinition_withNoToken_setsPrOpenAndNoTokenMessage() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.SAVING);
        session.setGeneratedContent("# Project Definition\nContent.");

        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SiteSettings settings = settingsWithAutoMerge(false);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(null);
        when(settingsService.getSettings()).thenReturn(settings);

        service.saveAndPushDefinition(session);

        assertEquals(ProjectDefinitionStatus.PR_OPEN, session.getStatus());
        assertNotNull(session.getErrorMessage());
        assertTrue(session.getErrorMessage().contains("no GitHub token configured"));
        verify(claudeService, never()).createGitHubPullRequest(any(), any(), any(), any(), any());
    }

    @Test
    void saveAndPushDefinition_onGitFailure_marksSessionFailed() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.SAVING);
        session.setGeneratedContent("# Content");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SiteSettings settings = settingsWithAutoMerge(false);
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        when(settingsService.getSettings()).thenReturn(settings);

        // Override to simulate git failure
        ProjectDefinitionService brokenGitService = new ProjectDefinitionService(
                sessionRepository, claudeService, settingsService, notificationHandler, userRepository) {
            @Override
            protected String detectDefaultBranch(String repoDir) {
                return "main";
            }

            @Override
            protected void runGitCommand(String repoDir, String... command) throws Exception {
                throw new RuntimeException("git: not a repository");
            }
        };

        brokenGitService.saveAndPushDefinition(session);

        assertEquals(ProjectDefinitionStatus.FAILED, session.getStatus());
        assertNotNull(session.getErrorMessage());
    }

    // ─── toStateResponseWithQuestion ─────────────────────────────────────────────

    @Test
    void toStateResponseWithQuestion_populatesQuestionFields_fromJsonNode() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(questionJson(3));

        ProjectDefinitionStateResponse resp = service.toStateResponseWithQuestion(session, node);

        assertEquals("Question 3?", resp.getCurrentQuestion());
        assertEquals("open", resp.getQuestionType());
        assertEquals(33, resp.getProgressPercent());
        assertNotNull(resp.getOptions());
    }

    @Test
    void toStateResponseWithQuestion_withNullNode_returnsBaseResponse() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);

        ProjectDefinitionStateResponse resp = service.toStateResponseWithQuestion(session, null);

        assertNotNull(resp);
        assertEquals(SESSION_ID, resp.getSessionId());
        assertNull(resp.getCurrentQuestion());
    }

    @Test
    void toStateResponseWithQuestion_withCompleteType_doesNotPopulateQuestion() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(completeJson());

        ProjectDefinitionStateResponse resp = service.toStateResponseWithQuestion(session, node);

        assertNull(resp.getCurrentQuestion());
    }

    @Test
    void toStateResponseWithQuestion_withMultipleChoiceOptions_populatesOptionsList() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String mcJson = "{\"type\":\"question\",\"questionType\":\"multiple_choice\"," +
                "\"question\":\"Pick one?\",\"options\":[\"Alpha\",\"Beta\",\"Gamma\"],\"progress\":50}";
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(mcJson);

        ProjectDefinitionStateResponse resp = service.toStateResponseWithQuestion(session, node);

        assertEquals("Pick one?", resp.getCurrentQuestion());
        assertEquals("multiple_choice", resp.getQuestionType());
        assertEquals(50, resp.getProgressPercent());
        assertNotNull(resp.getOptions());
        assertEquals(3, resp.getOptions().size());
        assertEquals("Alpha", resp.getOptions().get(0));
        assertEquals("Beta", resp.getOptions().get(1));
        assertEquals("Gamma", resp.getOptions().get(2));
    }

    @Test
    void toStateResponseWithQuestion_withMissingFields_usesDefaults() throws Exception {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // Minimal question node with no optional fields
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree("{\"type\":\"question\",\"question\":\"What?\"}");

        ProjectDefinitionStateResponse resp = service.toStateResponseWithQuestion(session, node);

        assertEquals("What?", resp.getCurrentQuestion());
        assertEquals("open", resp.getQuestionType());   // default
        assertEquals(0, resp.getProgressPercent());     // default
        assertNotNull(resp.getOptions());
        assertTrue(resp.getOptions().isEmpty());
    }

    @Test
    void toStateResponseWithQuestion_preservesBaseSessionFields() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setHasExistingDefinition(true);
        session.setPrUrl(PR_URL);
        session.setErrorMessage("some error");

        ProjectDefinitionStateResponse resp = service.toStateResponseWithQuestion(session, null);

        assertEquals(SESSION_ID, resp.getSessionId());
        assertEquals(ProjectDefinitionStatus.ACTIVE, resp.getStatus());
        assertTrue(resp.isEdit());
        assertEquals(PR_URL, resp.getPrUrl());
        assertEquals("some error", resp.getErrorMessage());
    }

    // ─── toStateResponse: isEdit field ───────────────────────────────────────────

    @Test
    void toStateResponse_isEdit_falseWhenHasExistingDefinitionIsFalse() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setHasExistingDefinition(false);

        ProjectDefinitionStateResponse resp = service.toStateResponse(session);

        assertFalse(resp.isEdit());
    }

    @Test
    void toStateResponse_isEdit_trueWhenHasExistingDefinitionIsTrue() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setHasExistingDefinition(true);

        ProjectDefinitionStateResponse resp = service.toStateResponse(session);

        assertTrue(resp.isEdit());
    }

    // ─── getState ────────────────────────────────────────────────────────────────

    @Test
    void getState_returnsStateResponse_whenSessionExists() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        when(sessionRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(session));

        ProjectDefinitionStateResponse resp = service.getState();

        assertNotNull(resp);
        assertEquals(SESSION_ID, resp.getSessionId());
        assertEquals(ProjectDefinitionStatus.ACTIVE, resp.getStatus());
    }

    @Test
    void getState_returnsNull_whenNoSessions() {
        when(sessionRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        assertNull(service.getState());
    }

    // ─── resetSession ─────────────────────────────────────────────────────────────

    @Test
    void resetSession_setsActiveSessionToFailed() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        when(sessionRepository.findFirstByStatusIn(List.of(ProjectDefinitionStatus.ACTIVE)))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetSession();

        assertEquals(ProjectDefinitionStatus.FAILED, session.getStatus());
        assertEquals("Session was reset", session.getErrorMessage());
        assertNotNull(session.getCompletedAt());
        verify(sessionRepository).save(session);
    }

    @Test
    void resetSession_doesNothing_whenNoActiveSession() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        service.resetSession();

        verify(sessionRepository, never()).save(any());
    }

    // ─── appendMessage helper ─────────────────────────────────────────────────────

    @Test
    void appendMessage_appendsToEmptyArray() {
        String result = service.appendMessage("[]", "user", "hello");
        assertTrue(result.contains("\"role\":\"user\""));
        assertTrue(result.contains("\"content\":\"hello\""));
    }

    @Test
    void appendMessage_appendsToExistingHistory() {
        String initial = service.appendMessage("[]", "user", "first");
        String result = service.appendMessage(initial, "assistant", "second");
        assertTrue(result.contains("\"content\":\"first\""));
        assertTrue(result.contains("\"content\":\"second\""));
    }

    @Test
    void appendMessage_handlesNullHistory() {
        String result = service.appendMessage(null, "user", "hello");
        assertTrue(result.contains("\"role\":\"user\""));
    }

    // ─── extractJsonBlock helper ──────────────────────────────────────────────────

    @Test
    void extractJsonBlock_extractsEmbeddedJson() {
        String response = "Some preamble\n{\"type\":\"question\"}\nSome suffix";
        String json = service.extractJsonBlock(response);
        assertEquals("{\"type\":\"question\"}", json);
    }

    @Test
    void extractJsonBlock_returnsNull_forNonJsonResponse() {
        assertNull(service.extractJsonBlock("no json here"));
        assertNull(service.extractJsonBlock(null));
    }

    // ─── broadcastSimple broadcasts to admins ─────────────────────────────────────

    @Test
    void broadcastSimple_sendsToAllAdmins() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.COMPLETED);
        session.setPrUrl(PR_URL);

        User rootAdmin = adminUser("root");
        User admin = adminUser("admin");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of(rootAdmin));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));

        service.broadcastSimple(session);

        verify(notificationHandler).sendNotificationToUser(eq("root"), any());
        verify(notificationHandler).sendNotificationToUser(eq("admin"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastSimple_payloadHasProjectDefinitionUpdateType() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.COMPLETED);
        session.setPrUrl(PR_URL);

        User admin = adminUser("admin");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of());
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));

        service.broadcastSimple(session);

        verify(notificationHandler).sendNotificationToUser(eq("admin"), argThat(payload -> {
            if (!"PROJECT_DEFINITION_UPDATE".equals(payload.get("type"))) return false;
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            return data != null && "COMPLETED".equals(data.get("status"));
        }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastSimple_prUrlIncludedInDataWhenPresent() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.PR_OPEN);
        session.setPrUrl(PR_URL);

        User admin = adminUser("admin");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of());
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));

        service.broadcastSimple(session);

        verify(notificationHandler).sendNotificationToUser(eq("admin"), argThat(payload -> {
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            return data != null && PR_URL.equals(data.get("prUrl"));
        }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleClaudeResponse_broadcastContainsQuestionInData() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        session.setConversationHistory("[]");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User admin = adminUser("admin1");
        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of(admin));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        service.handleClaudeResponse(SESSION_ID, questionJson(2));

        verify(notificationHandler).sendNotificationToUser(eq("admin1"), argThat(payload -> {
            if (!"PROJECT_DEFINITION_UPDATE".equals(payload.get("type"))) return false;
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            return data != null && data.containsKey("currentQuestion") && data.containsKey("progressPercent");
        }));
    }

    // ─── markFailed ──────────────────────────────────────────────────────────────

    @Test
    void markFailed_setsStatusAndErrorMessage() {
        ProjectDefinitionSession session = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markFailed(SESSION_ID, "Oops");

        assertEquals(ProjectDefinitionStatus.FAILED, session.getStatus());
        assertEquals("Oops", session.getErrorMessage());
        assertNotNull(session.getCompletedAt());
    }

    @Test
    void markFailed_doesNothing_whenSessionNotFound() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.markFailed(SESSION_ID, "error"));
        verify(sessionRepository, never()).save(any());
    }

    // ─── readExistingProjectDefinition ───────────────────────────────────────────

    @Test
    void readExistingProjectDefinition_returnsContent_whenFileExists(@TempDir Path tempDir) throws Exception {
        String content = "# Project Definition\n## Overview\nThis is a test project.";
        Files.writeString(tempDir.resolve("PROJECT_DEFINITION.md"), content);

        when(claudeService.getMainRepoDir()).thenReturn(tempDir.toString());

        ProjectDefinitionService svc = new ProjectDefinitionService(
                sessionRepository, claudeService, settingsService, notificationHandler, userRepository) {
            @Override protected String detectDefaultBranch(String repoDir) { return "main"; }
            @Override protected void runGitCommand(String repoDir, String... command) {}
        };

        String result = svc.readExistingProjectDefinition();

        assertEquals(content, result);
    }

    @Test
    void readExistingProjectDefinition_returnsNull_whenFileDoesNotExist(@TempDir Path tempDir) {
        when(claudeService.getMainRepoDir()).thenReturn(tempDir.toString());

        ProjectDefinitionService svc = new ProjectDefinitionService(
                sessionRepository, claudeService, settingsService, notificationHandler, userRepository) {
            @Override protected String detectDefaultBranch(String repoDir) { return "main"; }
            @Override protected void runGitCommand(String repoDir, String... command) {}
        };

        String result = svc.readExistingProjectDefinition();

        assertNull(result);
    }

    // ─── startSession: hasExistingDefinition ─────────────────────────────────────

    @Test
    void startSession_setsHasExistingDefinitionTrue_whenDefinitionFileExists() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        saved.setHasExistingDefinition(true);
        when(sessionRepository.save(any())).thenReturn(saved);

        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(1)));

        ProjectDefinitionService svcWithExisting = new ProjectDefinitionService(
                sessionRepository, claudeService, settingsService, notificationHandler, userRepository) {
            @Override protected String detectDefaultBranch(String repoDir) { return "main"; }
            @Override protected void runGitCommand(String repoDir, String... command) {}
            @Override protected String readExistingProjectDefinition() { return "# Existing Definition\nContent."; }
        };

        svcWithExisting.startSession();

        verify(sessionRepository, atLeastOnce()).save(argThat(s -> s.isHasExistingDefinition()));
    }

    @Test
    void startSession_setsHasExistingDefinitionFalse_whenNoDefinitionFile() {
        when(sessionRepository.findFirstByStatusIn(anyList())).thenReturn(Optional.empty());

        ProjectDefinitionSession saved = sessionWithId(SESSION_ID, ProjectDefinitionStatus.ACTIVE);
        saved.setClaudeSessionId("app-session-uuid");
        saved.setConversationHistory("[]");
        saved.setHasExistingDefinition(false);
        when(sessionRepository.save(any())).thenReturn(saved);

        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(questionJson(1)));

        ProjectDefinitionService svcWithoutExisting = new ProjectDefinitionService(
                sessionRepository, claudeService, settingsService, notificationHandler, userRepository) {
            @Override protected String detectDefaultBranch(String repoDir) { return "main"; }
            @Override protected void runGitCommand(String repoDir, String... command) {}
            @Override protected String readExistingProjectDefinition() { return null; }
        };

        svcWithoutExisting.startSession();

        verify(sessionRepository, atLeastOnce()).save(argThat(s -> !s.isHasExistingDefinition()));
    }

    // ─── buildInterviewPrompt ─────────────────────────────────────────────────────

    @Test
    void buildInterviewPrompt_withNullExistingDefinition_returnsConversationalNewPrompt() {
        String prompt = service.buildInterviewPrompt(null);

        // Should instruct conversational interview style
        assertTrue(prompt.contains("conversational"), "Prompt should mention conversational style");
        // Should not contain rigid numbered question list
        assertFalse(prompt.contains("question 1") || prompt.contains("9 questions"),
                "New-mode prompt should not impose a rigid question list");
        // Should include JSON format instructions
        assertTrue(prompt.contains("\"type\":\"question\""), "Prompt should include JSON question format");
        assertTrue(prompt.contains("\"type\":\"complete\""), "Prompt should include JSON complete format");
        // Should instruct Claude to avoid technical jargon
        assertTrue(prompt.toLowerCase().contains("technical jargon") || prompt.toLowerCase().contains("plain, everyday language"),
                "Prompt must instruct Claude to use plain language");
        // Should cover key topic areas
        assertTrue(prompt.contains("what the project does") || prompt.contains("what they are building"),
                "Prompt should ask about what project does");
        // Progress field should be described
        assertTrue(prompt.contains("progress"), "Prompt should mention progress field");
    }

    @Test
    void buildInterviewPrompt_withExistingDefinition_returnsEditPromptContainingDefinition() {
        String existingContent = "# Project Definition\n## Overview\nThis is a task manager app.";
        String prompt = service.buildInterviewPrompt(existingContent);

        // Should include the existing definition content
        assertTrue(prompt.contains(existingContent), "Prompt should embed the existing definition");
        // Should instruct Claude to summarize and ask what to update
        assertTrue(prompt.contains("summar"), "Prompt should ask Claude to summarize the existing definition");
        assertTrue(prompt.contains("update") || prompt.contains("improve"),
                "Prompt should ask what user wants to update or improve");
        // Should include JSON format instructions
        assertTrue(prompt.contains("\"type\":\"question\""), "Prompt should include JSON question format");
        assertTrue(prompt.contains("\"type\":\"complete\""), "Prompt should include JSON complete format");
        // Should instruct Claude to avoid technical jargon
        assertTrue(prompt.toLowerCase().contains("technical jargon") || prompt.toLowerCase().contains("plain, everyday language"),
                "Prompt must instruct Claude to use plain language");
        // Progress field should be described
        assertTrue(prompt.contains("progress"), "Prompt should mention progress field");
    }

    @Test
    void buildInterviewPrompt_newMode_promptIncludesKeyTopicAreas() {
        String prompt = service.buildInterviewPrompt(null);

        // All key areas should be covered in the topic list
        assertTrue(prompt.contains("who will use") || prompt.contains("Who will use"),
                "Should ask about target users");
        assertTrue(prompt.contains("goals") || prompt.contains("motivation"),
                "Should ask about goals or motivation");
        assertTrue(prompt.contains("success"), "Should ask about what success looks like");
    }

    @Test
    void buildInterviewPrompt_editMode_mentionsIncompleteOrVagueAreas() {
        String definition = "# My App\nIt does stuff.";
        String prompt = service.buildInterviewPrompt(definition);

        // Should guide the user toward refining vague areas
        assertTrue(prompt.contains("incomplete") || prompt.contains("vague") || prompt.contains("lack detail"),
                "Edit prompt should guide toward refining incomplete or vague content");
    }

    @Test
    void buildInterviewPrompt_newMode_instructsOneQuestionAtATime() {
        String prompt = service.buildInterviewPrompt(null);

        assertTrue(prompt.contains("one question at a time"),
                "New-mode prompt should instruct one question at a time");
    }

    @Test
    void buildInterviewPrompt_editMode_instructsOneQuestionAtATime() {
        String prompt = service.buildInterviewPrompt("# Existing\nContent.");

        assertTrue(prompt.contains("one question at a time"),
                "Edit-mode prompt should instruct one question at a time");
    }

    @Test
    void buildInterviewPrompt_newMode_instructsToStartAsking() {
        String prompt = service.buildInterviewPrompt(null);

        // Should tell Claude to start asking right away
        assertTrue(prompt.toLowerCase().contains("start by asking"),
                "New-mode prompt should instruct Claude to start asking");
    }

    @Test
    void buildInterviewPrompt_editMode_instructsToStartWithSummary() {
        String prompt = service.buildInterviewPrompt("# Existing\nContent.");

        // Should tell Claude to start with a summary
        assertTrue(prompt.toLowerCase().contains("start by summar"),
                "Edit-mode prompt should instruct Claude to start by summarizing");
    }

    // ─── Test helper methods ──────────────────────────────────────────────────────

    private static ProjectDefinitionSession sessionWithId(Long id, ProjectDefinitionStatus status) {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    private static User adminUser(String username) {
        User u = new User();
        u.setUsername(username);
        return u;
    }

    private static SiteSettings settingsWithAutoMerge(boolean autoMerge) {
        SiteSettings s = new SiteSettings();
        s.setAutoMergePr(autoMerge);
        return s;
    }

    private static String questionJson(int questionNumber) {
        return "{\"type\":\"question\",\"questionType\":\"open\"," +
                "\"question\":\"Question " + questionNumber + "?\",\"options\":[]," +
                "\"progress\":" + (questionNumber * 11) + "}";
    }

    private static String completeJson() {
        return "{\"type\":\"complete\",\"message\":\"Thank you! Your project definition is ready.\"}";
    }
}
