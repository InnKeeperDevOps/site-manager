package com.sitemanager.config;

import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebSocketConfigTest {

    @Test
    void registerWebSocketHandlers_registersBothEndpoints() {
        SuggestionWebSocketHandler suggestionHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler notificationHandler = mock(UserNotificationWebSocketHandler.class);
        WebSocketConfig config = new WebSocketConfig(suggestionHandler, notificationHandler);

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration registration =
                mock(org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(), any(String.class))).thenReturn(registration);
        when(registration.setAllowedOrigins(any(String.class))).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        verify(registry).addHandler(eq(suggestionHandler), eq("/ws/suggestions/{suggestionId}"));
        verify(registry).addHandler(eq(notificationHandler), eq("/ws/notifications"));
        verify(registration, times(2)).setAllowedOrigins("*");
    }

    @Test
    void registerWebSocketHandlers_notificationsEndpoint_setsAllowedOrigins() {
        SuggestionWebSocketHandler suggestionHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler notificationHandler = mock(UserNotificationWebSocketHandler.class);
        WebSocketConfig config = new WebSocketConfig(suggestionHandler, notificationHandler);

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration registration =
                mock(org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(), any(String.class))).thenReturn(registration);
        when(registration.setAllowedOrigins(any(String.class))).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        // Verify allowed origins wildcard is set for both registrations
        verify(registration, times(2)).setAllowedOrigins("*");
    }
}
