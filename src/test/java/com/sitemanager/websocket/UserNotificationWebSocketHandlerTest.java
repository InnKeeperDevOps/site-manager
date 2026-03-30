package com.sitemanager.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.security.Principal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserNotificationWebSocketHandlerTest {

    private UserNotificationWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new UserNotificationWebSocketHandler(new ObjectMapper());
    }

    @Test
    void afterConnectionEstablished_registersSession() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");

        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.getConnectionCount("alice"));
    }

    @Test
    void afterConnectionEstablished_noPrincipalNoUsername_closesSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(null);
        when(session.getUri()).thenReturn(new URI("/ws/notifications"));
        when(session.getId()).thenReturn("session1");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertEquals(0, handler.getConnectionCount(""));
    }

    @Test
    void afterConnectionEstablished_usernameQueryParam_registersSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(null);
        when(session.getUri()).thenReturn(new URI("/ws/notifications?username=bob"));
        when(session.getId()).thenReturn("session1");

        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.getConnectionCount("bob"));
    }

    @Test
    void afterConnectionClosed_removesSession() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(0, handler.getConnectionCount("alice"));
    }

    @Test
    void afterConnectionClosed_emptySetRemovedFromMap() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // A second send should do nothing (no NPE)
        handler.sendNotificationToUser("alice", Map.of("type", "test"));
    }

    @Test
    void sendNotificationToUser_sendsToOpenSessions() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.sendNotificationToUser("alice", Map.of("type", "clarification_needed"));

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendNotificationToUser_skipsClosedSessions() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");
        when(session.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(session);
        handler.sendNotificationToUser("alice", Map.of("type", "clarification_needed"));

        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendNotificationToUser_noSessionsForUser_doesNothing() {
        // Should not throw
        handler.sendNotificationToUser("nobody", Map.of("type", "test"));
    }

    @Test
    void sendNotificationToUser_payloadSerializedAsJson() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.sendNotificationToUser("alice", Map.of("type", "clarification_needed", "suggestionId", 42));

        verify(session).sendMessage(argThat(msg -> {
            String payload = ((TextMessage) msg).getPayload();
            return payload.contains("\"type\"") && payload.contains("clarification_needed")
                    && payload.contains("42");
        }));
    }

    @Test
    void multipleSessions_sameUser_allReceiveMessages() throws Exception {
        WebSocketSession s1 = mockSession("alice", "session1");
        WebSocketSession s2 = mockSession("alice", "session2");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        assertEquals(2, handler.getConnectionCount("alice"));

        handler.sendNotificationToUser("alice", Map.of("type", "test"));

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s2).sendMessage(any(TextMessage.class));
    }

    @Test
    void differentUsers_isolatedSessions() throws Exception {
        WebSocketSession sAlice = mockSession("alice", "session1");
        WebSocketSession sBob = mockSession("bob", "session2");
        when(sAlice.isOpen()).thenReturn(true);
        when(sBob.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(sAlice);
        handler.afterConnectionEstablished(sBob);

        handler.sendNotificationToUser("alice", Map.of("type", "test"));

        verify(sAlice).sendMessage(any(TextMessage.class));
        verify(sBob, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendNotificationToUser_ioExceptionOnOneSession_continuesOthers() throws Exception {
        WebSocketSession s1 = mockSession("alice", "session1");
        WebSocketSession s2 = mockSession("alice", "session2");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);
        doThrow(new java.io.IOException("send failed")).when(s1).sendMessage(any());

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        // Should not throw; s2 should still receive message
        assertDoesNotThrow(() ->
                handler.sendNotificationToUser("alice", Map.of("type", "test")));
        verify(s2).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastToAll_sendsToAllConnectedUsers() throws Exception {
        WebSocketSession sAlice = mockSession("alice", "session1");
        WebSocketSession sBob = mockSession("bob", "session2");
        when(sAlice.isOpen()).thenReturn(true);
        when(sBob.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(sAlice);
        handler.afterConnectionEstablished(sBob);

        handler.broadcastToAll(Map.of("type", "PROJECT_DEFINITION_UPDATE", "data", Map.of("status", "ACTIVE")));

        verify(sAlice).sendMessage(any(TextMessage.class));
        verify(sBob).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastToAll_skipsClosedSessions() throws Exception {
        WebSocketSession openSession = mockSession("alice", "session1");
        WebSocketSession closedSession = mockSession("alice", "session2");
        when(openSession.isOpen()).thenReturn(true);
        when(closedSession.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(openSession);
        handler.afterConnectionEstablished(closedSession);

        handler.broadcastToAll(Map.of("type", "PROJECT_DEFINITION_UPDATE"));

        verify(openSession).sendMessage(any(TextMessage.class));
        verify(closedSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastToAll_noSessions_doesNothing() {
        assertDoesNotThrow(() ->
                handler.broadcastToAll(Map.of("type", "PROJECT_DEFINITION_UPDATE")));
    }

    @Test
    void broadcastToAll_payloadContainsProjectDefinitionUpdateType() throws Exception {
        WebSocketSession session = mockSession("alice", "session1");
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.broadcastToAll(Map.of("type", "PROJECT_DEFINITION_UPDATE", "data",
                Map.of("sessionId", 1, "status", "GENERATING")));

        verify(session).sendMessage(argThat(msg -> {
            String payload = ((TextMessage) msg).getPayload();
            return payload.contains("PROJECT_DEFINITION_UPDATE")
                    && payload.contains("GENERATING");
        }));
    }

    @Test
    void broadcastToAll_ioExceptionOnOneSession_continuesOthers() throws Exception {
        WebSocketSession s1 = mockSession("alice", "session1");
        WebSocketSession s2 = mockSession("bob", "session2");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);
        doThrow(new java.io.IOException("send failed")).when(s1).sendMessage(any());

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        assertDoesNotThrow(() ->
                handler.broadcastToAll(Map.of("type", "PROJECT_DEFINITION_UPDATE")));
        verify(s2).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession mockSession(String username, String sessionId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(username);
        when(session.getPrincipal()).thenReturn(principal);
        when(session.getId()).thenReturn(sessionId);
        return session;
    }
}
