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
import com.sitemanager.service.ExpertReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SuggestionServiceDraftTest {

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

        when(claudeService.generateSessionId()).thenReturn("test-session");
        when(siteSettingsService.getSettings()).thenReturn(new SiteSettings());
        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(userRepository.findByRole(any())).thenReturn(List.of());

        SuggestionMessage savedMsg = mock(SuggestionMessage.class);
        when(savedMsg.getId()).thenReturn(0L);
        when(messageRepository.save(any())).thenReturn(savedMsg);

        // Default: save returns the passed suggestion with an id
        when(suggestionRepository.save(any(Suggestion.class))).thenAnswer(inv -> {
            Suggestion s = inv.getArgument(0);
            if (s.getId() == null) {
                // Simulate DB assigning ID
                Suggestion saved = new Suggestion();
                saved.setTitle(s.getTitle());
                saved.setDescription(s.getDescription());
                saved.setAuthorName(s.getAuthorName());
                saved.setAuthorId(s.getAuthorId());
                saved.setPriority(s.getPriority());
                saved.setStatus(s.getStatus());
                return saved;
            }
            return s;
        });

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

    // --- createSuggestion with isDraft=true ---

    @Test
    void createSuggestion_isDraftTrue_savesWithDraftStatus() {
        service.createSuggestion("My draft", "Some description", 1L, "alice", Priority.MEDIUM, true);

        verify(suggestionRepository).save(argThat(s ->
                s.getStatus() == SuggestionStatus.DRAFT
        ));
    }

    @Test
    void createSuggestion_isDraftTrue_doesNotTriggerAiEvaluation() {
        service.createSuggestion("My draft", "Some description", 1L, "alice", Priority.MEDIUM, true);

        verify(claudeService, never()).evaluateSuggestion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createSuggestion_isDraftTrue_doesNotAddInitialMessage() {
        service.createSuggestion("My draft", "Some description", 1L, "alice", Priority.MEDIUM, true);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void createSuggestion_isDraftFalse_triggersAiEvaluation() {
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.createSuggestion("My idea", "Description", 1L, "alice", Priority.MEDIUM, false);

        verify(claudeService).evaluateSuggestion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createSuggestion_isDraftFalse_addsInitialMessage() {
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.createSuggestion("My idea", "Description", 1L, "alice", Priority.MEDIUM, false);

        verify(messageRepository, atLeastOnce()).save(any());
    }

    // --- updateDraft ---

    @Test
    void updateDraft_ownerCanUpdateTitleAndDescription() {
        Suggestion draft = draftOwnedBy("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(suggestionRepository.save(any())).thenReturn(draft);

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Updated title");
        req.setDescription("Updated description");

        service.updateDraft(1L, req, "alice");

        assertThat(draft.getTitle()).isEqualTo("Updated title");
        assertThat(draft.getDescription()).isEqualTo("Updated description");
        verify(suggestionRepository).save(draft);
    }

    @Test
    void updateDraft_ownerCanUpdatePriority() {
        Suggestion draft = draftOwnedBy("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(suggestionRepository.save(any())).thenReturn(draft);

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");
        req.setPriority(Priority.HIGH);

        service.updateDraft(1L, req, "alice");

        assertThat(draft.getPriority()).isEqualTo(Priority.HIGH);
    }

    @Test
    void updateDraft_nullPriority_doesNotChangePriority() {
        Suggestion draft = draftOwnedBy("alice");
        draft.setPriority(Priority.LOW);
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(suggestionRepository.save(any())).thenReturn(draft);

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");
        req.setPriority(null);

        service.updateDraft(1L, req, "alice");

        assertThat(draft.getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    void updateDraft_wrongOwner_throws403() {
        Suggestion draft = draftOwnedBy("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");

        assertThatThrownBy(() -> service.updateDraft(1L, req, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void updateDraft_notDraftStatus_throwsIllegalState() {
        Suggestion suggestion = new Suggestion();
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setAuthorName("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(suggestion));

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");
        req.setDescription("Desc");

        assertThatThrownBy(() -> service.updateDraft(1L, req, "alice"))
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
        Suggestion draft = draftOwnedBy("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(suggestionRepository.save(any())).thenReturn(draft);

        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("New title");
        req.setDescription("New description");

        service.updateDraft(1L, req, "alice");

        verify(webSocketHandler).sendToSuggestion(any(), any());
    }

    // --- submitDraft ---

    @Test
    void submitDraft_ownerCanSubmit_triggersAiEvaluation() {
        Suggestion draft = draftOwnedBy("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(suggestionRepository.save(any())).thenReturn(draft);
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.submitDraft(1L, "alice");

        verify(claudeService).evaluateSuggestion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitDraft_addsInitialMessage() {
        Suggestion draft = draftOwnedBy("alice");
        draft.setTitle("My title");
        draft.setDescription("My description");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(suggestionRepository.save(any())).thenReturn(draft);
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("PLAN_READY"));

        service.submitDraft(1L, "alice");

        verify(messageRepository, atLeastOnce()).save(any());
    }

    @Test
    void submitDraft_wrongOwner_throws403() {
        Suggestion draft = draftOwnedBy("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submitDraft(1L, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void submitDraft_notDraftStatus_throwsIllegalState() {
        Suggestion suggestion = new Suggestion();
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setAuthorName("alice");
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.submitDraft(1L, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void submitDraft_notFound_throwsIllegalArgument() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitDraft(99L, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- getMyDrafts ---

    @Test
    void getMyDrafts_returnsDraftsForUser() {
        Suggestion draft1 = draftOwnedBy("alice");
        Suggestion draft2 = draftOwnedBy("alice");
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "alice"))
                .thenReturn(List.of(draft1, draft2));

        List<Suggestion> result = service.getMyDrafts("alice");

        assertThat(result).containsExactly(draft1, draft2);
    }

    @Test
    void getMyDrafts_noMatchingDrafts_returnsEmptyList() {
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "bob"))
                .thenReturn(List.of());

        List<Suggestion> result = service.getMyDrafts("bob");

        assertThat(result).isEmpty();
    }

    @Test
    void getMyDrafts_doesNotReturnOtherUsersItems() {
        Suggestion aliceDraft = draftOwnedBy("alice");
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "alice"))
                .thenReturn(List.of(aliceDraft));
        when(suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, "bob"))
                .thenReturn(List.of());

        List<Suggestion> aliceResult = service.getMyDrafts("alice");
        List<Suggestion> bobResult = service.getMyDrafts("bob");

        assertThat(aliceResult).contains(aliceDraft);
        assertThat(bobResult).doesNotContain(aliceDraft);
    }

    // --- helpers ---

    private Suggestion draftOwnedBy(String username) {
        Suggestion s = new Suggestion();
        s.setStatus(SuggestionStatus.DRAFT);
        s.setAuthorName(username);
        s.setTitle("Draft title");
        s.setDescription("Draft description");
        return s;
    }
}
