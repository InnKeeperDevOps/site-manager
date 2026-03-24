package com.sitemanager.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SuggestionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SuggestionWebSocketHandler.class);

    // Map of suggestionId -> set of connected sessions
    private final Map<Long, Set<WebSocketSession>> suggestionSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long suggestionId = extractSuggestionId(session);
        if (suggestionId != null) {
            suggestionSessions
                    .computeIfAbsent(suggestionId, k -> new CopyOnWriteArraySet<>())
                    .add(session);
            log.info("WebSocket connected for suggestion {}: {}", suggestionId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long suggestionId = extractSuggestionId(session);
        if (suggestionId != null) {
            Set<WebSocketSession> sessions = suggestionSessions.get(suggestionId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    suggestionSessions.remove(suggestionId);
                }
            }
            log.info("WebSocket disconnected for suggestion {}: {}", suggestionId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Client messages are handled through REST API, not WebSocket
        // WebSocket is primarily for server-to-client push
        log.debug("Received message from client: {}", message.getPayload());
    }

    public void sendToSuggestion(Long suggestionId, String message) {
        Set<WebSocketSession> sessions = suggestionSessions.get(suggestionId);
        if (sessions == null || sessions.isEmpty()) return;

        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                }
            } catch (IOException e) {
                log.error("Error sending WebSocket message to session {}: {}",
                        session.getId(), e.getMessage());
            }
        }
    }

    public int getConnectionCount(Long suggestionId) {
        Set<WebSocketSession> sessions = suggestionSessions.get(suggestionId);
        return sessions != null ? sessions.size() : 0;
    }

    private Long extractSuggestionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        // Path format: /ws/suggestions/{suggestionId}
        String[] parts = path.split("/");
        if (parts.length >= 4) {
            try {
                return Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                log.warn("Invalid suggestion ID in WebSocket path: {}", path);
            }
        }
        return null;
    }
}
