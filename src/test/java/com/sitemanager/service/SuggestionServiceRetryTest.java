package com.sitemanager.service;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SuggestionService.retrySuggestion().
 */
class SuggestionServiceRetryTest {

    private SuggestionRepository suggestionRepository;
    private PlanTaskRepository planTaskRepository;
    private SuggestionMessagingHelper messagingHelper;
    private PlanExecutionService planExecutionService;
    private SuggestionService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        SuggestionMessageRepository messageRepository = mock(SuggestionMessageRepository.class);
        planTaskRepository = mock(PlanTaskRepository.class);
        messagingHelper = mock(SuggestionMessagingHelper.class);
        planExecutionService = mock(PlanExecutionService.class);

        SlackNotificationService slackNotificationService = mock(SlackNotificationService.class);
        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SuggestionMessage stubMsg = new SuggestionMessage(1L, com.sitemanager.model.enums.SenderType.SYSTEM, "System", "msg");
        stubMsg.setId(1L);
        when(messagingHelper.addMessage(any(), any(), any(), any())).thenReturn(stubMsg);

        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(planTaskRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ExpertReviewService expertReviewService = mock(ExpertReviewService.class);
        SiteSettingsService siteSettingsService = mock(SiteSettingsService.class);
        when(siteSettingsService.getSettings()).thenReturn(new com.sitemanager.model.SiteSettings());

        service = new SuggestionService(
                suggestionRepository,
                messageRepository,
                planTaskRepository,
                mock(ClaudeService.class),
                siteSettingsService,
                slackNotificationService,
                messagingHelper,
                expertReviewService,
                planExecutionService
        );
    }

    // -------------------------------------------------------------------------
    // 403 guard — not the owner
    // -------------------------------------------------------------------------

    @Test
    void retrySuggestion_wrongOwner_throws403() {
        Suggestion s = suggestion(50L, SuggestionStatus.IN_PROGRESS, "Task 1 failed — can retry");
        s.setAuthorName("bob");
        when(suggestionRepository.findById(50L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.retrySuggestion(50L, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -------------------------------------------------------------------------
    // 400 guard — wrong status
    // -------------------------------------------------------------------------

    @Test
    void retrySuggestion_notInProgress_throws400() {
        Suggestion s = suggestion(1L, SuggestionStatus.APPROVED, "Task 1 failed — can retry");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.retrySuggestion(1L, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void retrySuggestion_inProgressButNoFailedPhase_throws400() {
        Suggestion s = suggestion(2L, SuggestionStatus.IN_PROGRESS, "Running task 3");
        when(suggestionRepository.findById(2L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.retrySuggestion(2L, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void retrySuggestion_nullPhase_throws400() {
        Suggestion s = suggestion(3L, SuggestionStatus.IN_PROGRESS, null);
        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.retrySuggestion(3L, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // -------------------------------------------------------------------------
    // 404 guard — suggestion not found
    // -------------------------------------------------------------------------

    @Test
    void retrySuggestion_notFound_throws404() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retrySuggestion(99L, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // Happy path — IN_PROGRESS with various "failed" phase strings
    // -------------------------------------------------------------------------

    @Test
    void retrySuggestion_taskFailedPhase_resetsFailedTasksToPending() {
        Suggestion s = suggestion(10L, SuggestionStatus.IN_PROGRESS, "Task 2 failed — can retry");
        s.setFailureReason("Some failure");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(s));

        PlanTask t1 = planTask(1, TaskStatus.COMPLETED, 0);
        PlanTask t2 = planTask(2, TaskStatus.FAILED, 2);
        t2.setFailureReason("Build error");
        when(planTaskRepository.findBySuggestionIdAndStatus(10L, TaskStatus.FAILED)).thenReturn(List.of(t2));

        service.retrySuggestion(10L, "alice");

        assertThat(t2.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t2.getRetryCount()).isEqualTo(0);
        assertThat(t2.getFailureReason()).isNull();
    }

    @Test
    void retrySuggestion_clearsSuggestionFailureReason() {
        Suggestion s = suggestion(11L, SuggestionStatus.IN_PROGRESS, "Task 1 permanently failed");
        s.setFailureReason("Task failed badly");
        when(suggestionRepository.findById(11L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(11L, TaskStatus.FAILED)).thenReturn(List.of());

        service.retrySuggestion(11L, "alice");

        assertThat(s.getFailureReason()).isNull();
    }

    @Test
    void retrySuggestion_setsCurrentPhaseToRetrying() {
        Suggestion s = suggestion(12L, SuggestionStatus.IN_PROGRESS, "Failed — can retry");
        when(suggestionRepository.findById(12L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(12L, TaskStatus.FAILED)).thenReturn(List.of());

        service.retrySuggestion(12L, "alice");

        assertThat(s.getCurrentPhase()).isEqualTo("Retrying after failure");
    }

    @Test
    void retrySuggestion_savesSuggestion() {
        Suggestion s = suggestion(13L, SuggestionStatus.IN_PROGRESS, "Task 3 failed — can retry");
        when(suggestionRepository.findById(13L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(13L, TaskStatus.FAILED)).thenReturn(List.of());

        service.retrySuggestion(13L, "alice");

        verify(suggestionRepository).save(s);
    }

    @Test
    void retrySuggestion_broadcastsUpdate() {
        Suggestion s = suggestion(14L, SuggestionStatus.IN_PROGRESS, "Task 2 failed — can retry");
        when(suggestionRepository.findById(14L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(14L, TaskStatus.FAILED)).thenReturn(List.of());

        service.retrySuggestion(14L, "alice");

        verify(messagingHelper).broadcastUpdate(s);
    }

    @Test
    void retrySuggestion_callsExecuteNextTask() {
        Suggestion s = suggestion(15L, SuggestionStatus.IN_PROGRESS, "Task 1 failed — can retry");
        when(suggestionRepository.findById(15L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(15L, TaskStatus.FAILED)).thenReturn(List.of());

        service.retrySuggestion(15L, "alice");

        verify(planExecutionService).executeNextTask(15L);
    }

    @Test
    void retrySuggestion_returnsSuggestion() {
        Suggestion s = suggestion(16L, SuggestionStatus.IN_PROGRESS, "Failed — can retry");
        when(suggestionRepository.findById(16L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(16L, TaskStatus.FAILED)).thenReturn(List.of());

        Suggestion result = service.retrySuggestion(16L, "alice");

        assertThat(result).isSameAs(s);
    }

    @Test
    void retrySuggestion_caseInsensitiveFailedCheck_upperCaseFailed() {
        // "Failed — can retry" has capital F
        Suggestion s = suggestion(17L, SuggestionStatus.IN_PROGRESS, "Failed — can retry");
        when(suggestionRepository.findById(17L)).thenReturn(Optional.of(s));
        when(planTaskRepository.findBySuggestionIdAndStatus(17L, TaskStatus.FAILED)).thenReturn(List.of());

        // Should not throw
        Suggestion result = service.retrySuggestion(17L, "alice");
        assertThat(result).isSameAs(s);
    }

    @Test
    void retrySuggestion_multipleFailedTasks_allReset() {
        Suggestion s = suggestion(18L, SuggestionStatus.IN_PROGRESS, "Task 2 failed — can retry");
        when(suggestionRepository.findById(18L)).thenReturn(Optional.of(s));

        PlanTask t1 = planTask(1, TaskStatus.FAILED, 3);
        t1.setFailureReason("Error in t1");
        PlanTask t2 = planTask(2, TaskStatus.FAILED, 1);
        t2.setFailureReason("Error in t2");
        when(planTaskRepository.findBySuggestionIdAndStatus(18L, TaskStatus.FAILED)).thenReturn(List.of(t1, t2));

        service.retrySuggestion(18L, "alice");

        assertThat(t1.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t1.getRetryCount()).isEqualTo(0);
        assertThat(t1.getFailureReason()).isNull();
        assertThat(t2.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t2.getRetryCount()).isEqualTo(0);
        assertThat(t2.getFailureReason()).isNull();
    }

    @Test
    void retrySuggestion_savesAllFailedTasks() {
        Suggestion s = suggestion(19L, SuggestionStatus.IN_PROGRESS, "Task 1 permanently failed");
        when(suggestionRepository.findById(19L)).thenReturn(Optional.of(s));

        PlanTask t1 = planTask(1, TaskStatus.FAILED, 3);
        when(planTaskRepository.findBySuggestionIdAndStatus(19L, TaskStatus.FAILED)).thenReturn(List.of(t1));

        service.retrySuggestion(19L, "alice");

        verify(planTaskRepository).saveAll(List.of(t1));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Suggestion suggestion(Long id, SuggestionStatus status, String currentPhase) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setTitle("Test suggestion " + id);
        s.setDescription("Description");
        s.setStatus(status);
        s.setCurrentPhase(currentPhase);
        s.setCreatedAt(Instant.now());
        s.setAuthorName("alice");
        return s;
    }

    private PlanTask planTask(int order, TaskStatus status, int retryCount) {
        PlanTask t = new PlanTask();
        t.setTaskOrder(order);
        t.setTitle("Task " + order);
        t.setStatus(status);
        t.setRetryCount(retryCount);
        return t;
    }
}
