package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SuggestionServiceNotificationTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionWebSocketHandler webSocketHandler;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SuggestionService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);

        service = new SuggestionService(
                suggestionRepository,
                mock(SuggestionMessageRepository.class),
                mock(PlanTaskRepository.class),
                mock(ClaudeService.class),
                mock(SiteSettingsService.class),
                webSocketHandler,
                userNotificationHandler
        );
    }

    @Test
    void broadcastClarificationQuestions_sendsUserNotificationToAuthor() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Add dark mode");
        suggestion.setAuthorName("alice");

        when(suggestionRepository.findById(42L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(42L, List.of("Q1?", "Q2?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("alice"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 42L,
                        "suggestionTitle", "Add dark mode",
                        "questionCount", 2
                ))
        );
    }

    @Test
    void broadcastClarificationQuestions_nullAuthorName_doesNotSendNotification() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Feature X");
        suggestion.setAuthorName(null);

        when(suggestionRepository.findById(99L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(99L, List.of("Question?"));

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void broadcastClarificationQuestions_suggestionNotFound_doesNotSendNotification() throws Exception {
        when(suggestionRepository.findById(7L)).thenReturn(Optional.empty());

        invokeBroadcastClarificationQuestions(7L, List.of("Question?"));

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void broadcastClarificationQuestions_nullTitle_sendsEmptyStringForTitle() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle(null);
        suggestion.setAuthorName("bob");

        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(5L, List.of("Q?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("bob"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 5L,
                        "suggestionTitle", "",
                        "questionCount", 1
                ))
        );
    }

    @Test
    void broadcastClarificationQuestions_questionCountMatchesListSize() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Improve search");
        suggestion.setAuthorName("carol");

        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(3L, List.of("Q1?", "Q2?", "Q3?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("carol"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 3L,
                        "suggestionTitle", "Improve search",
                        "questionCount", 3
                ))
        );
    }

    private void invokeBroadcastClarificationQuestions(Long suggestionId, List<String> questions) throws Exception {
        Method method = SuggestionService.class.getDeclaredMethod(
                "broadcastClarificationQuestions", Long.class, List.class);
        method.setAccessible(true);
        method.invoke(service, suggestionId, questions);
    }
}
