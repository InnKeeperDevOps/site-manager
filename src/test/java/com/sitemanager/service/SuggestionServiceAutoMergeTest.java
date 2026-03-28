package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SuggestionServiceAutoMergeTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private SuggestionWebSocketHandler webSocketHandler;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SiteSettingsService siteSettingsService;
    private SlackNotificationService slackNotificationService;
    private ClaudeService claudeService;
    private SuggestionService service;

    private static final String REPO_URL = "https://github.com/owner/repo";
    private static final String TOKEN = "ghp_test";
    private static final String PR_URL = "https://github.com/owner/repo/pull/7";
    private static final int PR_NUMBER = 7;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        siteSettingsService = mock(SiteSettingsService.class);
        slackNotificationService = mock(SlackNotificationService.class);
        claudeService = mock(ClaudeService.class);

        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByRole(any())).thenReturn(java.util.List.of());

        SuggestionMessage savedMsg = mock(SuggestionMessage.class);
        when(savedMsg.getId()).thenReturn(0L);
        when(messageRepository.save(any())).thenReturn(savedMsg);

        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new SuggestionService(
                suggestionRepository,
                messageRepository,
                mock(PlanTaskRepository.class),
                claudeService,
                siteSettingsService,
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                userRepository
        );
    }

    // --- Helpers ---

    private SiteSettings settingsWithAutoMerge(boolean autoMerge) {
        SiteSettings settings = new SiteSettings();
        settings.setTargetRepoUrl(REPO_URL);
        settings.setGithubToken(TOKEN);
        settings.setAutoMergePr(autoMerge);
        return settings;
    }

    private Suggestion retryableSuggestion() {
        Suggestion s = new Suggestion();
        s.setId(5L);
        s.setTitle("My feature");
        s.setCurrentPhase("Done — review request failed");
        return s;
    }

    @SuppressWarnings("unchecked")
    private void stubPrCreation() throws Exception {
        when(claudeService.createGitHubPullRequest(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("html_url", PR_URL, "number", PR_NUMBER));
    }

    // --- retryPrCreation: auto-merge enabled, merge succeeds ---

    @Test
    void retryPrCreation_autoMergeEnabled_mergeSucceeds_statusBecomeMerged() throws Exception {
        Suggestion suggestion = retryableSuggestion();
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));
        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(true);

        service.retryPrCreation(5L);

        verify(claudeService).mergePullRequest(REPO_URL, PR_NUMBER, TOKEN);
        assert suggestion.getStatus() == SuggestionStatus.MERGED;
        assert "PR automatically merged into main".equals(suggestion.getCurrentPhase());
    }

    @Test
    void retryPrCreation_autoMergeEnabled_mergeSucceeds_sendsSlackMergedNotification() throws Exception {
        Suggestion suggestion = retryableSuggestion();
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));
        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(true);

        service.retryPrCreation(5L);

        verify(slackNotificationService).sendNotification(eq(suggestion), contains("merged"));
    }

    // --- retryPrCreation: auto-merge enabled, merge fails ---

    @Test
    void retryPrCreation_autoMergeEnabled_mergeFails_statusStaysFinalReview() throws Exception {
        Suggestion suggestion = retryableSuggestion();
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));
        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(false);

        service.retryPrCreation(5L);

        verify(claudeService).mergePullRequest(REPO_URL, PR_NUMBER, TOKEN);
        assert suggestion.getStatus() == SuggestionStatus.FINAL_REVIEW;
    }

    @Test
    void retryPrCreation_autoMergeEnabled_mergeFails_sendsSlackFailureNotification() throws Exception {
        Suggestion suggestion = retryableSuggestion();
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));
        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(false);

        service.retryPrCreation(5L);

        verify(slackNotificationService).sendNotification(eq(suggestion), contains("manual"));
    }

    // --- retryPrCreation: auto-merge disabled ---

    @Test
    void retryPrCreation_autoMergeDisabled_mergeNeverCalled() throws Exception {
        Suggestion suggestion = retryableSuggestion();
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));
        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(false));
        stubPrCreation();

        service.retryPrCreation(5L);

        verify(claudeService, never()).mergePullRequest(anyString(), anyInt(), anyString());
        assert suggestion.getStatus() == SuggestionStatus.FINAL_REVIEW;
    }

    // --- createPrForSuggestion (via reflection): auto-merge enabled, merge succeeds ---

    @Test
    void createPrForSuggestion_autoMergeEnabled_mergeSucceeds_statusBecomeMerged() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(10L);
        suggestion.setTitle("New feature");
        suggestion.setWorkingDirectory("/tmp/work");

        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubGitOps();
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(true);

        invokeCreatePrForSuggestion(suggestion);

        verify(claudeService).mergePullRequest(REPO_URL, PR_NUMBER, TOKEN);
        assert suggestion.getStatus() == SuggestionStatus.MERGED;
        assert "PR automatically merged into main".equals(suggestion.getCurrentPhase());
    }

    @Test
    void createPrForSuggestion_autoMergeEnabled_mergeSucceeds_sendsSlackMergedNotification() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(11L);
        suggestion.setTitle("Another feature");
        suggestion.setWorkingDirectory("/tmp/work");

        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubGitOps();
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(true);

        invokeCreatePrForSuggestion(suggestion);

        verify(slackNotificationService).sendNotification(eq(suggestion), contains("merged"));
    }

    @Test
    void createPrForSuggestion_autoMergeEnabled_mergeFails_statusStaysFinalReview() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(12L);
        suggestion.setTitle("Yet another feature");
        suggestion.setWorkingDirectory("/tmp/work");

        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubGitOps();
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(false);

        invokeCreatePrForSuggestion(suggestion);

        verify(claudeService).mergePullRequest(REPO_URL, PR_NUMBER, TOKEN);
        assert suggestion.getStatus() == SuggestionStatus.FINAL_REVIEW;
    }

    @Test
    void createPrForSuggestion_autoMergeEnabled_mergeFails_sendsSlackFailureNotification() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(13L);
        suggestion.setTitle("Feature X");
        suggestion.setWorkingDirectory("/tmp/work");

        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(true));
        stubGitOps();
        stubPrCreation();
        when(claudeService.mergePullRequest(eq(REPO_URL), eq(PR_NUMBER), eq(TOKEN)))
                .thenReturn(false);

        invokeCreatePrForSuggestion(suggestion);

        verify(slackNotificationService).sendNotification(eq(suggestion), contains("manual"));
    }

    @Test
    void createPrForSuggestion_autoMergeDisabled_mergeNeverCalled() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(14L);
        suggestion.setTitle("Feature Y");
        suggestion.setWorkingDirectory("/tmp/work");

        when(siteSettingsService.getSettings()).thenReturn(settingsWithAutoMerge(false));
        stubGitOps();
        stubPrCreation();

        invokeCreatePrForSuggestion(suggestion);

        verify(claudeService, never()).mergePullRequest(anyString(), anyInt(), anyString());
        assert suggestion.getStatus() == SuggestionStatus.FINAL_REVIEW;
    }

    // --- Private helpers ---

    private void stubGitOps() throws Exception {
        doNothing().when(claudeService).stageAllChanges(anyString());
        when(claudeService.getStagedDiffSummary(anyString())).thenReturn("some diff");
        when(claudeService.generateCommitMessage(any(), anyString(), anyString(), anyString()))
                .thenReturn("feat: auto commit");
        when(claudeService.commitStagedChanges(anyString(), anyString())).thenReturn(true);
        doNothing().when(claudeService).pushBranch(anyString(), anyString());
    }

    private void invokeCreatePrForSuggestion(Suggestion suggestion) throws Exception {
        Method method = SuggestionService.class.getDeclaredMethod("createPrForSuggestion", Suggestion.class);
        method.setAccessible(true);
        method.invoke(service, suggestion);
    }
}
