package com.sitemanager.service;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SuggestionMessagingHelperTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private PlanTaskRepository planTaskRepository;
    private SuggestionWebSocketHandler webSocketHandler;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SlackNotificationService slackNotificationService;
    private SiteSettingsService settingsService;
    private SuggestionMessagingHelper helper;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        planTaskRepository = mock(PlanTaskRepository.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        slackNotificationService = mock(SlackNotificationService.class);
        settingsService = mock(SiteSettingsService.class);

        when(settingsService.getSettings()).thenReturn(new SiteSettings());

        helper = new SuggestionMessagingHelper(
                suggestionRepository,
                messageRepository,
                planTaskRepository,
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                settingsService
        );
    }

    // --- addMessage ---

    @Test
    void addMessage_savesMessageToRepository() {
        SuggestionMessage saved = mock(SuggestionMessage.class);
        when(saved.getId()).thenReturn(42L);
        when(saved.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(messageRepository.save(any())).thenReturn(saved);

        SuggestionMessage result = helper.addMessage(1L, SenderType.SYSTEM, "System", "Hello");

        verify(messageRepository).save(any(SuggestionMessage.class));
        assertThat(result).isSameAs(saved);
    }

    @Test
    void addMessage_broadcastsMessageViaWebSocket() {
        SuggestionMessage saved = mock(SuggestionMessage.class);
        when(saved.getId()).thenReturn(5L);
        when(saved.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(messageRepository.save(any())).thenReturn(saved);

        helper.addMessage(10L, SenderType.AI, "Claude", "Plan ready");

        verify(webSocketHandler).sendToSuggestion(eq(10L), argThat(msg ->
                msg.contains("\"type\":\"message\"") &&
                msg.contains("\"id\":5") &&
                msg.contains("\"senderType\":\"AI\"") &&
                msg.contains("\"senderName\":\"Claude\"") &&
                msg.contains("\"content\":\"Plan ready\"")
        ));
    }

    @Test
    void addMessage_escapesSpecialCharactersInContent() {
        SuggestionMessage saved = mock(SuggestionMessage.class);
        when(saved.getId()).thenReturn(1L);
        when(saved.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(messageRepository.save(any())).thenReturn(saved);

        helper.addMessage(1L, SenderType.USER, "alice", "line1\nline2");

        verify(webSocketHandler).sendToSuggestion(eq(1L), argThat(msg ->
                msg.contains("\"content\":\"line1\\nline2\"")
        ));
    }

    // --- broadcastUpdate ---

    @Test
    void broadcastUpdate_sendsStatusUpdateWithAllFields() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(7L);
        suggestion.setStatus(SuggestionStatus.IN_PROGRESS);
        suggestion.setCurrentPhase("Working on task 1");
        suggestion.setUpVotes(3);
        suggestion.setDownVotes(1);
        suggestion.setPlanDisplaySummary("Build the feature");
        suggestion.setPlanSummary("Detailed plan");

        helper.broadcastUpdate(suggestion);

        verify(webSocketHandler).sendToSuggestion(eq(7L), argThat(msg ->
                msg.contains("\"type\":\"status_update\"") &&
                msg.contains("\"status\":\"IN_PROGRESS\"") &&
                msg.contains("\"currentPhase\":\"Working on task 1\"") &&
                msg.contains("\"upVotes\":3") &&
                msg.contains("\"downVotes\":1") &&
                msg.contains("\"planDisplaySummary\":\"Build the feature\"") &&
                msg.contains("\"planSummary\":\"Detailed plan\"")
        ));
    }

    @Test
    void broadcastUpdate_nullFields_usesEmptyStrings() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(8L);
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setCurrentPhase(null);
        suggestion.setPlanDisplaySummary(null);
        suggestion.setPlanSummary(null);

        helper.broadcastUpdate(suggestion);

        verify(webSocketHandler).sendToSuggestion(eq(8L), argThat(msg ->
                msg.contains("\"currentPhase\":\"\"") &&
                msg.contains("\"planDisplaySummary\":\"\"") &&
                msg.contains("\"planSummary\":\"\"")
        ));
    }

    @Test
    void broadcastUpdate_withFailureReason_includesFailureReasonInPayload() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(9L);
        suggestion.setStatus(SuggestionStatus.IN_PROGRESS);
        suggestion.setFailureReason("Task 'Build form' failed: connection timed out");

        helper.broadcastUpdate(suggestion);

        verify(webSocketHandler).sendToSuggestion(eq(9L), argThat(msg ->
                msg.contains("\"failureReason\"") &&
                msg.contains("connection timed out")
        ));
    }

    @Test
    void broadcastUpdate_nullFailureReason_usesEmptyString() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(10L);
        suggestion.setStatus(SuggestionStatus.IN_PROGRESS);
        // failureReason not set — should be null

        helper.broadcastUpdate(suggestion);

        verify(webSocketHandler).sendToSuggestion(eq(10L), argThat(msg ->
                msg.contains("\"failureReason\":\"\"")
        ));
    }

    // --- broadcastClarificationQuestions ---

    @Test
    void broadcastClarificationQuestions_sendsQuestionsViaWebSocket() {
        helper.broadcastClarificationQuestions(3L, List.of("Why?", "How?"));

        verify(webSocketHandler).sendToSuggestion(eq(3L), argThat(msg ->
                msg.contains("\"type\":\"clarification_questions\"") &&
                msg.contains("\"Why?\"") &&
                msg.contains("\"How?\"")
        ));
    }

    @Test
    void broadcastClarificationQuestions_sendsNotificationToAuthor() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("New feature");
        suggestion.setAuthorName("alice");
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));

        helper.broadcastClarificationQuestions(5L, List.of("Q1?", "Q2?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("alice"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 5L,
                        "suggestionTitle", "New feature",
                        "questionCount", 2
                ))
        );
    }

    @Test
    void broadcastClarificationQuestions_nullAuthor_doesNotSendUserNotification() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Feature");
        suggestion.setAuthorName(null);
        when(suggestionRepository.findById(6L)).thenReturn(Optional.of(suggestion));

        helper.broadcastClarificationQuestions(6L, List.of("Q?"));

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void broadcastClarificationQuestions_suggestionNotFound_doesNotSendUserNotification() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());

        helper.broadcastClarificationQuestions(99L, List.of("Q?"));

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void broadcastClarificationQuestions_nullTitle_usesEmptyString() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle(null);
        suggestion.setAuthorName("bob");
        when(suggestionRepository.findById(7L)).thenReturn(Optional.of(suggestion));

        helper.broadcastClarificationQuestions(7L, List.of("Q?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("bob"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 7L,
                        "suggestionTitle", "",
                        "questionCount", 1
                ))
        );
    }

    // --- broadcastTaskUpdate ---

    @Test
    void broadcastTaskUpdate_sendsTaskJsonViaWebSocket() {
        PlanTask task = new PlanTask();
        task.setTaskOrder(2);
        task.setTitle("Write tests");
        task.setStatus(TaskStatus.IN_PROGRESS);

        helper.broadcastTaskUpdate(11L, task);

        verify(webSocketHandler).sendToSuggestion(eq(11L), argThat(msg ->
                msg.contains("\"type\":\"task_update\"") &&
                msg.contains("\"task\":")
        ));
    }

    // --- broadcastTaskActivity ---

    @Test
    void broadcastTaskActivity_sendsActivityMessageViaWebSocket() {
        helper.broadcastTaskActivity(12L, 3, "Writing migration");

        verify(webSocketHandler).sendToSuggestion(eq(12L), argThat(msg ->
                msg.contains("\"type\":\"task_activity\"") &&
                msg.contains("\"taskOrder\":3") &&
                msg.contains("\"detail\":\"Writing migration\"")
        ));
    }

    @Test
    void broadcastTaskActivity_escapesSpecialCharactersInDetail() {
        helper.broadcastTaskActivity(12L, 1, "line1\nline2");

        verify(webSocketHandler).sendToSuggestion(eq(12L), argThat(msg ->
                msg.contains("\"detail\":\"line1\\nline2\"")
        ));
    }

    // --- broadcastTasks ---

    @Test
    void broadcastTasks_fetchesTasksAndSendsViaWebSocket() {
        PlanTask t1 = new PlanTask();
        t1.setTaskOrder(1);
        t1.setTitle("Task one");
        PlanTask t2 = new PlanTask();
        t2.setTaskOrder(2);
        t2.setTitle("Task two");

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(20L)).thenReturn(List.of(t1, t2));

        helper.broadcastTasks(20L);

        verify(planTaskRepository).findBySuggestionIdOrderByTaskOrder(20L);
        verify(webSocketHandler).sendToSuggestion(eq(20L), argThat(msg ->
                msg.contains("\"type\":\"tasks_update\"") &&
                msg.contains("\"tasks\":")
        ));
    }

    @Test
    void broadcastTasks_emptyList_sendsEmptyArray() {
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(21L)).thenReturn(List.of());

        helper.broadcastTasks(21L);

        verify(webSocketHandler).sendToSuggestion(eq(21L), argThat(msg ->
                msg.contains("\"tasks\":[]")
        ));
    }

    // --- broadcastExpertNote ---

    @Test
    void broadcastExpertNote_sendsExpertNoteViaWebSocket() {
        helper.broadcastExpertNote(30L, "Software Architect", "Looks good");

        verify(webSocketHandler).sendToSuggestion(eq(30L), argThat(msg ->
                msg.contains("\"type\":\"expert_note\"") &&
                msg.contains("\"expertName\":\"Software Architect\"") &&
                msg.contains("\"note\":\"Looks good\"")
        ));
    }

    @Test
    void broadcastExpertNote_escapesSpecialCharactersInNote() {
        helper.broadcastExpertNote(30L, "Expert", "Note with \"quotes\"");

        verify(webSocketHandler).sendToSuggestion(eq(30L), argThat(msg ->
                msg.contains("\"note\":\"Note with \\\"quotes\\\"\"")
        ));
    }

    // --- broadcastExpertReviewStatus ---

    @Test
    void broadcastExpertReviewStatus_suggestionNotFound_doesNotSendWebSocket() {
        when(suggestionRepository.findById(40L)).thenReturn(Optional.empty());

        helper.broadcastExpertReviewStatus(40L);

        verify(webSocketHandler, never()).sendToSuggestion(any(), any());
    }

    @Test
    void broadcastExpertReviewStatus_nullExpertReviewStep_doesNotSendWebSocket() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(41L);
        suggestion.setExpertReviewStep(null);
        when(suggestionRepository.findById(41L)).thenReturn(Optional.of(suggestion));

        helper.broadcastExpertReviewStatus(41L);

        verify(webSocketHandler, never()).sendToSuggestion(any(), any());
    }

    @Test
    void broadcastExpertReviewStatus_sendsStatusWithCorrectType() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(42L);
        suggestion.setExpertReviewStep(2);
        suggestion.setExpertReviewRound(1);
        when(suggestionRepository.findById(42L)).thenReturn(Optional.of(suggestion));

        helper.broadcastExpertReviewStatus(42L);

        verify(webSocketHandler).sendToSuggestion(eq(42L), argThat(msg ->
                msg.startsWith("{\"type\":\"expert_review_status\"")
        ));
    }

    @Test
    void broadcastExpertReviewStatus_includesStepAndRoundInfo() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(43L);
        suggestion.setExpertReviewStep(1);
        suggestion.setExpertReviewRound(2);
        when(suggestionRepository.findById(43L)).thenReturn(Optional.of(suggestion));

        helper.broadcastExpertReviewStatus(43L);

        verify(webSocketHandler).sendToSuggestion(eq(43L), argThat(msg ->
                msg.contains("\"currentStep\":1") &&
                msg.contains("\"round\":2") &&
                msg.contains("\"maxRounds\":" + SuggestionMessagingHelper.MAX_EXPERT_REVIEW_ROUNDS) &&
                msg.contains("\"totalSteps\":" + ExpertRole.reviewOrder().length)
        ));
    }

    @Test
    void broadcastExpertReviewStatus_nullRound_defaultsToOne() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(44L);
        suggestion.setExpertReviewStep(0);
        suggestion.setExpertReviewRound(null);
        when(suggestionRepository.findById(44L)).thenReturn(Optional.of(suggestion));

        helper.broadcastExpertReviewStatus(44L);

        verify(webSocketHandler).sendToSuggestion(eq(44L), argThat(msg ->
                msg.contains("\"round\":1")
        ));
    }

    @Test
    void broadcastExpertReviewStatus_marksCompletedInProgressAndPendingExperts() {
        int currentStep = 2;
        Suggestion suggestion = new Suggestion();
        suggestion.setId(45L);
        suggestion.setExpertReviewStep(currentStep);
        suggestion.setExpertReviewRound(1);
        when(suggestionRepository.findById(45L)).thenReturn(Optional.of(suggestion));

        helper.broadcastExpertReviewStatus(45L);

        verify(webSocketHandler).sendToSuggestion(eq(45L), argThat(msg ->
                msg.contains("\"completed\"") &&
                msg.contains("\"in_progress\"") &&
                msg.contains("\"pending\"")
        ));
    }

    // --- broadcastExecutionQueueStatus ---

    @Test
    void broadcastExecutionQueueStatus_broadcastsToAllUsers() {
        when(suggestionRepository.countByStatusIn(anyList())).thenReturn(1L);
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED)).thenReturn(new java.util.ArrayList<>());

        helper.broadcastExecutionQueueStatus();

        verify(userNotificationHandler).broadcastToAll(argThat(payload ->
                "execution_queue_status".equals(payload.get("type"))
        ));
    }

    @Test
    void broadcastExecutionQueueStatus_includesQueuedSuggestionsInOrder() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(2);
        when(settingsService.getSettings()).thenReturn(settings);

        Suggestion first = new Suggestion();
        first.setId(10L);
        first.setTitle("First");
        first.setCreatedAt(Instant.ofEpochSecond(100));

        Suggestion second = new Suggestion();
        second.setId(11L);
        second.setTitle("Second");
        second.setCreatedAt(Instant.ofEpochSecond(200));

        when(suggestionRepository.countByStatusIn(anyList())).thenReturn(1L);
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED)).thenReturn(new java.util.ArrayList<>(List.of(second, first)));

        helper.broadcastExecutionQueueStatus();

        verify(userNotificationHandler).broadcastToAll(argThat(payload -> {
            @SuppressWarnings("unchecked")
            var queued = (List<Map<String, Object>>) payload.get("queued");
            return queued != null &&
                   queued.size() == 2 &&
                   queued.get(0).get("id").equals(10L) &&   // first by createdAt
                   queued.get(0).get("position").equals(1) &&
                   queued.get(1).get("id").equals(11L) &&
                   queued.get(1).get("position").equals(2);
        }));
    }

    @Test
    void broadcastExecutionQueueStatus_nullMaxConcurrent_defaultsToOne() {
        SiteSettings settings = new SiteSettings();
        settings.setMaxConcurrentSuggestions(null);
        when(settingsService.getSettings()).thenReturn(settings);
        when(suggestionRepository.countByStatusIn(anyList())).thenReturn(0L);
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED)).thenReturn(new java.util.ArrayList<>());

        helper.broadcastExecutionQueueStatus();

        verify(userNotificationHandler).broadcastToAll(argThat(payload ->
                Integer.valueOf(1).equals(payload.get("maxConcurrent"))
        ));
    }

    @Test
    void broadcastExecutionQueueStatus_includesActiveCount() {
        when(suggestionRepository.countByStatusIn(anyList())).thenReturn(3L);
        when(suggestionRepository.findByStatus(SuggestionStatus.APPROVED)).thenReturn(new java.util.ArrayList<>());

        helper.broadcastExecutionQueueStatus();

        verify(userNotificationHandler).broadcastToAll(argThat(payload ->
                Long.valueOf(3L).equals(payload.get("activeCount"))
        ));
    }

    // --- broadcastProgress ---

    @Test
    void broadcastProgress_sendsProgressTypeViaWebSocket() {
        helper.broadcastProgress(55L, "Analysing your suggestion...");

        verify(webSocketHandler).sendToSuggestion(eq(55L), argThat(msg ->
                msg.contains("\"type\":\"progress\"") &&
                msg.contains("\"content\":\"Analysing your suggestion...\"")
        ));
    }

    @Test
    void broadcastProgress_escapesSpecialCharactersInContent() {
        helper.broadcastProgress(56L, "line1\nline2");

        verify(webSocketHandler).sendToSuggestion(eq(56L), argThat(msg ->
                msg.contains("\"content\":\"line1\\nline2\"")
        ));
    }

    @Test
    void broadcastProgress_sendsToCorrectSuggestion() {
        helper.broadcastProgress(77L, "In progress");
        helper.broadcastProgress(88L, "Also in progress");

        verify(webSocketHandler).sendToSuggestion(eq(77L), anyString());
        verify(webSocketHandler).sendToSuggestion(eq(88L), anyString());
    }

    // --- escapeJson ---

    @Test
    void escapeJson_nullInput_returnsEmptyString() {
        assertThat(helper.escapeJson(null)).isEqualTo("");
    }

    @Test
    void escapeJson_escapesBackslash() {
        assertThat(helper.escapeJson("a\\b")).isEqualTo("a\\\\b");
    }

    @Test
    void escapeJson_escapesDoubleQuotes() {
        assertThat(helper.escapeJson("say \"hi\"")).isEqualTo("say \\\"hi\\\"");
    }

    @Test
    void escapeJson_escapesNewline() {
        assertThat(helper.escapeJson("line1\nline2")).isEqualTo("line1\\nline2");
    }

    @Test
    void escapeJson_escapesCarriageReturn() {
        assertThat(helper.escapeJson("line1\rline2")).isEqualTo("line1\\rline2");
    }

    @Test
    void escapeJson_escapesTab() {
        assertThat(helper.escapeJson("col1\tcol2")).isEqualTo("col1\\tcol2");
    }

    @Test
    void escapeJson_plainString_returnsUnchanged() {
        assertThat(helper.escapeJson("hello world")).isEqualTo("hello world");
    }
}
