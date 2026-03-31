package com.sitemanager.service;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PlanExecutionService covering: handleExecutionResult,
 * createPrAsync, retryPrCreation, getExecutionQueueStatus, savePlanTasks,
 * executeApprovedSuggestion, tryStartNextQueuedSuggestion,
 * markRemainingTasksCompleted, and buildTasksJsonForExecution.
 */
class PlanExecutionServiceTest {

    private SuggestionRepository suggestionRepository;
    private PlanTaskRepository planTaskRepository;
    private SuggestionMessageRepository messageRepository;
    private ClaudeService claudeService;
    private SuggestionMessagingHelper messagingHelper;
    private SiteSettingsService settingsService;
    private SuggestionWebSocketHandler webSocketHandler;
    private SlackNotificationService slackNotificationService;
    private PlanExecutionService service;

    private static final String REPO_URL = "https://github.com/owner/repo";
    private static final String GITHUB_TOKEN = "ghp_test";
    private static final String PR_URL = "https://github.com/owner/repo/pull/42";
    private static final int PR_NUMBER = 42;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        planTaskRepository = mock(PlanTaskRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        claudeService = mock(ClaudeService.class);
        messagingHelper = mock(SuggestionMessagingHelper.class);
        settingsService = mock(SiteSettingsService.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        slackNotificationService = mock(SlackNotificationService.class);

        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(planTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SuggestionMessage stubMsg = new SuggestionMessage(1L, SenderType.SYSTEM, "System", "msg");
        stubMsg.setId(1L);
        when(messageRepository.save(any())).thenReturn(stubMsg);
        when(messagingHelper.addMessage(any(), any(), any(), any())).thenReturn(stubMsg);
        when(messagingHelper.escapeJson(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s == null ? "" : s;
        });

        // Default settings — individual tests can override as needed
        SiteSettings defaultSettings = new SiteSettings();
        defaultSettings.setTargetRepoUrl(null); // no repo by default → createPr bails early
        defaultSettings.setGithubToken(null);
        defaultSettings.setMaxConcurrentSuggestions(1);
        when(settingsService.getSettings()).thenReturn(defaultSettings);

        // Default for task/suggestion lists used in tryStartNextQueuedSuggestion
        when(suggestionRepository.findByStatus(any())).thenReturn(new java.util.ArrayList<>());
        when(suggestionRepository.countByStatusIn(any())).thenReturn(0L);
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(any())).thenReturn(new java.util.ArrayList<>());

        service = new PlanExecutionService(
                suggestionRepository,
                planTaskRepository,
                claudeService,
                messagingHelper,
                settingsService,
                webSocketHandler,
                slackNotificationService
        );
    }

    // -------------------------------------------------------------------------
    // handleExecutionResult
    // -------------------------------------------------------------------------

    @Test
    void handleExecutionResult_completedResult_setsStatusDevComplete() {
        Suggestion suggestion = suggestion(10L);
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(10L)).thenReturn(List.of());
        when(claudeService.generateSessionId()).thenReturn("s");

        service.handleExecutionResult(10L, "COMPLETED — all done");

        assertThat(suggestion.getStatus()).isEqualTo(SuggestionStatus.DEV_COMPLETE);
    }

    @Test
    void handleExecutionResult_completedResult_sendsSlackNotification() {
        Suggestion suggestion = suggestion(11L);
        when(suggestionRepository.findById(11L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(11L)).thenReturn(List.of());
        when(claudeService.generateSessionId()).thenReturn("s");

        service.handleExecutionResult(11L, "COMPLETED");

        verify(slackNotificationService).sendNotification(eq(suggestion), eq("DEV_COMPLETE"));
    }

    @Test
    void handleExecutionResult_failedResult_setsRetryPhase() {
        Suggestion suggestion = suggestion(12L);
        when(suggestionRepository.findById(12L)).thenReturn(Optional.of(suggestion));

        service.handleExecutionResult(12L, "FAILED — error");

        assertThat(suggestion.getCurrentPhase()).contains("can retry");
    }

    @Test
    void handleExecutionResult_unknownResult_setsTestingStatus() {
        Suggestion suggestion = suggestion(13L);
        when(suggestionRepository.findById(13L)).thenReturn(Optional.of(suggestion));

        service.handleExecutionResult(13L, "Running tests now...");

        assertThat(suggestion.getStatus()).isEqualTo(SuggestionStatus.TESTING);
    }

    @Test
    void handleExecutionResult_suggestionNotFound_doesNothing() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());
        service.handleExecutionResult(99L, "COMPLETED");
        verifyNoInteractions(planTaskRepository);
    }

    // -------------------------------------------------------------------------
    // retryPrCreation
    // -------------------------------------------------------------------------

    @Test
    void retryPrCreation_suggestionNotFound_returnsError() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());
        Map<String, Object> result = service.retryPrCreation(99L);
        assertThat(result.get("success")).isEqualTo(false);
    }

    @Test
    void retryPrCreation_wrongPhase_returnsError() {
        Suggestion s = suggestion(20L);
        s.setCurrentPhase("Done — ready for review");
        when(suggestionRepository.findById(20L)).thenReturn(Optional.of(s));

        Map<String, Object> result = service.retryPrCreation(20L);

        assertThat(result.get("success")).isEqualTo(false);
    }

    @Test
    void retryPrCreation_noGitHubToken_returnsError() {
        Suggestion s = suggestion(21L);
        s.setCurrentPhase("Done — review request failed");
        when(suggestionRepository.findById(21L)).thenReturn(Optional.of(s));
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken("");
        when(settingsService.getSettings()).thenReturn(settings);

        Map<String, Object> result = service.retryPrCreation(21L);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("GitHub token");
    }

    @Test
    void retryPrCreation_success_returnsPrUrl() throws Exception {
        Suggestion s = suggestion(22L);
        s.setCurrentPhase("Done — review request failed");
        when(suggestionRepository.findById(22L)).thenReturn(Optional.of(s));
        stubFullSettings(false);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("html_url", PR_URL, "number", PR_NUMBER));

        Map<String, Object> result = service.retryPrCreation(22L);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("prUrl")).isEqualTo(PR_URL);
    }

    @Test
    void retryPrCreation_success_setsStatusFinalReview() throws Exception {
        Suggestion s = suggestion(23L);
        s.setCurrentPhase("Done — review request failed");
        when(suggestionRepository.findById(23L)).thenReturn(Optional.of(s));
        stubFullSettings(false);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("html_url", PR_URL, "number", PR_NUMBER));

        service.retryPrCreation(23L);

        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.FINAL_REVIEW);
    }

    @Test
    void retryPrCreation_autoMergeEnabled_mergeSucceeds_statusMerged() throws Exception {
        Suggestion s = suggestion(24L);
        s.setCurrentPhase("Done — review request failed");
        when(suggestionRepository.findById(24L)).thenReturn(Optional.of(s));
        stubFullSettings(true);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("html_url", PR_URL, "number", PR_NUMBER));
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(GITHUB_TOKEN)))
                .thenReturn(true);

        service.retryPrCreation(24L);

        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.MERGED);
        assertThat(s.getCurrentPhase()).isEqualTo("PR automatically merged into main");
    }

    @Test
    void retryPrCreation_autoMergeEnabled_mergeFails_statusFinalReview() throws Exception {
        Suggestion s = suggestion(25L);
        s.setCurrentPhase("Done — review request failed");
        when(suggestionRepository.findById(25L)).thenReturn(Optional.of(s));
        stubFullSettings(true);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("html_url", PR_URL, "number", PR_NUMBER));
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(GITHUB_TOKEN)))
                .thenReturn(false);

        service.retryPrCreation(25L);

        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.FINAL_REVIEW);
    }

    @Test
    void retryPrCreation_prCreationThrows_returnsError() throws Exception {
        Suggestion s = suggestion(26L);
        s.setCurrentPhase("Done — review request failed");
        when(suggestionRepository.findById(26L)).thenReturn(Optional.of(s));
        stubFullSettings(false);
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("API error"));

        Map<String, Object> result = service.retryPrCreation(26L);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("API error");
        assertThat(s.getCurrentPhase()).isEqualTo("Done — review request failed");
    }

    // -------------------------------------------------------------------------
    // getExecutionQueueStatus
    // -------------------------------------------------------------------------

    @Test
    void getExecutionQueueStatus_returnsCorrectCounts() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(2);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(1L);
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED)).thenReturn(new java.util.ArrayList<>());

        Map<String, Object> result = service.getExecutionQueueStatus();

        assertThat(result.get("maxConcurrent")).isEqualTo(2);
        assertThat(result.get("activeCount")).isEqualTo(1L);
        assertThat(result.get("queuedCount")).isEqualTo(0);
    }

    @Test
    void getExecutionQueueStatus_withQueuedSuggestions_includesPositions() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(1);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(1L);

        Suggestion queued1 = suggestion(30L);
        queued1.setTitle("First");
        queued1.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        Suggestion queued2 = suggestion(31L);
        queued2.setTitle("Second");
        queued2.setCreatedAt(Instant.parse("2024-01-02T00:00:00Z"));
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED))
                .thenReturn(new java.util.ArrayList<>(List.of(queued2, queued1))); // unsorted

        Map<String, Object> result = service.getExecutionQueueStatus();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queued = (List<Map<String, Object>>) result.get("queued");
        assertThat(queued).hasSize(2);
        assertThat(queued.get(0).get("id")).isEqualTo(30L); // oldest first
        assertThat(queued.get(0).get("position")).isEqualTo(1);
    }

    @Test
    void getExecutionQueueStatus_defaultsToMaxConcurrentOneWhenNull() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(null);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(0L);
        when(suggestionRepository.findByStatus(any())).thenReturn(new java.util.ArrayList<>());

        Map<String, Object> result = service.getExecutionQueueStatus();

        assertThat(result.get("maxConcurrent")).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // savePlanTasks
    // -------------------------------------------------------------------------

    @Test
    void savePlanTasks_parsesAndSavesTasks() {
        String response = "{\"status\":\"PLAN_READY\",\"tasks\":[" +
                "{\"title\":\"Task A\",\"description\":\"Do A\",\"estimatedMinutes\":10}," +
                "{\"title\":\"Task B\",\"description\":\"Do B\",\"estimatedMinutes\":20}" +
                "]}";
        Suggestion s = suggestion(40L);
        s.setStatus(SuggestionStatus.DISCUSSING);
        when(suggestionRepository.findById(40L)).thenReturn(Optional.of(s));

        service.savePlanTasks(40L, response);

        verify(planTaskRepository, times(2)).save(any(PlanTask.class));
        verify(messagingHelper).broadcastTasks(40L);
    }

    @Test
    void savePlanTasks_planLocked_doesNotSave() {
        Suggestion s = suggestion(41L);
        s.setStatus(SuggestionStatus.IN_PROGRESS);
        when(suggestionRepository.findById(41L)).thenReturn(Optional.of(s));

        service.savePlanTasks(41L, "{\"tasks\":[{\"title\":\"X\"}]}");

        verify(planTaskRepository, never()).save(any());
        verify(planTaskRepository, never()).deleteBySuggestionId(any());
    }

    @Test
    void savePlanTasks_deletesExistingTasksFirst() {
        String response = "{\"tasks\":[{\"title\":\"Only\"}]}";
        Suggestion s = suggestion(42L);
        s.setStatus(SuggestionStatus.DISCUSSING);
        when(suggestionRepository.findById(42L)).thenReturn(Optional.of(s));

        service.savePlanTasks(42L, response);

        verify(planTaskRepository).deleteBySuggestionId(42L);
    }

    @Test
    void savePlanTasks_invalidJson_doesNotThrow() {
        Suggestion s = suggestion(43L);
        s.setStatus(SuggestionStatus.DISCUSSING);
        when(suggestionRepository.findById(43L)).thenReturn(Optional.of(s));

        // no exception expected
        service.savePlanTasks(43L, "not json at all");
    }

    @Test
    void savePlanTasks_noTasksArray_doesNotSave() {
        Suggestion s = suggestion(44L);
        s.setStatus(SuggestionStatus.DISCUSSING);
        when(suggestionRepository.findById(44L)).thenReturn(Optional.of(s));

        service.savePlanTasks(44L, "{\"status\":\"PLAN_READY\",\"plan\":\"do stuff\"}");

        verify(planTaskRepository, never()).save(any());
    }

    @Test
    void savePlanTasks_approvedStatusIsLocked() {
        Suggestion s = suggestion(45L);
        s.setStatus(SuggestionStatus.APPROVED);
        when(suggestionRepository.findById(45L)).thenReturn(Optional.of(s));

        service.savePlanTasks(45L, "{\"tasks\":[{\"title\":\"X\"}]}");

        verify(planTaskRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // executeApprovedSuggestion — concurrency / queue guard
    // -------------------------------------------------------------------------

    @Test
    void executeApprovedSuggestion_noRepoUrl_blocksExecution() {
        Suggestion s = suggestion(50L);
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl(null);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeApprovedSuggestion(s);

        assertThat(s.getCurrentPhase()).contains("Blocked");
        verify(messagingHelper).addMessage(eq(50L), eq(SenderType.SYSTEM), any(), contains("project hasn't been set up"));
    }

    @Test
    void executeApprovedSuggestion_emptyRepoUrl_blocksExecution() {
        Suggestion s = suggestion(51L);
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl("  ");
        when(settingsService.getSettings()).thenReturn(settings);

        service.executeApprovedSuggestion(s);

        assertThat(s.getCurrentPhase()).contains("Blocked");
    }

    @Test
    void executeApprovedSuggestion_atConcurrencyLimit_queues() {
        Suggestion s = suggestion(52L);
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl(REPO_URL);
        settings.setMaxConcurrentSuggestions(1);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(1L); // already 1 running

        service.executeApprovedSuggestion(s);

        assertThat(s.getCurrentPhase()).contains("Queued");
        verify(messagingHelper).addMessage(eq(52L), eq(SenderType.SYSTEM), any(), contains("queued"));
        verify(messagingHelper).broadcastExecutionQueueStatus();
    }

    // -------------------------------------------------------------------------
    // tryStartNextQueuedSuggestion
    // -------------------------------------------------------------------------

    @Test
    void tryStartNextQueuedSuggestion_noSlotAvailable_doesNothing() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(1);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(1L);

        service.tryStartNextQueuedSuggestion();

        verify(suggestionRepository, never()).findByStatus(SuggestionStatus.APPROVED);
    }

    @Test
    void tryStartNextQueuedSuggestion_noQueuedSuggestions_doesNothing() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(2);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(0L);
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED)).thenReturn(List.of());

        service.tryStartNextQueuedSuggestion();

        verify(messagingHelper, never()).addMessage(any(), any(), any(), any());
    }

    @Test
    void tryStartNextQueuedSuggestion_slotAvailable_startsOldestFirst() {
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl(REPO_URL);
        settings.setMaxConcurrentSuggestions(2);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(any())).thenReturn(0L);

        Suggestion older = suggestion(60L);
        older.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        Suggestion newer = suggestion(61L);
        newer.setCreatedAt(Instant.parse("2024-06-01T00:00:00Z"));
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED))
                .thenReturn(List.of(newer, older));

        service.tryStartNextQueuedSuggestion();

        // The oldest (60L) gets the "slot opened up" message
        verify(messagingHelper).addMessage(eq(60L), eq(SenderType.SYSTEM), any(), contains("slot"));
    }

    // -------------------------------------------------------------------------
    // markRemainingTasksCompleted (via reflection)
    // -------------------------------------------------------------------------

    @Test
    void markRemainingTasksCompleted_marksNonTerminalTasksDone() throws Exception {
        PlanTask pending = planTask(1, TaskStatus.PENDING);
        PlanTask inProgress = planTask(2, TaskStatus.IN_PROGRESS);
        PlanTask done = planTask(3, TaskStatus.COMPLETED);
        PlanTask failed = planTask(4, TaskStatus.FAILED);

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(70L))
                .thenReturn(List.of(pending, inProgress, done, failed));

        invokeMarkRemainingTasksCompleted(70L);

        assertThat(pending.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(inProgress.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.COMPLETED); // unchanged
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);  // unchanged
    }

    // -------------------------------------------------------------------------
    // buildTasksJsonForExecution (via reflection)
    // -------------------------------------------------------------------------

    @Test
    void buildTasksJsonForExecution_includesTaskTitlesAndDescriptions() throws Exception {
        PlanTask t1 = planTask(1, TaskStatus.PENDING);
        t1.setTitle("Set up database");
        t1.setDescription("Create tables");
        t1.setEstimatedMinutes(15);

        PlanTask t2 = planTask(2, TaskStatus.PENDING);
        t2.setTitle("Build API");
        t2.setDescription(null);

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(80L))
                .thenReturn(List.of(t1, t2));

        String result = invokeBuildTasksJson(80L);

        assertThat(result).contains("Set up database");
        assertThat(result).contains("Create tables");
        assertThat(result).contains("15 min");
        assertThat(result).contains("Build API");
    }

    @Test
    void buildTasksJsonForExecution_noTasks_returnsNull() throws Exception {
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(81L)).thenReturn(List.of());

        String result = invokeBuildTasksJson(81L);

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Suggestion suggestion(Long id) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setTitle("Test suggestion " + id);
        s.setDescription("Description for " + id);
        s.setStatus(SuggestionStatus.APPROVED);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private PlanTask planTask(int order, TaskStatus status) {
        PlanTask t = new PlanTask();
        t.setTaskOrder(order);
        t.setTitle("Task " + order);
        t.setStatus(status);
        return t;
    }

    private void stubFullSettings(boolean autoMerge) {
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(GITHUB_TOKEN);
        settings.setAutoMergePr(autoMerge);
        when(settingsService.getSettings()).thenReturn(settings);
    }

    private void invokeMarkRemainingTasksCompleted(Long suggestionId) throws Exception {
        Method m = PlanExecutionService.class.getDeclaredMethod("markRemainingTasksCompleted", Long.class);
        m.setAccessible(true);
        m.invoke(service, suggestionId);
    }

    private String invokeBuildTasksJson(Long suggestionId) throws Exception {
        Method m = PlanExecutionService.class.getDeclaredMethod("buildTasksJsonForExecution", Long.class);
        m.setAccessible(true);
        return (String) m.invoke(service, suggestionId);
    }
}
