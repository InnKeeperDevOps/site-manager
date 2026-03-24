package com.sitemanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.ClarificationRequest;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SuggestionRepository suggestionRepository;
    private final SuggestionMessageRepository messageRepository;
    private final ClaudeService claudeService;
    private final SiteSettingsService settingsService;
    private final SuggestionWebSocketHandler webSocketHandler;

    public SuggestionService(SuggestionRepository suggestionRepository,
                             SuggestionMessageRepository messageRepository,
                             ClaudeService claudeService,
                             SiteSettingsService settingsService,
                             SuggestionWebSocketHandler webSocketHandler) {
        this.suggestionRepository = suggestionRepository;
        this.messageRepository = messageRepository;
        this.claudeService = claudeService;
        this.settingsService = settingsService;
        this.webSocketHandler = webSocketHandler;
    }

    public List<Suggestion> getAllSuggestions() {
        return suggestionRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Suggestion> getSuggestion(Long id) {
        return suggestionRepository.findById(id);
    }

    public List<SuggestionMessage> getMessages(Long suggestionId) {
        return messageRepository.findBySuggestionIdOrderByCreatedAtAsc(suggestionId);
    }

    @Transactional
    public Suggestion createSuggestion(String title, String description, Long authorId, String authorName) {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle(title);
        suggestion.setDescription(description);
        suggestion.setAuthorId(authorId);
        suggestion.setAuthorName(authorName != null ? authorName : "Anonymous");
        suggestion.setStatus(SuggestionStatus.DRAFT);
        suggestion.setClaudeSessionId(claudeService.generateSessionId());
        suggestion = suggestionRepository.save(suggestion);

        // Add the initial description as the first message
        addMessage(suggestion.getId(), SenderType.USER, suggestion.getAuthorName(),
                "**" + title + "**\n\n" + description);

        // Trigger AI evaluation asynchronously
        triggerAiEvaluation(suggestion);

        return suggestion;
    }

    public void triggerAiEvaluation(Suggestion suggestion) {
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();

        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setCurrentPhase("Cloning repository for AI session...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Clone main-repo/ for Claude to use during the evaluation session
        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                String mainRepoDir = claudeService.cloneMainRepository(repoUrl);
                log.info("Main repo cloned to {} for suggestion {}", mainRepoDir, suggestion.getId());
            } catch (Exception e) {
                log.warn("Failed to clone main-repo for evaluation session: {}", e.getMessage());
            }
        }

        suggestion.setCurrentPhase("AI is evaluating the suggestion...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        claudeService.evaluateSuggestion(
                suggestion.getTitle(),
                suggestion.getDescription(),
                repoUrl,
                suggestion.getClaudeSessionId(),
                progress -> {
                    // Send real-time progress via WebSocket
                    webSocketHandler.sendToSuggestion(suggestion.getId(),
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                }
        ).thenAccept(response -> {
            handleAiResponse(suggestion.getId(), response);
        });
    }

    @Transactional
    public void handleAiResponse(Long suggestionId, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        suggestion.setLastActivityAt(Instant.now());

        // Add AI response as a message
        addMessage(suggestionId, SenderType.AI, "Claude", response);

        // Try to parse JSON response to determine status
        if (response.contains("PLAN_READY")) {
            suggestion.setStatus(SuggestionStatus.PLANNED);
            suggestion.setCurrentPhase("Plan ready - awaiting admin approval");
            suggestion.setPlanSummary(extractPlan(response));
            suggestion.setPendingClarificationQuestions(null);
        } else if (response.contains("NEEDS_CLARIFICATION")) {
            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("Awaiting user clarification");

            // Extract questions array from the response
            List<String> questions = extractQuestions(response);
            if (questions != null && !questions.isEmpty()) {
                try {
                    suggestion.setPendingClarificationQuestions(objectMapper.writeValueAsString(questions));
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize clarification questions", e);
                }
                // Broadcast clarification questions via WebSocket
                broadcastClarificationQuestions(suggestionId, questions);
            }
        } else {
            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("In discussion with AI");
            suggestion.setPendingClarificationQuestions(null);
        }

        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
    }

    @Transactional
    public void handleClarificationAnswers(Long suggestionId, String senderName,
                                            List<ClarificationRequest.ClarificationAnswer> answers) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setLastActivityAt(Instant.now());
        suggestion.setPendingClarificationQuestions(null);
        suggestionRepository.save(suggestion);

        // Format the Q&A pairs as a structured message
        StringBuilder formattedMessage = new StringBuilder();
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            formattedMessage.append("**Q: ").append(qa.getQuestion()).append("**\n");
            formattedMessage.append("A: ").append(qa.getAnswer()).append("\n\n");
        }
        String userMessage = formattedMessage.toString().trim();

        // Store the combined clarification response as a user message
        addMessage(suggestionId, SenderType.USER, senderName, userMessage);

        // Build the prompt for Claude with the structured answers
        StringBuilder claudePrompt = new StringBuilder();
        claudePrompt.append("The user has provided the following clarification answers:\n\n");
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            claudePrompt.append("Question: ").append(qa.getQuestion()).append("\n");
            claudePrompt.append("Answer: ").append(qa.getAnswer()).append("\n\n");
        }
        claudePrompt.append("Based on these answers and the original suggestion, please evaluate again:\n");
        claudePrompt.append("1. If you still need more information, respond with NEEDS_CLARIFICATION status and a new set of questions.\n");
        claudePrompt.append("2. If you now have enough information, create the implementation plan and respond with PLAN_READY status.\n\n");
        claudePrompt.append("Respond in this JSON format:\n");
        claudePrompt.append("If clarification needed:\n");
        claudePrompt.append("{\"status\": \"NEEDS_CLARIFICATION\", ");
        claudePrompt.append("\"message\": \"brief summary of what you still need to know\", ");
        claudePrompt.append("\"questions\": [\"specific question 1\", \"specific question 2\", ...]}\n\n");
        claudePrompt.append("If ready to plan:\n");
        claudePrompt.append("{\"status\": \"PLAN_READY\", ");
        claudePrompt.append("\"message\": \"your response to the user\", ");
        claudePrompt.append("\"plan\": \"implementation plan\"}\n\n");
        claudePrompt.append("IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array with each clarifying question as a separate string element.");

        // Continue the Claude conversation
        suggestion.setCurrentPhase("AI is reviewing your clarifications...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                claudePrompt.toString(),
                context,
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                }
        ).thenAccept(response -> {
            handleAiResponse(suggestionId, response);
        });
    }

    @Transactional
    public void handleUserReply(Long suggestionId, String senderName, String message) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setLastActivityAt(Instant.now());
        suggestionRepository.save(suggestion);

        addMessage(suggestionId, SenderType.USER, senderName, message);

        // Continue the Claude conversation
        suggestion.setCurrentPhase("AI is processing your response...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                message,
                context,
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                }
        ).thenAccept(response -> {
            handleAiResponse(suggestionId, response);
        });
    }

    @Transactional
    public Suggestion approveSuggestion(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setStatus(SuggestionStatus.APPROVED);
        suggestion.setCurrentPhase("Approved - preparing to execute");
        suggestionRepository.save(suggestion);

        addMessage(suggestionId, SenderType.SYSTEM, "System", "Suggestion has been **approved** by an administrator.");
        broadcastUpdate(suggestion);

        // Begin execution
        executeApprovedSuggestion(suggestion);

        return suggestion;
    }

    private void executeApprovedSuggestion(Suggestion suggestion) {
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Cannot execute: No target repository URL configured.");
            suggestion.setCurrentPhase("Blocked - no repository configured");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return;
        }

        suggestion.setStatus(SuggestionStatus.IN_PROGRESS);
        suggestion.setCurrentPhase("Cloning repository into suggestion-" + suggestion.getId() + "-repo...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Clone repo into suggestion-{id}-repo/ and execute in background
        new Thread(() -> {
            try {
                String workDir = claudeService.cloneRepository(repoUrl, suggestion.getId().toString());
                suggestion.setWorkingDirectory(workDir);
                suggestion.setCurrentPhase("Executing implementation plan...");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);

                addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Repository cloned. Starting implementation...");

                String executionSessionId = claudeService.generateSessionId();
                claudeService.executePlan(
                        executionSessionId,
                        suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription(),
                        workDir,
                        progress -> {
                            webSocketHandler.sendToSuggestion(suggestion.getId(),
                                    "{\"type\":\"execution_progress\",\"content\":\"" +
                                            escapeJson(progress) + "\"}");
                        }
                ).thenAccept(result -> {
                    handleExecutionResult(suggestion.getId(), result);
                });

            } catch (Exception e) {
                log.error("Failed to execute suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
                addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Execution failed: " + e.getMessage());
                suggestion.setCurrentPhase("Execution failed");
                suggestion.setStatus(SuggestionStatus.PLANNED);
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);
            }
        }).start();
    }

    @Transactional
    public void handleExecutionResult(Long suggestionId, String result) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        addMessage(suggestionId, SenderType.AI, "Claude", result);

        if (result.contains("COMPLETED")) {
            suggestion.setStatus(SuggestionStatus.COMPLETED);
            suggestion.setCurrentPhase("Implementation completed");
        } else if (result.contains("FAILED")) {
            suggestion.setStatus(SuggestionStatus.PLANNED);
            suggestion.setCurrentPhase("Execution failed - can retry");
        } else {
            suggestion.setStatus(SuggestionStatus.TESTING);
            suggestion.setCurrentPhase("Testing changes...");
        }

        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
    }

    @Transactional
    public Suggestion denySuggestion(Long suggestionId, String reason) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setStatus(SuggestionStatus.DENIED);
        suggestion.setCurrentPhase("Denied");
        suggestionRepository.save(suggestion);

        String msg = "Suggestion has been **denied** by an administrator.";
        if (reason != null && !reason.isBlank()) {
            msg += "\nReason: " + reason;
        }
        addMessage(suggestionId, SenderType.SYSTEM, "System", msg);
        broadcastUpdate(suggestion);

        return suggestion;
    }

    @Scheduled(fixedRate = 60000) // Check every minute
    public void checkTimeouts() {
        int timeout = settingsService.getSettings().getSuggestionTimeoutMinutes();
        Instant cutoff = Instant.now().minus(timeout, ChronoUnit.MINUTES);

        List<Suggestion> stale = suggestionRepository.findByStatusInAndLastActivityAtBefore(
                List.of(SuggestionStatus.DRAFT, SuggestionStatus.DISCUSSING),
                cutoff
        );

        for (Suggestion s : stale) {
            s.setStatus(SuggestionStatus.TIMED_OUT);
            s.setCurrentPhase("Timed out due to inactivity");
            suggestionRepository.save(s);
            addMessage(s.getId(), SenderType.SYSTEM, "System",
                    "This suggestion has been closed due to inactivity.");
            broadcastUpdate(s);
        }
    }

    private SuggestionMessage addMessage(Long suggestionId, SenderType type, String sender, String content) {
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

    private void broadcastUpdate(Suggestion suggestion) {
        webSocketHandler.sendToSuggestion(suggestion.getId(),
                "{\"type\":\"status_update\"" +
                ",\"status\":\"" + suggestion.getStatus() + "\"" +
                ",\"currentPhase\":\"" + escapeJson(
                        suggestion.getCurrentPhase() != null ? suggestion.getCurrentPhase() : "") + "\"" +
                ",\"upVotes\":" + suggestion.getUpVotes() +
                ",\"downVotes\":" + suggestion.getDownVotes() + "}");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private List<String> extractQuestions(String response) {
        try {
            // Try to find JSON in the response
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode questionsNode = root.get("questions");
                if (questionsNode != null && questionsNode.isArray()) {
                    return objectMapper.convertValue(questionsNode,
                            new TypeReference<List<String>>() {});
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract questions from AI response: {}", e.getMessage());
        }
        return null;
    }

    private String extractJsonBlock(String response) {
        // Find the first { and last } to extract the JSON object
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    private void broadcastClarificationQuestions(Long suggestionId, List<String> questions) {
        try {
            String questionsJson = objectMapper.writeValueAsString(questions);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"clarification_questions\",\"questions\":" + questionsJson + "}");
        } catch (JsonProcessingException e) {
            log.error("Failed to broadcast clarification questions", e);
        }
    }

    public List<String> getPendingQuestions(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getPendingClarificationQuestions() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(suggestion.getPendingClarificationQuestions(),
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse pending questions", e);
            return null;
        }
    }

    private String buildConversationContext(Long suggestionId) {
        List<SuggestionMessage> messages = messageRepository.findBySuggestionIdOrderByCreatedAtAsc(suggestionId);
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        for (SuggestionMessage msg : messages) {
            switch (msg.getSenderType()) {
                case USER:
                    context.append("[User (").append(msg.getSenderName()).append(")]: ");
                    break;
                case AI:
                    context.append("[Assistant]: ");
                    break;
                case SYSTEM:
                    context.append("[System]: ");
                    break;
            }
            context.append(msg.getContent()).append("\n\n");
        }
        return context.toString().trim();
    }

    private String extractPlan(String response) {
        // Try to extract plan from JSON response
        int planIdx = response.indexOf("\"plan\"");
        if (planIdx >= 0) {
            int start = response.indexOf("\"", planIdx + 6);
            if (start >= 0) {
                start++;
                int end = findClosingQuote(response, start);
                if (end > start) {
                    return response.substring(start, end).replace("\\n", "\n");
                }
            }
        }
        return response;
    }

    private int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return s.length();
    }
}
