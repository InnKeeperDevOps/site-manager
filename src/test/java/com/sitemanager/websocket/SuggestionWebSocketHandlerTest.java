package com.sitemanager.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SuggestionWebSocketHandlerTest {

    private SuggestionWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SuggestionWebSocketHandler();
    }

    @Test
    void afterConnectionEstablished_registersSession() throws Exception {
        WebSocketSession session = mockSession(1L, "session1");

        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.getConnectionCount(1L));
    }

    @Test
    void afterConnectionClosed_removesSession() throws Exception {
        WebSocketSession session = mockSession(1L, "session1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(0, handler.getConnectionCount(1L));
    }

    @Test
    void sendToSuggestion_sendsToConnectedSessions() throws Exception {
        WebSocketSession session = mockSession(1L, "session1");
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.sendToSuggestion(1L, "{\"type\":\"test\"}");

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendToSuggestion_skipsClosedSessions() throws Exception {
        WebSocketSession session = mockSession(1L, "session1");
        when(session.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(session);
        handler.sendToSuggestion(1L, "{\"type\":\"test\"}");

        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendToSuggestion_noSessionsForId_doesNothing() {
        // Should not throw
        handler.sendToSuggestion(999L, "{\"type\":\"test\"}");
    }

    @Test
    void multipleSessions_sameSuggestion_allReceiveMessages() throws Exception {
        WebSocketSession s1 = mockSession(1L, "session1");
        WebSocketSession s2 = mockSession(1L, "session2");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        assertEquals(2, handler.getConnectionCount(1L));

        handler.sendToSuggestion(1L, "{\"type\":\"test\"}");

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s2).sendMessage(any(TextMessage.class));
    }

    @Test
    void differentSuggestions_isolatedSessions() throws Exception {
        WebSocketSession s1 = mockSession(1L, "session1");
        WebSocketSession s2 = mockSession(2L, "session2");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        handler.sendToSuggestion(1L, "{\"type\":\"test\"}");

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s2, never()).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession mockSession(Long suggestionId, String sessionId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("/ws/suggestions/" + suggestionId));
        when(session.getId()).thenReturn(sessionId);
        return session;
    }
}
