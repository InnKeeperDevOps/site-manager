package com.sitemanager.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@Component
public class UserNotificationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UserNotificationWebSocketHandler.class);

    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public UserNotificationWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = extractUsername(session);
        if (username == null) {
            log.warn("Notification WebSocket connection rejected — no username provided: {}", session.getId());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.error("Error closing session without username {}: {}", session.getId(), e.getMessage());
            }
            return;
        }
        userSessions
                .computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet())
                .add(session);
        log.info("Notification WebSocket connected for user {}: {}", username, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = extractUsername(session);
        if (username == null) return;
        Set<WebSocketSession> sessions = userSessions.get(username);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                userSessions.remove(username, sessions);
            }
        }
        log.info("Notification WebSocket disconnected for user {}: {}", username, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Received message from client on notification channel: {}", message.getPayload());
    }

    public void sendNotificationToUser(String username, Map<String, Object> payload) {
        Set<WebSocketSession> sessions = userSessions.get(username);
        if (sessions == null || sessions.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            log.error("Failed to serialize notification payload for user {}: {}", username, e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) continue;
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.error("Error sending notification to session {} for user {}: {}",
                        session.getId(), username, e.getMessage());
            }
        }
    }

    public void broadcastToAll(Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            log.error("Failed to serialize broadcast payload: {}", e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        for (Set<WebSocketSession> sessions : userSessions.values()) {
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) continue;
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException e) {
                    log.error("Error broadcasting to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    public int getConnectionCount(String username) {
        Set<WebSocketSession> sessions = userSessions.get(username);
        return sessions != null ? sessions.size() : 0;
    }

    private String extractUsername(WebSocketSession session) {
        // Try principal first (if auth is configured)
        if (session.getPrincipal() != null) {
            return session.getPrincipal().getName();
        }
        // Fall back to username query parameter
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String param : uri.getQuery().split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "username".equals(kv[0]) && !kv[1].isEmpty()) {
                return kv[1];
            }
        }
        return null;
    }
}
