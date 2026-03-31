package com.sitemanager.service;

import com.sitemanager.dto.UpdateDraftRequest;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.Priority;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import com.sitemanager.service.ExpertReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the three draft lifecycle service methods:
 * createSuggestion (isDraft=true), updateDraft, submitDraft, and getMyDrafts.
 */
class SuggestionDraftServiceTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private ClaudeService claudeService;
    private SiteSettingsService siteSettingsService;
    private SuggestionWebSocketHandler webSocketHandler;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SlackNotificationService slackNotificationService;
    private UserRepository userRepository;
    private SuggestionService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        claudeService = mock(ClaudeService.class);
        siteSettingsService = mock(SiteSettingsService.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        slackNotificationService = mock(SlackNotificationService.class);
        userRepository = mock(UserRepository.class);

        when(claudeService.generateSessionId()).thenReturn("session-id");
        when(siteSettingsService.getSettings()).thenReturn(new SiteSettings());
        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(userRepository.findByRole(any())).thenReturn(List.of());

        SuggestionMessage savedMsg = mock(SuggestionMessage.class);
        when(savedMsg.getId()).thenReturn(0L);
        when(messageRepository.save(any())).thenReturn(savedMsg);

        when(suggestionRepository.save(any(Suggestion.class))).thenAnswer(inv -> inv.getArgument(0));

        SuggestionMessagingHelper messagingHelper = new SuggestionMessagingHelper(
                suggestionRepository,
                messageRepository,
                mock(PlanTaskRepository.class),
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                siteSettingsService
        );

        service = new SuggestionService(
                suggestionRepository,
                messageRepository,
                mock(PlanTaskRepository.class),
                claudeService,
                siteSettingsService,
                slackNotificationService,
                messagingHelper,
                mock(ExpertReviewService.class),
                mock(PlanExecutionService.class)
        );
    }

    // -----------------------------------------------------------------------
    // createSuggestion with isDraft=true
    // -----------------------------------------------------------------------

    @Test
    void createDraft_setsDraftStatus() {
        service.createSuggestion("Draft title", "Draft desc", 1L, "alice", Priority.MEDIUM, true);

        verify(suggestionRepository).save(argThat(s ->
                s.getStatus() == SuggestionStatus.DRAFT
        ));
    }

    @Test
    void createDraft_skipsAiEvaluation() {
        service.createSuggestion("Draft title", "Draft desc", 1L, "alice", Priority.MEDIUM, true);

        verify(claudeService, never()).evaluateSuggestion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDraft_skipsInitialMessage() {
        service.createSuggestion("Draft title", "Draft desc", 1L, "alice", Priority.MEDIUM, true);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void createNonDraft_triggersAiEvaluation() {
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.createSuggestion("Normal idea", "Desc", 1L, "alice", Priority.MEDIUM, false);

        verify(claudeService).evaluateSuggestion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createNonDraft_addsInitialMessage() {
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.createSuggestion("Normal idea", "Desc", 1L, "alice", Priority.MEDIUM, false);

        verify(messageRepository, atLeastOnce()).save(any());
    }

    // -----------------------------------------------------------------------
    // updateDraft
    // -----------------------------------------------------------------------

    @Test
    void updateDraft_ownerUpdatesFieldsAndSaves() {
        Suggestion draft = draftFor("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("New title");
        req.setDescription("New desc");
        req.setPriority(Priority.HIGH);

        service.updateDraft(10L, req, "alice");

        assertThat(draft.getTitle()).isEqualTo("New title");
        assertThat(draft.getDescription()).isEqualTo("New desc");
        assertThat(draft.getPriority()).isEqualTo(Priority.HIGH);
        verify(suggestionRepository).save(draft);
    }

    @Test
    void updateDraft_nullPriorityPreservesExisting() {
        Suggestion draft = draftFor("alice");
        draft.setPriority(Priority.LOW);
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");
        req.setPriority(null);

        service.updateDraft(10L, req, "alice");

        assertThat(draft.getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    void updateDraft_nonOwner_throws403() {
        Suggestion draft = draftFor("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");

        assertThatThrownBy(() -> service.updateDraft(10L, req, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void updateDraft_alreadySubmitted_throwsIllegalState() {
        Suggestion suggestion = new Suggestion();
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setAuthorName("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(suggestion));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");

        assertThatThrownBy(() -> service.updateDraft(10L, req, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateDraft_notFound_throwsIllegalArgument() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");

        assertThatThrownBy(() -> service.updateDraft(99L, req, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDraft_broadcastsWebSocketUpdate() {
        Suggestion draft = draftFor("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");

        service.updateDraft(10L, req, "alice");

        verify(webSocketHandler).sendToSuggestion(any(), any());
    }

    // -----------------------------------------------------------------------
    // submitDraft
    // -----------------------------------------------------------------------

    @Test
    void submitDraft_setsStatusAwayFromDraft() {
        Suggestion draft = draftFor("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.submitDraft(10L, "alice");

        assertThat(draft.getStatus()).isNotEqualTo(SuggestionStatus.DRAFT);
    }

    @Test
    void submitDraft_triggersAiEvaluation() {
        Suggestion draft = draftFor("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.submitDraft(10L, "alice");

        verify(claudeService).evaluateSuggestion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitDraft_addsInitialMessage() {
        Suggestion draft = draftFor("alice");
        draft.setTitle("Title");
        draft.setDescription("Description");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.submitDraft(10L, "alice");

        verify(messageRepository, atLeastOnce()).save(any());
    }

    @Test
    void submitDraft_nonOwner_throws403() {
        Suggestion draft = draftFor("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submitDraft(10L, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void submitDraft_alreadySubmitted_throwsIllegalState() {
        Suggestion suggestion = new Suggestion();
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setAuthorName("alice");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.submitDraft(10L, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void submitDraft_notFound_throwsIllegalArgument() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitDraft(99L, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // getMyDrafts
    // -----------------------------------------------------------------------

    @Test
    void getMyDrafts_returnsOnlyUsersDrafts() {
        Suggestion d1 = draftFor("alice");
        Suggestion d2 = draftFor("alice");
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "alice"))
                .thenReturn(List.of(d1, d2));

        List<Suggestion> result = service.getMyDrafts("alice");

        assertThat(result).containsExactly(d1, d2);
    }

    @Test
    void getMyDrafts_doesNotReturnOtherUsersDrafts() {
        Suggestion aliceDraft = draftFor("alice");
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "alice"))
                .thenReturn(List.of(aliceDraft));
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "bob"))
                .thenReturn(List.of());

        assertThat(service.getMyDrafts("bob")).doesNotContain(aliceDraft);
    }

    @Test
    void getMyDrafts_emptyWhenNoDrafts() {
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "alice"))
                .thenReturn(List.of());

        assertThat(service.getMyDrafts("alice")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // helper
    // -----------------------------------------------------------------------

    private Suggestion draftFor(String username) {
        Suggestion s = new Suggestion();
        s.setStatus(SuggestionStatus.DRAFT);
        s.setAuthorName(username);
        s.setTitle("Draft title");
        s.setDescription("Draft description");
        return s;
    }
}
