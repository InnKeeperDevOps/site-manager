package com.sitemanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper for broadcasting WebSocket messages and user notifications
 * related to suggestions. Extracted from SuggestionService so that
 * ExpertReviewService and PlanExecutionService can share these methods
 * without creating circular dependencies.
 */
@Service
public class SuggestionMessagingHelper {

    private static final Logger log = LoggerFactory.getLogger(SuggestionMessagingHelper.class);
    static final int MAX_EXPERT_REVIEW_ROUNDS = 2;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SuggestionRepository suggestionRepository;
    private final SuggestionMessageRepository messageRepository;
    private final PlanTaskRepository planTaskRepository;
    private final SuggestionWebSocketHandler webSocketHandler;
    private final UserNotificationWebSocketHandler userNotificationHandler;
    private final SlackNotificationService slackNotificationService;
    private final SiteSettingsService settingsService;

    public SuggestionMessagingHelper(SuggestionRepository suggestionRepository,
                                     SuggestionMessageRepository messageRepository,
                                     PlanTaskRepository planTaskRepository,
                                     SuggestionWebSocketHandler webSocketHandler,
                                     UserNotificationWebSocketHandler userNotificationHandler,
                                     SlackNotificationService slackNotificationService,
                                     SiteSettingsService settingsService) {
        this.suggestionRepository = suggestionRepository;
        this.messageRepository = messageRepository;
        this.planTaskRepository = planTaskRepository;
        this.webSocketHandler = webSocketHandler;
        this.userNotificationHandler = userNotificationHandler;
        this.slackNotificationService = slackNotificationService;
        this.settingsService = settingsService;
    }

    /**
     * Persists a message for a suggestion and broadcasts it via WebSocket.
     */
    SuggestionMessage addMessage(Long suggestionId, SenderType type, String sender, String content) {
        SuggestionMessage msg = new SuggestionMessage(suggestionId, type, sender, content);
        msg = messageRepository.save(msg);

        // Broadcast new message via WebSocket
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"message\",\"id\":" + msg.getId() +
                ",\"senderType\":\"" + type + "\"" +
                ",\"senderName\":\"" + escapeJson(sender) + "\"" +
                ",\"content\":\"" + escapeJson(content) + "\"" +
                ",\"createdAt\":\"" + msg.getCreatedAt() + "\"}");

        return msg;
    }

    /**
     * Broadcasts the current status of a suggestion to all connected clients.
     */
    void broadcastUpdate(Suggestion suggestion) {
        webSocketHandler.sendToSuggestion(suggestion.getId(),
                "{\"type\":\"status_update\"" +
                ",\"status\":\"" + suggestion.getStatus() + "\"" +
                ",\"currentPhase\":\"" + escapeJson(
                        suggestion.getCurrentPhase() != null ? suggestion.getCurrentPhase() : "") + "\"" +
                ",\"upVotes\":" + suggestion.getUpVotes() +
                ",\"downVotes\":" + suggestion.getDownVotes() +
                ",\"planDisplaySummary\":\"" + escapeJson(
                        suggestion.getPlanDisplaySummary() != null ? suggestion.getPlanDisplaySummary() : "") + "\"" +
                ",\"planSummary\":\"" + escapeJson(
                        suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : "") + "\"}");
    }

    /**
     * Broadcasts clarification questions to the suggestion channel and sends
     * a notification to the suggestion author.
     */
    void broadcastClarificationQuestions(Long suggestionId, List<String> questions) {
        try {
            String questionsJson = objectMapper.writeValueAsString(questions);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"clarification_questions\",\"questions\":" + questionsJson + "}");
        } catch (JsonProcessingException e) {
            log.error("Failed to broadcast clarification questions", e);
        }

        suggestionRepository.findById(suggestionId).ifPresent(suggestion -> {
            String authorName = suggestion.getAuthorName();
            if (authorName != null) {
                userNotificationHandler.sendNotificationToUser(authorName, Map.of(
                        "type", "clarification_needed",
                        "suggestionId", suggestionId,
                        "suggestionTitle", suggestion.getTitle() != null ? suggestion.getTitle() : "",
                        "questionCount", questions.size()
                ));
            }
        });
    }

    /**
     * Broadcasts an updated task to all clients watching the suggestion.
     */
    void broadcastTaskUpdate(Long suggestionId, PlanTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"task_update\",\"task\":" + taskJson + "}");
        } catch (Exception e) {
            log.warn("Failed to broadcast task update: {}", e.getMessage());
        }
    }

    /**
     * Broadcasts a short activity detail for a specific task.
     */
    void broadcastTaskActivity(Long suggestionId, int taskOrder, String detail) {
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"task_activity\",\"taskOrder\":" + taskOrder +
                        ",\"detail\":\"" + escapeJson(detail) + "\"}");
    }

    /**
     * Broadcasts the full task list for a suggestion to all connected clients.
     */
    void broadcastTasks(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        try {
            String tasksJson = objectMapper.writeValueAsString(tasks);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"tasks_update\",\"tasks\":" + tasksJson + "}");
        } catch (Exception e) {
            log.warn("Failed to broadcast tasks: {}", e.getMessage());
        }
    }

    /**
     * Broadcasts a note left by an expert reviewer.
     */
    void broadcastExpertNote(Long suggestionId, String expertName, String note) {
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"expert_note\"" +
                ",\"expertName\":\"" + escapeJson(expertName) + "\"" +
                ",\"note\":\"" + escapeJson(note) + "\"}");
    }

    /**
     * Computes and broadcasts the current expert review progress for a suggestion.
     */
    void broadcastExpertReviewStatus(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getExpertReviewStep() == null) return;

        int currentStep = suggestion.getExpertReviewStep();
        ExpertRole[] experts = ExpertRole.reviewOrder();
        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;

        List<Map<String, String>> expertList = new ArrayList<>();
        for (int i = 0; i < experts.length; i++) {
            String stepStatus;
            if (i < currentStep) stepStatus = "completed";
            else if (i == currentStep) stepStatus = "in_progress";
            else stepStatus = "pending";
            expertList.add(Map.of("name", experts[i].getDisplayName(), "status", stepStatus));
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("currentStep", currentStep);
        status.put("totalSteps", experts.length);
        status.put("round", currentRound);
        status.put("maxRounds", MAX_EXPERT_REVIEW_ROUNDS);
        status.put("experts", expertList);
        status.put("ownerLockedSections", suggestion.getOwnerLockedSections());

        try {
            String json = objectMapper.writeValueAsString(status);
            // Add the type field for WebSocket message routing
            String wsJson = "{\"type\":\"expert_review_status\"," + json.substring(1);
            webSocketHandler.sendToSuggestion(suggestionId, wsJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize expert review status", e);
        }
    }

    /**
     * Computes and broadcasts the execution queue status to all connected users.
     */
    void broadcastExecutionQueueStatus() {
        Integer max = settingsService.getSettings().getMaxConcurrentSuggestions();
        int maxConcurrent = (max != null && max >= 1) ? max : 1;

        long activeCount = suggestionRepository.countByStatusIn(
                List.of(SuggestionStatus.IN_PROGRESS, SuggestionStatus.TESTING));

        List<Suggestion> queued = suggestionRepository.findByStatus(SuggestionStatus.APPROVED);
        queued.sort(java.util.Comparator.comparing(Suggestion::getCreatedAt));

        List<Map<String, Object>> queuedList = new ArrayList<>();
        for (int i = 0; i < queued.size(); i++) {
            Suggestion s = queued.get(i);
            queuedList.add(Map.of(
                    "id", s.getId(),
                    "title", s.getTitle(),
                    "position", i + 1
            ));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "execution_queue_status");
        payload.put("maxConcurrent", maxConcurrent);
        payload.put("activeCount", activeCount);
        payload.put("queuedCount", queued.size());
        payload.put("queued", queuedList);

        userNotificationHandler.broadcastToAll(payload);
    }

    /**
     * Broadcasts a real-time progress token to all clients watching the suggestion.
     * Used for streaming Claude responses so users see live output.
     */
    void broadcastProgress(Long suggestionId, String content) {
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"progress\",\"content\":\"" + escapeJson(content) + "\"}");
    }

    /**
     * Escapes special characters in a string for safe inclusion in a JSON value.
     */
    String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
