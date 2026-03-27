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
        if (session.getPrincipal() == null) {
            log.warn("Notification WebSocket connection rejected — no authenticated principal: {}", session.getId());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.error("Error closing unauthenticated session {}: {}", session.getId(), e.getMessage());
            }
            return;
        }
        String username = session.getPrincipal().getName();
        userSessions
                .computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet())
                .add(session);
        log.info("Notification WebSocket connected for user {}: {}", username, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() == null) return;
        String username = session.getPrincipal().getName();
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

    public int getConnectionCount(String username) {
        Set<WebSocketSession> sessions = userSessions.get(username);
        return sessions != null ? sessions.size() : 0;
    }
}
