package com.sitemanager.config;

import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SuggestionWebSocketHandler handler;
    private final UserNotificationWebSocketHandler userNotificationWebSocketHandler;

    public WebSocketConfig(SuggestionWebSocketHandler handler,
                           UserNotificationWebSocketHandler userNotificationWebSocketHandler) {
        this.handler = handler;
        this.userNotificationWebSocketHandler = userNotificationWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/suggestions/{suggestionId}")
                .setAllowedOrigins("*");
        registry.addHandler(userNotificationWebSocketHandler, "/ws/notifications")
                .setAllowedOrigins("*");
    }
}
