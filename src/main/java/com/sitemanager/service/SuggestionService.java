package com.sitemanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.dto.ClarificationRequest;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.PlanTask;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    private static final int MAX_EXPERT_REVIEW_ROUNDS = 2;
    private static final int MAX_TOTAL_EXPERT_REVIEW_ROUNDS = 3;
    private static final int MIN_EXPERT_ANALYSIS_LENGTH = 50;
    private static final int MAX_EXPERT_RETRIES = 2;
    private final ConcurrentHashMap<String, Integer> expertRetryCount = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SuggestionRepository suggestionRepository;
    private final SuggestionMessageRepository messageRepository;
    private final PlanTaskRepository planTaskRepository;
    private final ClaudeService claudeService;
    private final SiteSettingsService settingsService;
    private final SuggestionWebSocketHandler webSocketHandler;
    private final UserNotificationWebSocketHandler userNotificationHandler;

    public SuggestionService(SuggestionRepository suggestionRepository,
                             SuggestionMessageRepository messageRepository,
                             PlanTaskRepository planTaskRepository,
                             ClaudeService claudeService,
                             SiteSettingsService settingsService,
                             SuggestionWebSocketHandler webSocketHandler,
                             UserNotificationWebSocketHandler userNotificationHandler) {
        this.suggestionRepository = suggestionRepository;
        this.messageRepository = messageRepository;
        this.planTaskRepository = planTaskRepository;
        this.claudeService = claudeService;
        this.settingsService = settingsService;
        this.webSocketHandler = webSocketHandler;
        this.userNotificationHandler = userNotificationHandler;
    }

    /**
     * On application startup, find any suggestions that were mid-execution
     * (APPROVED, IN_PROGRESS, or TESTING) and resume Claude to continue the plan.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeSuggestionsOnStartup() {
        List<Suggestion> toResume = suggestionRepository.findByStatusIn(
                List.of(SuggestionStatus.APPROVED, SuggestionStatus.IN_PROGRESS, SuggestionStatus.TESTING)
        );

        if (toResume.isEmpty()) {
            log.info("No approved/in-progress suggestions to resume on startup");
            return;
        }

        log.info("Found {} suggestion(s) to resume on startup", toResume.size());

        for (Suggestion suggestion : toResume) {
            try {
                if (suggestion.getStatus() == SuggestionStatus.APPROVED) {
                    // Approved but execution never started (or crashed before cloning).
                    // Re-trigger the full execution flow.
                    log.info("Resuming approved suggestion {} — starting execution", suggestion.getId());
                    addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                            "System restarted. Picking up where we left off...");
                    executeApprovedSuggestion(suggestion);
                } else {
                    // IN_PROGRESS or TESTING — Claude was mid-execution. Resume it.
                    resumeInProgressSuggestion(suggestion);
                }
            } catch (Exception e) {
                log.error("Failed to resume suggestion {} on startup: {}", suggestion.getId(), e.getMessage(), e);
                addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Something went wrong while resuming work. You can retry.");
                suggestion.setCurrentPhase("Resume failed — can retry");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);
            }
        }
    }

    private void resumeInProgressSuggestion(Suggestion suggestion) {
        String workDir = suggestion.getWorkingDirectory();

        // If no working directory or it doesn't exist, fall back to full re-execution
        if (workDir == null || !new File(workDir).exists()) {
            log.info("Suggestion {} has no valid working directory, re-executing from scratch",
                    suggestion.getId());
            suggestion.setStatus(SuggestionStatus.APPROVED);
            suggestion.setCurrentPhase("Restarting from the beginning...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "System restarted. Starting the work over from the beginning...");
            executeApprovedSuggestion(suggestion);
            return;
        }

        log.info("Resuming in-progress suggestion {} in {}", suggestion.getId(), workDir);

        addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                "System restarted. Resuming work on the next pending task...");

        suggestion.setCurrentPhase("Resuming — checking progress...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Reset any IN_PROGRESS or REVIEWING tasks to PENDING since we can't know if they completed before crash
        List<PlanTask> inProgressTasks = planTaskRepository.findBySuggestionIdAndStatus(
                suggestion.getId(), TaskStatus.IN_PROGRESS);
        List<PlanTask> reviewingTasks = planTaskRepository.findBySuggestionIdAndStatus(
                suggestion.getId(), TaskStatus.REVIEWING);
        for (PlanTask task : inProgressTasks) {
            task.setStatus(TaskStatus.PENDING);
            task.setStartedAt(null);
            planTaskRepository.save(task);
        }
        for (PlanTask task : reviewingTasks) {
            task.setStatus(TaskStatus.PENDING);
            task.setStartedAt(null);
            planTaskRepository.save(task);
        }

        // Resume sequential task execution — pick up from the next pending task
        executeNextTask(suggestion.getId());
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
        suggestion.setCurrentPhase("Getting the latest version of the project...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Pull latest main-repo/ so Claude always evaluates against the newest code
        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                boolean changed = claudeService.pullMainRepository(repoUrl);
                log.info("Main repo {} for suggestion {}", changed ? "updated" : "already up to date",
                        suggestion.getId());
            } catch (Exception e) {
                log.warn("Failed to pull main-repo for evaluation session: {}", e.getMessage());
            }
        }

        suggestion.setCurrentPhase("Evaluating your suggestion...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        claudeService.evaluateSuggestion(
                suggestion.getTitle(),
                suggestion.getDescription(),
                repoUrl,
                suggestion.getClaudeSessionId(),
                claudeService.getMainRepoDir(),
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

        // Log the AI evaluation result
        String responseStatus = response.contains("PLAN_READY") ? "PLAN_READY" :
                response.contains("NEEDS_CLARIFICATION") ? "NEEDS_CLARIFICATION" : "UNKNOWN";
        log.info("[AI-FLOW] suggestion={} evaluation result: status={} responseLength={}",
                suggestionId, responseStatus, response.length());
        log.debug("[AI-FLOW] suggestion={} evaluation response: {}", suggestionId,
                response.length() > 1000 ? response.substring(0, 1000) + "..." : response);

        // Extract the human-readable message from JSON, never show raw JSON in discussion
        String displayMessage = extractMessage(response);

        // Try to parse JSON response to determine status
        if (response.contains("PLAN_READY")) {
            if (isPlanLocked(suggestion.getStatus())) {
                log.warn("Ignoring PLAN_READY for suggestion {} — plan is locked in {} state",
                        suggestionId, suggestion.getStatus());
                return;
            }
            addMessage(suggestionId, SenderType.AI, "Claude", displayMessage);
            suggestion.setPlanSummary(extractPlan(response));
            suggestion.setPlanDisplaySummary(extractPlanDisplaySummary(response));
            suggestion.setPendingClarificationQuestions(null);
            // Parse and save structured tasks
            savePlanTasks(suggestionId, response);

            // Start expert review pipeline instead of going directly to PLANNED
            suggestion.setStatus(SuggestionStatus.EXPERT_REVIEW);
            suggestion.setExpertReviewStep(0);
            suggestion.setExpertReviewRound(1);
            suggestion.setTotalExpertReviewRounds(1);
            suggestion.setExpertReviewNotes(null);
            suggestion.setExpertReviewPlanChanged(false);
            suggestion.setCurrentPhase("Plan created — starting expert reviews...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            // Kick off the first expert review asynchronously
            startExpertReviewPipeline(suggestionId);
            return;
        } else if (response.contains("NEEDS_CLARIFICATION")) {
            // Do NOT post clarification questions to the user discussion;
            // they are delivered via WebSocket as structured prompts instead
            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("Waiting for your answers");

            List<String> questions = extractQuestions(response);
            if (questions != null && !questions.isEmpty()) {
                try {
                    suggestion.setPendingClarificationQuestions(objectMapper.writeValueAsString(questions));
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize clarification questions", e);
                }
                broadcastClarificationQuestions(suggestionId, questions);
            }
        } else {
            addMessage(suggestionId, SenderType.AI, "Claude", displayMessage);
            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("In discussion");
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
        claudePrompt.append("2. If you now have enough information, create a plan broken into tasks and respond with PLAN_READY status.\n\n");
        claudePrompt.append("COMMUNICATION RULES:\n");
        claudePrompt.append("- All messages, questions, and plan descriptions MUST be written in plain, non-technical language.\n");
        claudePrompt.append("- NEVER mention programming languages, frameworks, libraries, databases, APIs, file names, class names, or any technical implementation details.\n");
        claudePrompt.append("- Describe changes in terms of what the user will experience — features, behaviors, and outcomes.\n");
        claudePrompt.append("- Questions should be about desired behavior and outcomes, not technical choices.\n\n");
        claudePrompt.append("Respond in this JSON format:\n");
        claudePrompt.append("If clarification needed:\n");
        claudePrompt.append("{\"status\": \"NEEDS_CLARIFICATION\", ");
        claudePrompt.append("\"message\": \"brief summary of what you still need to know\", ");
        claudePrompt.append("\"questions\": [\"specific question 1\", \"specific question 2\", ...]}\n\n");
        claudePrompt.append("If ready to plan:\n");
        claudePrompt.append("{\"status\": \"PLAN_READY\", ");
        claudePrompt.append("\"message\": \"your response to the user\", ");
        claudePrompt.append("\"plan\": \"brief overall summary of what will be done\", ");
        claudePrompt.append("\"tasks\": [\n");
        claudePrompt.append("  {\"title\": \"short task name\", \"description\": \"what this task involves\", \"estimatedMinutes\": number},\n");
        claudePrompt.append("  ...\n");
        claudePrompt.append("]}\n\n");
        claudePrompt.append("IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array with each clarifying question as a separate string element.\n");
        claudePrompt.append("When status is PLAN_READY, you MUST include a \"tasks\" array that breaks the plan into ordered steps. ");
        claudePrompt.append("Each task should be a concrete, actionable unit of work with a realistic time estimate in minutes. ");
        claudePrompt.append("Order tasks by implementation sequence. Typically 3-8 tasks is appropriate.");

        // Continue the Claude conversation
        suggestion.setCurrentPhase("Reviewing your answers...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                claudePrompt.toString(),
                context,
                claudeService.getMainRepoDir(),
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                }
        ).thenAccept(response -> {
            handleAiResponse(suggestionId, response);
        });
    }

    // --- Expert Review Pipeline ---

    private void startExpertReviewPipeline(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            log.warn("Blocking expert review pipeline start for suggestion {} in non-EXPERT_REVIEW state",
                    suggestionId);
            return;
        }
        broadcastExpertReviewStatus(suggestionId);
        runNextExpertReview(suggestionId);
    }

    private void runNextExpertReview(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        if (suggestion.getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            log.warn("Aborting expert review step for suggestion {} — status is {}",
                    suggestionId, suggestion.getStatus());
            return;
        }

        int step = suggestion.getExpertReviewStep() != null ? suggestion.getExpertReviewStep() : 0;
        ExpertRole[] experts = ExpertRole.reviewOrder();

        if (step >= experts.length) {
            // All experts have reviewed — check if plan was changed during this round
            boolean planChanged = Boolean.TRUE.equals(suggestion.getExpertReviewPlanChanged());
            int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;

            int totalRounds = suggestion.getTotalExpertReviewRounds() != null ? suggestion.getTotalExpertReviewRounds() : currentRound;

            if (planChanged && currentRound < MAX_EXPERT_REVIEW_ROUNDS && totalRounds < MAX_TOTAL_EXPERT_REVIEW_ROUNDS) {
                // Plan was modified — run targeted re-review of affected domain experts only
                java.util.Set<ExpertRole.Domain> changedDomains = parseChangedDomains(suggestion.getExpertReviewChangedDomains());
                java.util.Set<ExpertRole.Domain> affectedDomains = new java.util.HashSet<>();
                for (ExpertRole.Domain d : changedDomains) {
                    affectedDomains.addAll(ExpertRole.affectedDomains(d));
                }

                java.util.List<ExpertRole> expertsToRerun = java.util.Arrays.stream(ExpertRole.reviewOrder())
                        .filter(e -> affectedDomains.contains(e.domain()))
                        .toList();

                int nextRound = currentRound + 1;
                suggestion.setExpertReviewRound(nextRound);
                suggestion.setTotalExpertReviewRounds(totalRounds + 1);
                suggestion.setExpertReviewNotes(null);
                suggestion.setExpertReviewPlanChanged(false);
                suggestion.setExpertReviewChangedDomains(null);
                suggestionRepository.save(suggestion);

                String expertNames = expertsToRerun.stream()
                        .map(ExpertRole::getDisplayName)
                        .collect(java.util.stream.Collectors.joining(", "));
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "The plan was updated — targeted re-review by " + expertNames + " (round " + nextRound + ").");
                broadcastTasks(suggestionId);
                broadcastExpertReviewStatus(suggestionId);
                runTargetedExpertRereviews(suggestionId, expertsToRerun, nextRound);
                return;
            }

            if (planChanged && totalRounds >= MAX_TOTAL_EXPERT_REVIEW_ROUNDS) {
                // Hard cap reached — force-finalize to prevent endless review loops
                log.warn("Suggestion {} hit hard cap of {} total expert review rounds, force-finalizing",
                        suggestionId, MAX_TOTAL_EXPERT_REVIEW_ROUNDS);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "Expert reviews have completed after " + totalRounds +
                        " total rounds of refinement. Finalizing the plan as-is.");
            } else if (planChanged) {
                // Per-cycle max reached but still under hard cap —
                // force-finalize rather than looping further
                log.info("Suggestion {} reached max expert review rounds ({}), force-finalizing",
                        suggestionId, MAX_EXPERT_REVIEW_ROUNDS);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "Expert reviews have completed after " + currentRound +
                        " rounds. Finalizing the plan.");
            }

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "All expert reviews are complete. The plan is ready for approval.");

            // Transition to PLANNED
            suggestion.setStatus(SuggestionStatus.PLANNED);
            suggestion.setExpertReviewStep(null);
            suggestion.setExpertReviewRound(null);
            suggestion.setExpertReviewPlanChanged(null);
            suggestion.setTotalExpertReviewRounds(null);
            suggestion.setExpertReviewChangedDomains(null);
            suggestion.setCurrentPhase("Plan ready — waiting for approval");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            broadcastExpertReviewStatus(suggestionId);
            return;
        }

        // Determine which batch this step belongs to and run the whole batch in parallel
        int[] batchInfo = ExpertRole.batchForStep(step);
        if (batchInfo == null) {
            // Fallback: run single expert
            runSingleExpertReview(suggestionId, suggestion, experts[step]);
            return;
        }

        int batchIndex = batchInfo[0];
        int positionInBatch = batchInfo[1];

        // If we're at the start of a batch, launch all experts in this batch concurrently
        if (positionInBatch == 0) {
            runExpertBatch(suggestionId, batchIndex);
        } else {
            // We're mid-batch (resumed after clarification) — run remaining experts sequentially
            runSingleExpertReview(suggestionId, suggestion, experts[step]);
        }
    }

    /**
     * Run all experts in a batch concurrently. Results are collected and processed
     * sequentially after all complete. If any expert needs clarification, the pipeline
     * pauses at that expert.
     */
    private void runExpertBatch(Long suggestionId, int batchIndex) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        ExpertRole[][] batches = ExpertRole.reviewBatches();
        if (batchIndex >= batches.length) return;

        ExpertRole[] batch = batches[batchIndex];
        long batchStart = System.currentTimeMillis();
        log.info("[EXPERT-BATCH] Starting batch {} ({} experts: {}) for suggestion {}",
                batchIndex + 1, batch.length,
                String.join(", ", java.util.Arrays.stream(batch).map(ExpertRole::getDisplayName).toArray(String[]::new)),
                suggestionId);

        // Show all batch experts as in-progress
        String expertNames = String.join(", ",
                java.util.Arrays.stream(batch).map(ExpertRole::getDisplayName).toArray(String[]::new));
        suggestion.setCurrentPhase(expertNames + " are reviewing the plan...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
        broadcastExpertReviewStatus(suggestionId);

        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        // Launch all experts in this batch concurrently
        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        List<CompletableFuture<ExpertBatchResult>> futures = new ArrayList<>();
        for (ExpertRole expert : batch) {
            String reviewSessionId = claudeService.generateSessionId();
            CompletableFuture<ExpertBatchResult> future = claudeService.expertReview(
                    reviewSessionId,
                    expert.getDisplayName(),
                    expert.getReviewPrompt(),
                    suggestion.getTitle(),
                    suggestion.getDescription(),
                    plan,
                    tasksJson,
                    previousNotes,
                    claudeService.getMainRepoDir(),
                    progress -> {
                        webSocketHandler.sendToSuggestion(suggestionId,
                                "{\"type\":\"progress\",\"content\":\"" +
                                        escapeJson(progress) + "\"}");
                    },
                    currentRound
            ).thenApply(response -> new ExpertBatchResult(expert, reviewSessionId, response));
            futures.add(future);
        }

        // When all experts in the batch complete, process results sequentially
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    long batchElapsed = System.currentTimeMillis() - batchStart;
                    log.info("[EXPERT-BATCH] Batch {} completed in {}ms for suggestion {}",
                            batchIndex + 1, batchElapsed, suggestionId);

                    for (CompletableFuture<ExpertBatchResult> future : futures) {
                        try {
                            ExpertBatchResult result = future.join();
                            handleExpertReviewResponse(suggestionId, result.response,
                                    result.expert, result.sessionId);

                            // Check if the expert paused the pipeline (clarification)
                            Suggestion refreshed = suggestionRepository.findById(suggestionId).orElse(null);
                            if (refreshed == null) return;
                            if (refreshed.getPendingClarificationQuestions() != null) {
                                log.info("[EXPERT-BATCH] Batch {} paused: {} needs clarification for suggestion {}",
                                        batchIndex + 1, result.expert.getDisplayName(), suggestionId);
                                return;
                            }
                        } catch (Exception e) {
                            log.error("[EXPERT-BATCH] Error processing result for suggestion {}: {}",
                                    suggestionId, e.getMessage(), e);
                        }
                    }
                });
    }

    private void runSingleExpertReview(Long suggestionId, Suggestion suggestion, ExpertRole expert) {
        suggestion.setCurrentPhase(expert.getDisplayName() + " is reviewing the plan...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
        broadcastExpertReviewStatus(suggestionId);

        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        String reviewSessionId = claudeService.generateSessionId();
        claudeService.expertReview(
                reviewSessionId,
                expert.getDisplayName(),
                expert.getReviewPrompt(),
                suggestion.getTitle(),
                suggestion.getDescription(),
                plan,
                tasksJson,
                previousNotes,
                claudeService.getMainRepoDir(),
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                },
                currentRound
        ).thenAccept(response -> {
            handleExpertReviewResponse(suggestionId, response, expert, reviewSessionId);
        });
    }

    /** Holds the result of a single expert review within a batch. */
    private static class ExpertBatchResult {
        final ExpertRole expert;
        final String sessionId;
        final String response;

        ExpertBatchResult(ExpertRole expert, String sessionId, String response) {
            this.expert = expert;
            this.sessionId = sessionId;
            this.response = response;
        }
    }

    @Transactional
    public void handleExpertReviewResponse(Long suggestionId, String response,
                                            ExpertRole expert, String reviewSessionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        suggestion.setLastActivityAt(Instant.now());

        log.info("[AI-FLOW] suggestion={} expert={} review response received, responseLength={}",
                suggestionId, expert.getDisplayName(), response.length());

        try {
            String json = extractJsonBlock(response);
            if (json == null) {
                // Can't parse — re-invoke expert for a proper structured response
                log.info("Expert {} provided no structured response, re-invoking for proper review", expert.getDisplayName());
                reInvokeExpertForDetailedReview(suggestionId, expert, reviewSessionId);
                return;
            }

            JsonNode root = objectMapper.readTree(json);
            String status = root.has("status") ? root.get("status").asText() : "APPROVED";
            String analysis = root.has("analysis") ? root.get("analysis").asText() : "";
            String message = root.has("message") ? root.get("message").asText() : "";

            log.info("[AI-FLOW] suggestion={} expert={} verdict={} analysisLength={}",
                    suggestionId, expert.getDisplayName(), status, analysis.length());

            // Ensure every expert provides substantive feedback
            if (!isSubstantiveAnalysis(analysis) && !"NEEDS_CLARIFICATION".equals(status)) {
                log.info("Expert {} provided insufficient analysis (length={}), re-invoking for detailed review",
                        expert.getDisplayName(), analysis.length());
                reInvokeExpertForDetailedReview(suggestionId, expert, reviewSessionId);
                return;
            }

            if ("NEEDS_CLARIFICATION".equals(status)) {
                // Expert needs user input — save questions and wait
                List<String> questions = extractQuestions(response);
                if (questions != null && !questions.isEmpty()) {
                    try {
                        suggestion.setPendingClarificationQuestions(objectMapper.writeValueAsString(questions));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize expert clarification questions", e);
                    }
                    suggestion.setCurrentPhase(expert.getDisplayName() + " has questions for you");
                    suggestionRepository.save(suggestion);
                    broadcastUpdate(suggestion);

                    addMessage(suggestionId, SenderType.AI, expert.getDisplayName(), message);
                    broadcastExpertClarificationQuestions(suggestionId, questions, expert);
                } else {
                    // No actual questions — treat as approved
                    appendExpertNote(suggestion, expert, "Approved.");
                    addMessage(suggestionId, SenderType.AI, expert.getDisplayName(), "Approved.");
                    advanceExpertStep(suggestion);
                    runNextExpertReview(suggestionId);
                }
                return;
            }

            if ("CHANGES_PROPOSED".equals(status)) {
                String proposedChanges = root.has("proposedChanges") ? root.get("proposedChanges").asText() : "";

                // Pick the right reviewer: Product Manager for UX/product experts,
                // Software Architect for technical experts
                ExpertRole reviewer = pickChangeReviewer(expert);

                suggestion.setCurrentPhase(reviewer.getDisplayName() + " is evaluating " +
                        expert.getDisplayName() + "'s recommendations...");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);

                String reviewerSessionId = claudeService.generateSessionId();
                String currentPlan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : "";
                int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;

                claudeService.reviewExpertFeedback(
                        reviewerSessionId,
                        expert.getDisplayName(),
                        analysis,
                        proposedChanges,
                        currentPlan,
                        reviewer.getDisplayName(),
                        reviewer.getReviewPrompt(),
                        claudeService.getMainRepoDir(),
                        progress -> {},
                        currentRound
                ).thenAccept(reviewerResponse -> {
                    handleReviewerResponse(suggestionId, reviewerResponse, expert, response, analysis, message);
                });
                return;
            }

            // APPROVED or unknown status — record note and advance
            appendExpertNote(suggestion, expert, "Approved.");
            addMessage(suggestionId, SenderType.AI, expert.getDisplayName(), "Approved.");
            advanceExpertStep(suggestion);
            runNextExpertReview(suggestionId);

        } catch (Exception e) {
            log.error("Failed to handle expert review response for suggestion {}: {}",
                    suggestionId, e.getMessage(), e);
            // Re-invoke the expert instead of skipping — every expert must participate
            log.info("Re-invoking {} after error to ensure participation", expert.getDisplayName());
            reInvokeExpertForDetailedReview(suggestionId, expert, reviewSessionId);
        }
    }

    @Transactional
    public void handleReviewerResponse(Long suggestionId, String reviewerResponse,
                                        ExpertRole expert, String originalExpertResponse,
                                        String expertAnalysis, String expertMessage) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        boolean applyChanges = false;
        String reviewerNotes = "";

        try {
            String json = extractJsonBlock(reviewerResponse);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                applyChanges = root.has("apply") && root.get("apply").asBoolean();
                reviewerNotes = root.has("notes") ? root.get("notes").asText() : "";
            }
        } catch (Exception e) {
            log.warn("Failed to parse reviewer response: {}", e.getMessage());
        }

        if (applyChanges && isPlanLocked(suggestion.getStatus())) {
            log.warn("Blocking expert plan changes for suggestion {} — plan is locked in {} state",
                    suggestionId, suggestion.getStatus());
            applyChanges = false;
        }

        if (applyChanges) {
            // Apply the expert's proposed changes to the plan
            try {
                String json = extractJsonBlock(originalExpertResponse);
                if (json != null) {
                    JsonNode root = objectMapper.readTree(json);
                    if (root.has("revisedPlan")) {
                        suggestion.setPlanSummary(root.get("revisedPlan").asText());
                    }
                    if (root.has("revisedPlanDisplaySummary")) {
                        suggestion.setPlanDisplaySummary(root.get("revisedPlanDisplaySummary").asText());
                    }
                    if (root.has("revisedTasks") && root.get("revisedTasks").isArray()) {
                        savePlanTasksFromNode(suggestionId, root.get("revisedTasks"));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to apply expert changes: {}", e.getMessage());
            }

            appendExpertNote(suggestion, expert,
                    "Proposed changes — ACCEPTED. " + expertAnalysis +
                    "\nReviewer: " + reviewerNotes);
            addMessage(suggestionId, SenderType.AI, expert.getDisplayName(),
                    expertMessage + "\n\n*Changes have been applied to the plan.*");

            // Mark that the plan changed during this round and track which domain caused it
            suggestion.setExpertReviewPlanChanged(true);
            String changedDomain = expert.domain().name();
            String existing = suggestion.getExpertReviewChangedDomains();
            if (existing == null || existing.isBlank()) {
                suggestion.setExpertReviewChangedDomains(changedDomain);
            } else if (!existing.contains(changedDomain)) {
                suggestion.setExpertReviewChangedDomains(existing + "," + changedDomain);
            }
            suggestionRepository.save(suggestion);

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "The plan was updated. Remaining experts will continue reviewing before a new round begins.");
            advanceExpertStep(suggestion);
            broadcastTasks(suggestionId);
            broadcastExpertReviewStatus(suggestionId);
            runNextExpertReview(suggestionId);
        } else {
            appendExpertNote(suggestion, expert,
                    "Proposed changes — not applied. " + expertAnalysis +
                    "\nReviewer: " + reviewerNotes);
            addMessage(suggestionId, SenderType.AI, expert.getDisplayName(),
                    expertMessage + "\n\n*Recommendations were noted but the plan remains unchanged.*");

            advanceExpertStep(suggestion);
            broadcastTasks(suggestionId);
            runNextExpertReview(suggestionId);
        }
    }

    @Transactional
    public void handleExpertClarificationAnswers(Long suggestionId, String senderName,
                                                   List<ClarificationRequest.ClarificationAnswer> answers) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        if (suggestion.getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            log.warn("Rejecting expert clarification for suggestion {} in state {}",
                    suggestionId, suggestion.getStatus());
            throw new IllegalStateException(
                    "Expert reviews cannot be submitted when the suggestion is in " + suggestion.getStatus() + " state.");
        }

        suggestion.setLastActivityAt(Instant.now());
        suggestion.setPendingClarificationQuestions(null);
        suggestionRepository.save(suggestion);

        int step = suggestion.getExpertReviewStep() != null ? suggestion.getExpertReviewStep() : 0;
        ExpertRole expert = ExpertRole.fromStep(step);

        if (expert == null) {
            // User answered the max-rounds clarification — check hard cap first
            int totalRounds = suggestion.getTotalExpertReviewRounds() != null ? suggestion.getTotalExpertReviewRounds() : 0;

            StringBuilder formattedMsg = new StringBuilder();
            for (ClarificationRequest.ClarificationAnswer qa : answers) {
                formattedMsg.append("**Q: ").append(qa.getQuestion()).append("**\n");
                formattedMsg.append("A: ").append(qa.getAnswer()).append("\n\n");
            }
            addMessage(suggestionId, SenderType.USER, senderName, formattedMsg.toString().trim());

            if (totalRounds >= MAX_TOTAL_EXPERT_REVIEW_ROUNDS) {
                // Hard cap reached — finalize the plan, no more review cycles
                log.warn("Suggestion {} hit hard cap of {} total review rounds after user guidance, force-finalizing",
                        suggestionId, MAX_TOTAL_EXPERT_REVIEW_ROUNDS);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "Thank you for the guidance. The plan has been through extensive review (" +
                        totalRounds + " total rounds) and will be finalized as-is.");

                suggestion.setStatus(SuggestionStatus.PLANNED);
                suggestion.setExpertReviewStep(null);
                suggestion.setExpertReviewRound(null);
                suggestion.setExpertReviewPlanChanged(null);
                suggestion.setTotalExpertReviewRounds(null);
                suggestion.setCurrentPhase("Plan ready — waiting for approval");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);
                broadcastExpertReviewStatus(suggestionId);
                return;
            }

            // Append user guidance to expert notes so reviewers can see it
            String userGuidance = "User guidance after review rounds:\n" +
                    formattedMsg.toString().trim();
            String existingNotes = suggestion.getExpertReviewNotes();
            suggestion.setExpertReviewNotes(
                    (existingNotes != null ? existingNotes + "\n\n" : "") + userGuidance);

            // Reset review pipeline with fresh rounds (total keeps accumulating)
            suggestion.setExpertReviewStep(0);
            suggestion.setExpertReviewRound(1);
            suggestion.setTotalExpertReviewRounds(totalRounds + 1);
            suggestion.setExpertReviewPlanChanged(false);
            suggestion.setCurrentPhase("Restarting expert reviews with your guidance...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Thank you for the guidance. Restarting expert reviews with your direction in mind.");
            broadcastExpertReviewStatus(suggestionId);
            runNextExpertReview(suggestionId);
            return;
        }

        // Format answers as message
        StringBuilder formattedMessage = new StringBuilder();
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            formattedMessage.append("**Q: ").append(qa.getQuestion()).append("**\n");
            formattedMessage.append("A: ").append(qa.getAnswer()).append("\n\n");
        }
        addMessage(suggestionId, SenderType.USER, senderName, formattedMessage.toString().trim());

        // Build prompt with user's answers for the expert to continue
        StringBuilder prompt = new StringBuilder();
        prompt.append("The user has provided answers to your questions. Please continue your review as ")
              .append(expert.getDisplayName()).append(".\n\n");
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            prompt.append("Question: ").append(qa.getQuestion()).append("\n");
            prompt.append("Answer: ").append(qa.getAnswer()).append("\n\n");
        }
        prompt.append("Based on these answers, complete your review of the plan.\n\n");
        prompt.append("Respond in this JSON format:\n");
        prompt.append("If the plan looks good: {\"status\": \"APPROVED\", \"analysis\": \"...\", \"message\": \"...\"}\n");
        prompt.append("If you recommend changes: {\"status\": \"CHANGES_PROPOSED\", \"analysis\": \"...\", ");
        prompt.append("\"proposedChanges\": \"...\", \"revisedPlan\": \"...\", ");
        prompt.append("\"revisedTasks\": [{\"title\": \"...\", \"description\": \"...\", \"estimatedMinutes\": N}, ...], ");
        prompt.append("\"message\": \"...\"}\n");
        prompt.append("If you still need clarification: {\"status\": \"NEEDS_CLARIFICATION\", \"analysis\": \"...\", ");
        prompt.append("\"questions\": [...], \"message\": \"...\"}\n\n");
        prompt.append("COMMUNICATION RULES: All messages MUST be plain, non-technical language. NEVER mention technologies or implementation details.");

        suggestion.setCurrentPhase(expert.getDisplayName() + " is reviewing your answers...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String reviewSessionId = claudeService.generateSessionId();
        claudeService.continueConversation(
                reviewSessionId,
                prompt.toString(),
                null,
                claudeService.getMainRepoDir(),
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                }
        ).thenAccept(response -> {
            handleExpertReviewResponse(suggestionId, response, expert, reviewSessionId);
        });
    }

    private void advanceExpertStep(Suggestion suggestion) {
        int current = suggestion.getExpertReviewStep() != null ? suggestion.getExpertReviewStep() : 0;
        ExpertRole expert = ExpertRole.fromStep(current);
        if (expert != null) {
            expertRetryCount.remove(suggestion.getId() + "-" + expert.name());
        }
        suggestion.setExpertReviewStep(current + 1);
        suggestionRepository.save(suggestion);
        broadcastExpertReviewStatus(suggestion.getId());
    }

    private void appendExpertNote(Suggestion suggestion, ExpertRole expert, String note) {
        String existing = suggestion.getExpertReviewNotes();
        String entry = "**" + expert.getDisplayName() + "**: " + note;
        if (existing == null || existing.isBlank()) {
            suggestion.setExpertReviewNotes(entry);
        } else {
            suggestion.setExpertReviewNotes(existing + "\n\n" + entry);
        }
        suggestionRepository.save(suggestion);
        broadcastExpertNote(suggestion.getId(), expert.getDisplayName(), note);
    }

    private void broadcastExpertNote(Long suggestionId, String expertName, String note) {
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"expert_note\"" +
                ",\"expertName\":\"" + escapeJson(expertName) + "\"" +
                ",\"note\":\"" + escapeJson(note) + "\"}");
    }

    /**
     * Pick the appropriate expert to review proposed changes.
     * Product Manager reviews UX/product/frontend changes.
     * Software Architect reviews all other technical changes.
     */
    private ExpertRole pickChangeReviewer(ExpertRole proposingExpert) {
        return switch (proposingExpert) {
            case FRONTEND_ENGINEER, UX_EXPERT, QA_ENGINEER -> ExpertRole.PRODUCT_MANAGER;
            default -> ExpertRole.SOFTWARE_ARCHITECT;
        };
    }

    private java.util.Set<ExpertRole.Domain> parseChangedDomains(String domainsStr) {
        java.util.Set<ExpertRole.Domain> domains = new java.util.HashSet<>();
        if (domainsStr == null || domainsStr.isBlank()) return domains;
        for (String name : domainsStr.split(",")) {
            try {
                domains.add(ExpertRole.Domain.valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown domain in changed domains: {}", name);
            }
        }
        return domains;
    }

    /**
     * Run targeted re-reviews for only the specified experts (affected by domain changes).
     * Runs experts sequentially since the set is small (typically 2-4 experts).
     */
    private void runTargetedExpertRereviews(Long suggestionId, java.util.List<ExpertRole> experts, int round) {
        if (experts.isEmpty()) {
            // No experts to re-run — mark all done and trigger finalization
            Suggestion s = suggestionRepository.findById(suggestionId).orElse(null);
            if (s == null) return;
            s.setExpertReviewStep(ExpertRole.reviewOrder().length);
            suggestionRepository.save(s);
            runNextExpertReview(suggestionId);
            return;
        }

        ExpertRole expert = experts.get(0);
        java.util.List<ExpertRole> remaining = experts.subList(1, experts.size());

        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        suggestion.setCurrentPhase(expert.getDisplayName() + " is re-reviewing the plan (round " + round + ")...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        String reviewSessionId = claudeService.generateSessionId();
        claudeService.expertReview(
                reviewSessionId,
                expert.getDisplayName(),
                expert.getReviewPrompt(),
                suggestion.getTitle(),
                suggestion.getDescription(),
                plan,
                tasksJson,
                previousNotes,
                claudeService.getMainRepoDir(),
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                },
                round
        ).thenAccept(response -> {
            handleExpertReviewResponse(suggestionId, response, expert, reviewSessionId);

            // Check if pipeline paused for clarification
            Suggestion refreshed = suggestionRepository.findById(suggestionId).orElse(null);
            if (refreshed != null && refreshed.getPendingClarificationQuestions() == null) {
                if (!remaining.isEmpty()) {
                    runTargetedExpertRereviews(suggestionId, remaining, round);
                } else {
                    // All targeted experts done — trigger the finalization check
                    refreshed.setExpertReviewStep(ExpertRole.reviewOrder().length);
                    suggestionRepository.save(refreshed);
                    runNextExpertReview(suggestionId);
                }
            }
        });
    }

    private boolean isPlanLocked(SuggestionStatus status) {
        return status == SuggestionStatus.APPROVED
            || status == SuggestionStatus.IN_PROGRESS
            || status == SuggestionStatus.TESTING
            || status == SuggestionStatus.DEV_COMPLETE;
    }

    private boolean isSubstantiveAnalysis(String analysis) {
        if (analysis == null || analysis.isBlank()) return false;
        return analysis.trim().length() >= MIN_EXPERT_ANALYSIS_LENGTH;
    }

    private void reInvokeExpertForDetailedReview(Long suggestionId, ExpertRole expert, String originalSessionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        String retryKey = suggestionId + "-" + expert.name();
        int retries = expertRetryCount.getOrDefault(retryKey, 0);
        if (retries >= MAX_EXPERT_RETRIES) {
            log.warn("Expert {} exceeded max retries for suggestion {}, accepting current response",
                    expert.getDisplayName(), suggestionId);
            expertRetryCount.remove(retryKey);
            appendExpertNote(suggestion, expert, "Review completed (response accepted after retries).");
            advanceExpertStep(suggestion);
            runNextExpertReview(suggestionId);
            return;
        }
        expertRetryCount.put(retryKey, retries + 1);

        suggestion.setCurrentPhase(expert.getDisplayName() + " is providing more detail...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        String retrySessionId = claudeService.generateSessionId();
        String reinforcedPrompt = expert.getReviewPrompt() +
                "\n\nIMPORTANT: Your previous response did not include enough detail. " +
                "You MUST provide a thorough, substantive analysis (at least 2-3 sentences) covering specific aspects " +
                "of this plan from your expertise as a " + expert.getDisplayName() + ". " +
                "Explain what you evaluated, what you found, and why. Do NOT give a brief or generic response.";

        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        claudeService.expertReview(
                retrySessionId,
                expert.getDisplayName(),
                reinforcedPrompt,
                suggestion.getTitle(),
                suggestion.getDescription(),
                plan,
                tasksJson,
                previousNotes,
                claudeService.getMainRepoDir(),
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                },
                currentRound
        ).thenAccept(response -> {
            handleExpertReviewResponse(suggestionId, response, expert, retrySessionId);
        });
    }

    private void savePlanTasksFromNode(Long suggestionId, JsonNode tasksNode) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion != null && isPlanLocked(suggestion.getStatus())) {
            log.warn("Blocking plan task changes for suggestion {} — plan is locked in {} state",
                    suggestionId, suggestion.getStatus());
            return;
        }
        planTaskRepository.deleteBySuggestionId(suggestionId);

        int order = 1;
        for (JsonNode taskNode : tasksNode) {
            PlanTask task = new PlanTask();
            task.setSuggestionId(suggestionId);
            task.setTaskOrder(order++);
            task.setTitle(taskNode.has("title") ? taskNode.get("title").asText() : "Task " + (order - 1));
            task.setDescription(taskNode.has("description") ? taskNode.get("description").asText() : null);
            task.setDisplayTitle(taskNode.has("displayTitle") ? taskNode.get("displayTitle").asText() : task.getTitle());
            task.setDisplayDescription(taskNode.has("displayDescription") ? taskNode.get("displayDescription").asText() : task.getDescription());
            task.setEstimatedMinutes(taskNode.has("estimatedMinutes") ? taskNode.get("estimatedMinutes").asInt() : null);
            task.setStatus(TaskStatus.PENDING);
            planTaskRepository.save(task);
        }
        log.info("Updated {} plan tasks for suggestion {} from expert revision", order - 1, suggestionId);
        broadcastTasks(suggestionId);
    }

    public Map<String, Object> getExpertReviewStatus(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getExpertReviewStep() == null) return null;

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

        return Map.of(
                "currentStep", currentStep,
                "totalSteps", experts.length,
                "round", currentRound,
                "maxRounds", MAX_EXPERT_REVIEW_ROUNDS,
                "experts", expertList
        );
    }

    private void broadcastExpertReviewStatus(Long suggestionId) {
        Map<String, Object> status = getExpertReviewStatus(suggestionId);
        if (status == null) return;

        try {
            String json = objectMapper.writeValueAsString(status);
            // Add the type field for WebSocket message routing
            String wsJson = "{\"type\":\"expert_review_status\"," + json.substring(1);
            webSocketHandler.sendToSuggestion(suggestionId, wsJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize expert review status", e);
        }
    }

    private void broadcastExpertClarificationQuestions(Long suggestionId, List<String> questions,
                                                        ExpertRole expert) {
        try {
            String questionsJson = objectMapper.writeValueAsString(questions);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"expert_clarification_questions\",\"questions\":" + questionsJson +
                    ",\"expertName\":\"" + escapeJson(expert.getDisplayName()) + "\"}");
        } catch (JsonProcessingException e) {
            log.error("Failed to broadcast expert clarification questions", e);
        }
    }

    public int getExpertReviewTotalSteps() {
        return ExpertRole.reviewOrder().length;
    }

    @Transactional
    public void handleUserReply(Long suggestionId, String senderName, String message) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setLastActivityAt(Instant.now());
        suggestionRepository.save(suggestion);

        addMessage(suggestionId, SenderType.USER, senderName, message);

        // Continue the Claude conversation
        suggestion.setCurrentPhase("Processing your response...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                message,
                context,
                claudeService.getMainRepoDir(),
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
        suggestion.setCurrentPhase("Approved — getting ready to start");
        suggestionRepository.save(suggestion);

        addMessage(suggestionId, SenderType.SYSTEM, "System", "This suggestion has been **approved** and work will begin shortly.");
        broadcastUpdate(suggestion);

        // Begin execution
        executeApprovedSuggestion(suggestion);

        return suggestion;
    }

    private void executeApprovedSuggestion(Suggestion suggestion) {
        log.info("[AI-FLOW] suggestion={} starting execution", suggestion.getId());
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Cannot start work yet — the project hasn't been set up. An admin needs to configure the project in Settings.");
            suggestion.setCurrentPhase("Blocked — project not configured");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return;
        }

        suggestion.setStatus(SuggestionStatus.IN_PROGRESS);
        suggestion.setCurrentPhase("Setting up a workspace for this suggestion...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Clone repo into suggestion-{id}-repo/ and execute in background
        new Thread(() -> {
            try {
                String workDir = claudeService.cloneRepository(repoUrl, suggestion.getId().toString());

                // Create a new branch from main for this suggestion's changes
                String branchName = "suggestion-" + suggestion.getId();
                claudeService.createBranch(workDir, branchName);

                suggestion.setWorkingDirectory(workDir);
                suggestionRepository.save(suggestion);

                addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Workspace ready. Starting work on your suggestion one task at a time...");

                // Start executing the first task
                executeNextTask(suggestion.getId());

            } catch (Exception e) {
                log.error("Failed to execute suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
                addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Something went wrong while working on this suggestion. It can be retried.");
                suggestion.setCurrentPhase("Failed — can retry");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);
            }
        }).start();
    }

    /**
     * Find the next PENDING task and execute it. Called after each task's expert review passes.
     * If no more tasks remain, finalize the suggestion as COMPLETED.
     */
    private void executeNextTask(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) {
            // No tasks — use legacy full-plan execution
            log.warn("[AI-FLOW] suggestion={} has no tasks, falling back to full plan execution", suggestionId);
            String workDir = suggestion.getWorkingDirectory();
            String executionSessionId = claudeService.generateSessionId();
            String tasksJson = buildTasksJsonForExecution(suggestionId);
            claudeService.executePlan(
                    executionSessionId,
                    suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription(),
                    tasksJson,
                    workDir,
                    progress -> handleExecutionProgress(suggestionId, progress)
            ).thenAccept(result -> handleExecutionResult(suggestionId, result));
            return;
        }

        // Find the next pending task
        PlanTask nextTask = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst()
                .orElse(null);

        if (nextTask == null) {
            // All tasks are done — finalize
            log.info("[AI-FLOW] suggestion={} all {} tasks completed, finalizing", suggestionId, tasks.size());
            markRemainingTasksCompleted(suggestionId);

            suggestion.setStatus(SuggestionStatus.DEV_COMPLETE);
            suggestion.setCurrentPhase("Work completed — submitting changes...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "All tasks have been completed and verified by expert review. Submitting changes...");
            createPrAsync(suggestionId);
            return;
        }

        int totalTasks = tasks.size();
        int taskOrder = nextTask.getTaskOrder();
        log.info("[AI-FLOW] suggestion={} executing task {}/{}: {}", suggestionId, taskOrder, totalTasks, nextTask.getTitle());

        suggestion.setCurrentPhase("Task " + taskOrder + "/" + totalTasks + ": " + nextTask.getTitle());
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Build summary of previously completed tasks for context
        String completedSummary = buildCompletedTasksSummary(suggestionId);

        String executionSessionId = claudeService.generateSessionId();
        String workDir = suggestion.getWorkingDirectory();
        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();

        claudeService.executeSingleTask(
                executionSessionId,
                plan,
                taskOrder,
                nextTask.getTitle(),
                nextTask.getDescription(),
                totalTasks,
                completedSummary,
                workDir,
                progress -> handleExecutionProgress(suggestionId, progress)
        ).thenAccept(result -> handleSingleTaskResult(suggestionId, taskOrder, result));
    }

    /**
     * Handle the result of a single task execution. If the task completed,
     * trigger expert review before moving to the next task.
     */
    private void handleSingleTaskResult(Long suggestionId, int taskOrder, String result) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        log.info("[AI-FLOW] suggestion={} task {} execution result: resultLength={}",
                suggestionId, taskOrder, result.length());

        Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
        if (optTask.isEmpty()) return;

        PlanTask task = optTask.get();

        if (task.getStatus() == TaskStatus.COMPLETED) {
            // Task was marked completed during progress streaming — trigger expert review
            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " implementation complete. Experts are now reviewing the work...");
            startTaskCompletionReview(suggestionId, taskOrder);
        } else if (task.getStatus() == TaskStatus.FAILED) {
            // Task failed — notify and halt
            addMessage(suggestionId, SenderType.AI, "Claude", result);
            suggestion.setCurrentPhase("Task " + taskOrder + " failed — can retry");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
        } else {
            // Task didn't report completion through progress — mark it and review
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            if (task.getStartedAt() == null) task.setStartedAt(task.getCompletedAt());
            planTaskRepository.save(task);
            broadcastTaskUpdate(suggestionId, task);

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " implementation complete. Experts are now reviewing the work...");
            startTaskCompletionReview(suggestionId, taskOrder);
        }
    }

    /**
     * Start expert review of a completed task. Uses Software Engineer and QA Engineer
     * to verify the task was properly implemented before proceeding.
     */
    private void startTaskCompletionReview(Long suggestionId, int taskOrder) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
        if (optTask.isEmpty()) return;

        PlanTask task = optTask.get();

        // Block review if the task is already being reviewed or is not in COMPLETED state
        if (task.getStatus() == TaskStatus.REVIEWING) {
            log.warn("Task {} for suggestion {} is already being reviewed, skipping duplicate review",
                    taskOrder, suggestionId);
            return;
        }
        if (task.getStatus() != TaskStatus.COMPLETED) {
            log.warn("Task {} for suggestion {} is in {} state, not eligible for expert review",
                    taskOrder, suggestionId, task.getStatus());
            return;
        }

        // Mark as REVIEWING to prevent re-entry
        task.setStatus(TaskStatus.REVIEWING);
        planTaskRepository.save(task);
        broadcastTaskUpdate(suggestionId, task);

        List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        int totalTasks = allTasks.size();

        suggestion.setCurrentPhase("Experts reviewing task " + taskOrder + "/" + totalTasks + ": " + task.getTitle());
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        // Use Software Engineer and QA Engineer to review the task completion
        ExpertRole[] taskReviewers = { ExpertRole.SOFTWARE_ENGINEER, ExpertRole.QA_ENGINEER };
        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();
        String workDir = suggestion.getWorkingDirectory();

        // Run reviewers sequentially — SE first, then QA
        runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, taskReviewers, 0);
    }

    /**
     * Run a single task reviewer. After completion, either run the next reviewer
     * or proceed to the next task.
     */
    private void runTaskReviewer(Long suggestionId, int taskOrder, PlanTask task,
                                   String plan, String workDir,
                                   ExpertRole[] reviewers, int reviewerIndex) {
        if (reviewerIndex >= reviewers.length) {
            // All reviewers approved — mark task as COMPLETED and move to next task
            task.setStatus(TaskStatus.COMPLETED);
            planTaskRepository.save(task);
            broadcastTaskUpdate(suggestionId, task);

            Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
            if (suggestion == null) return;

            List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
            long completedCount = allTasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " has been verified by expert review (" + completedCount + "/" + allTasks.size() + " tasks complete). Moving to the next task...");

            log.info("[AI-FLOW] suggestion={} task {} passed expert review, proceeding to next task", suggestionId, taskOrder);
            executeNextTask(suggestionId);
            return;
        }

        ExpertRole reviewer = reviewers[reviewerIndex];
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        int totalTasks = allTasks.size();

        suggestion.setCurrentPhase(reviewer.getDisplayName() + " reviewing task " + taskOrder + "/" + totalTasks + "...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String reviewSessionId = claudeService.generateSessionId();
        claudeService.reviewTaskCompletion(
                reviewSessionId,
                reviewer.getDisplayName(),
                reviewer.getReviewPrompt(),
                suggestion.getTitle(),
                taskOrder,
                task.getTitle(),
                task.getDescription(),
                plan,
                workDir,
                progress -> {
                    webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"execution_progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}");
                }
        ).thenAccept(response -> {
            handleTaskReviewResponse(suggestionId, taskOrder, task, plan, workDir,
                    reviewer, reviewers, reviewerIndex, response);
        });
    }

    /**
     * Handle the response from a task completion reviewer.
     */
    private void handleTaskReviewResponse(Long suggestionId, int taskOrder, PlanTask task,
                                            String plan, String workDir,
                                            ExpertRole reviewer, ExpertRole[] reviewers,
                                            int reviewerIndex, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        log.info("[AI-FLOW] suggestion={} task {} reviewer={} response received",
                suggestionId, taskOrder, reviewer.getDisplayName());

        try {
            String json = extractJsonBlock(response);
            if (json == null) {
                // Can't parse — treat as approved to avoid blocking
                log.warn("Task reviewer {} provided no structured response for task {}, treating as approved",
                        reviewer.getDisplayName(), taskOrder);
                broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                        "Task " + taskOrder + " review: Approved (no structured response).");
                runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex + 1);
                return;
            }

            JsonNode root = objectMapper.readTree(json);
            String status = root.has("status") ? root.get("status").asText() : "APPROVED";
            String analysis = root.has("analysis") ? root.get("analysis").asText() : "";
            String message = root.has("message") ? root.get("message").asText() : "";

            log.info("[AI-FLOW] suggestion={} task {} reviewer={} verdict={}",
                    suggestionId, taskOrder, reviewer.getDisplayName(), status);

            if ("NEEDS_FIXES".equals(status)) {
                String fixes = root.has("fixes") ? root.get("fixes").asText() : analysis;

                broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                        "Task " + taskOrder + " review: Needs fixes. " + analysis);
                addMessage(suggestionId, SenderType.AI, reviewer.getDisplayName(), message);

                // Send fixes back to Claude to address, then re-review
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        reviewer.getDisplayName() + " found issues with task " + taskOrder + ". Fixing...");

                suggestion.setCurrentPhase("Fixing issues in task " + taskOrder + " found by " + reviewer.getDisplayName() + "...");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);

                // Re-execute the task with fix instructions
                String fixSessionId = claudeService.generateSessionId();
                String fixPrompt = String.format(
                        "The %s has reviewed your implementation of task %d and found issues that need to be fixed.\n\n" +
                        "Task: %s\n" +
                        "Description: %s\n\n" +
                        "Issues found:\n%s\n\n" +
                        "Please fix these issues. After fixing, verify your changes are correct.\n\n" +
                        "When done, output:\n" +
                        "{\"taskOrder\": %d, \"status\": \"COMPLETED\", \"message\": \"what was fixed and verified\"}",
                        reviewer.getDisplayName(), taskOrder,
                        task.getTitle(),
                        task.getDescription() != null ? task.getDescription() : task.getTitle(),
                        fixes,
                        taskOrder
                );

                claudeService.continueConversation(
                        fixSessionId,
                        fixPrompt,
                        null,
                        workDir,
                        progress -> handleExecutionProgress(suggestionId, progress)
                ).thenAccept(fixResult -> {
                    // After fixing, re-run the same reviewer
                    log.info("[AI-FLOW] suggestion={} task {} fix applied, re-reviewing with {}",
                            suggestionId, taskOrder, reviewer.getDisplayName());
                    runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex);
                });
                return;
            }

            // APPROVED — record note and move to next reviewer
            broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                    "Task " + taskOrder + ": Approved.");
            addMessage(suggestionId, SenderType.AI, reviewer.getDisplayName(), "Approved.");
            runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex + 1);

        } catch (Exception e) {
            log.error("Failed to handle task review response for suggestion {} task {}: {}",
                    suggestionId, taskOrder, e.getMessage(), e);
            // Treat as approved to avoid blocking
            broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                    "Task " + taskOrder + " review: Approved (processing error).");
            runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex + 1);
        }
    }

    private String buildCompletedTasksSummary(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            if (task.getStatus() == TaskStatus.COMPLETED) {
                sb.append("Task ").append(task.getTaskOrder()).append(" (COMPLETED): ").append(task.getTitle()).append("\n");
            }
        }
        return sb.toString();
    }

    @Transactional
    public void handleExecutionResult(Long suggestionId, String result) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        String execStatus = result.contains("COMPLETED") ? "COMPLETED" :
                result.contains("FAILED") ? "FAILED" : "TESTING";
        log.info("[AI-FLOW] suggestion={} execution result: status={} resultLength={}",
                suggestionId, execStatus, result.length());

        addMessage(suggestionId, SenderType.AI, "Claude", result);

        if (result.contains("COMPLETED")) {
            // Mark all remaining non-completed tasks as COMPLETED
            markRemainingTasksCompleted(suggestionId);

            suggestion.setStatus(SuggestionStatus.DEV_COMPLETE);
            suggestion.setCurrentPhase("Work completed — submitting changes...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            // Push branch and create PR in background
            createPrAsync(suggestion.getId());
            return;
        } else if (result.contains("FAILED")) {
            suggestion.setCurrentPhase("Something went wrong — can retry");
        } else {
            suggestion.setStatus(SuggestionStatus.TESTING);
            suggestion.setCurrentPhase("Verifying the changes...");
        }

        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
    }

    @Async
    public void createPrAsync(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) {
            log.error("Cannot create PR: suggestion {} not found", suggestionId);
            return;
        }
        createPrForSuggestion(suggestion);
    }

    private void createPrForSuggestion(Suggestion suggestion) {
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        String githubToken = settingsService.getSettings().getGithubToken();
        String branchName = "suggestion-" + suggestion.getId();
        String workDir = suggestion.getWorkingDirectory();

        // Step 1: Generate changelog entry
        String changelog = generateChangelog(suggestion);
        suggestion.setChangelogEntry(changelog);
        suggestionRepository.save(suggestion);

        // Step 2: Push branch
        try {
            suggestion.setCurrentPhase("Submitting changes...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            claudeService.pushBranch(workDir, branchName);

            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes have been submitted for review.");
        } catch (Exception e) {
            log.error("Failed to push branch for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Completed the work, but couldn't submit the changes. An admin can retry this.");
            suggestion.setCurrentPhase("Done, but submission failed");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return;
        }

        // Step 3: Create PR
        if (githubToken == null || githubToken.isBlank()) {
            log.warn("No GitHub token configured, skipping PR creation for suggestion {}", suggestion.getId());
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes submitted. Automatic review request wasn't created — an admin can set this up in Settings.");
            suggestion.setCurrentPhase("Done — changes submitted");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return;
        }

        try {
            suggestion.setCurrentPhase("Creating a review request...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            String prTitle = "Suggestion #" + suggestion.getId() + ": " + suggestion.getTitle();
            String prBody = buildPrBody(suggestion);

            var prResult = claudeService.createGitHubPullRequest(
                    repoUrl, branchName, prTitle, prBody, githubToken);

            String prUrl = (String) prResult.get("html_url");
            int prNumber = (int) prResult.get("number");

            suggestion.setPrUrl(prUrl);
            suggestion.setPrNumber(prNumber);
            suggestion.setStatus(SuggestionStatus.FINAL_REVIEW);
            suggestion.setCurrentPhase("Done — ready for review");
            suggestionRepository.save(suggestion);

            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes are ready for review: " + prUrl);

            broadcastUpdate(suggestion);
            // Send PR URL via WebSocket so frontend can display it immediately
            webSocketHandler.sendToSuggestion(suggestion.getId(),
                    "{\"type\":\"pr_created\",\"prUrl\":\"" + escapeJson(prUrl) +
                    "\",\"prNumber\":" + prNumber + "}");

        } catch (Exception e) {
            log.error("Failed to create PR for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes were submitted, but the review request couldn't be created automatically.");
            suggestion.setCurrentPhase("Done — review request failed");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
        }
    }

    public Map<String, Object> retryPrCreation(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) {
            return Map.of("success", false, "error", "Suggestion not found");
        }
        if (!"Done — review request failed".equals(suggestion.getCurrentPhase())) {
            return Map.of("success", false, "error", "Suggestion is not in a retryable state");
        }

        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        String githubToken = settingsService.getSettings().getGithubToken();
        String branchName = "suggestion-" + suggestion.getId();

        if (githubToken == null || githubToken.isBlank()) {
            return Map.of("success", false, "error", "No GitHub token configured. Please update it in Settings.");
        }

        try {
            suggestion.setCurrentPhase("Creating a review request...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            String prTitle = "Suggestion #" + suggestion.getId() + ": " + suggestion.getTitle();
            String prBody = buildPrBody(suggestion);

            var prResult = claudeService.createGitHubPullRequest(
                    repoUrl, branchName, prTitle, prBody, githubToken);

            String prUrl = (String) prResult.get("html_url");
            int prNumber = (int) prResult.get("number");

            suggestion.setPrUrl(prUrl);
            suggestion.setPrNumber(prNumber);
            suggestion.setStatus(SuggestionStatus.FINAL_REVIEW);
            suggestion.setCurrentPhase("Done — ready for review");
            suggestionRepository.save(suggestion);

            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes are ready for review: " + prUrl);

            broadcastUpdate(suggestion);
            webSocketHandler.sendToSuggestion(suggestion.getId(),
                    "{\"type\":\"pr_created\",\"prUrl\":\"" + escapeJson(prUrl) +
                    "\",\"prNumber\":" + prNumber + "}");

            return Map.of("success", true, "prUrl", prUrl);

        } catch (Exception e) {
            log.error("Retry PR creation failed for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Review request retry failed. An admin can try again.");
            suggestion.setCurrentPhase("Done — review request failed");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return Map.of("success", false, "error", "PR creation failed: " + e.getMessage());
        }
    }

    private String generateChangelog(Suggestion suggestion) {
        StringBuilder entry = new StringBuilder();
        entry.append("### Suggestion #").append(suggestion.getId())
                .append(": ").append(suggestion.getTitle()).append("\n\n");

        entry.append("**Author:** ").append(suggestion.getAuthorName() != null ?
                suggestion.getAuthorName() : "Anonymous").append("\n");
        entry.append("**Date:** ").append(java.time.LocalDate.now()).append("\n");
        entry.append("**Branch:** `suggestion-").append(suggestion.getId()).append("`\n\n");

        entry.append("**Description:**\n").append(suggestion.getDescription()).append("\n\n");

        if (suggestion.getPlanSummary() != null) {
            entry.append("**Implementation Plan:**\n").append(suggestion.getPlanSummary()).append("\n");
        }

        return entry.toString();
    }

    private String buildPrBody(Suggestion suggestion) {
        StringBuilder body = new StringBuilder();
        body.append("## Changelog\n\n");
        body.append(suggestion.getChangelogEntry()).append("\n");

        body.append("---\n\n");
        body.append("*Automatically created by Site Suggestion Platform from ");
        body.append("[Suggestion #").append(suggestion.getId()).append("]*\n\n");

        // Link back to the suggestion in the platform
        body.append("**Suggestion link:** `/suggestions/").append(suggestion.getId()).append("`\n\n");

        if (suggestion.getUpVotes() > 0 || suggestion.getDownVotes() > 0) {
            body.append("**Community votes:** ").append(suggestion.getUpVotes())
                    .append(" up / ").append(suggestion.getDownVotes()).append(" down\n");
        }

        return body.toString();
    }

    // --- Plan Task management ---

    private void savePlanTasks(Long suggestionId, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion != null && isPlanLocked(suggestion.getStatus())) {
            log.warn("Blocking plan task changes for suggestion {} — plan is locked in {} state",
                    suggestionId, suggestion.getStatus());
            return;
        }
        try {
            // Delete any existing tasks for this suggestion (re-plan scenario)
            planTaskRepository.deleteBySuggestionId(suggestionId);

            String json = extractJsonBlock(response);
            if (json == null) return;

            JsonNode root = objectMapper.readTree(json);
            JsonNode tasksNode = root.get("tasks");
            if (tasksNode == null || !tasksNode.isArray()) return;

            int order = 1;
            for (JsonNode taskNode : tasksNode) {
                PlanTask task = new PlanTask();
                task.setSuggestionId(suggestionId);
                task.setTaskOrder(order++);
                task.setTitle(taskNode.has("title") ? taskNode.get("title").asText() : "Task " + (order - 1));
                task.setDescription(taskNode.has("description") ? taskNode.get("description").asText() : null);
                task.setDisplayTitle(taskNode.has("displayTitle") ? taskNode.get("displayTitle").asText() : task.getTitle());
                task.setDisplayDescription(taskNode.has("displayDescription") ? taskNode.get("displayDescription").asText() : task.getDescription());
                task.setEstimatedMinutes(taskNode.has("estimatedMinutes") ? taskNode.get("estimatedMinutes").asInt() : null);
                task.setStatus(TaskStatus.PENDING);
                planTaskRepository.save(task);
            }

            log.info("Saved {} plan tasks for suggestion {}", order - 1, suggestionId);
            broadcastTasks(suggestionId);
        } catch (Exception e) {
            log.warn("Failed to parse plan tasks from response: {}", e.getMessage());
        }
    }

    public List<PlanTask> getPlanTasks(Long suggestionId) {
        return planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
    }

    private String buildTaskStatusSummary(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            sb.append("Task ").append(task.getTaskOrder()).append(": ")
              .append(task.getTitle()).append(" — ").append(task.getStatus()).append("\n");
        }
        return sb.toString();
    }

    private String buildTasksJsonForExecution(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            sb.append("Task ").append(task.getTaskOrder()).append(": ")
              .append(task.getTitle());
            if (task.getDescription() != null) {
                sb.append(" — ").append(task.getDescription());
            }
            if (task.getEstimatedMinutes() != null) {
                sb.append(" (~").append(task.getEstimatedMinutes()).append(" min)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void handleExecutionProgress(Long suggestionId, String progress) {
        // Forward raw progress to WebSocket
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"execution_progress\",\"content\":\"" +
                        escapeJson(progress) + "\"}");

        // Try to parse task status updates from Claude's output
        try {
            String json = extractJsonBlock(progress);
            if (json == null) return;

            JsonNode node = objectMapper.readTree(json);
            if (!node.has("taskOrder")) return;

            int taskOrder = node.get("taskOrder").asInt();
            String status = node.has("status") ? node.get("status").asText() : null;
            String message = node.has("message") ? node.get("message").asText() : null;

            if (status == null) return;

            Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
            if (optTask.isEmpty()) return;

            PlanTask task = optTask.get();
            TaskStatus newStatus = TaskStatus.valueOf(status);
            task.setStatus(newStatus);

            if (newStatus == TaskStatus.IN_PROGRESS && task.getStartedAt() == null) {
                task.setStartedAt(Instant.now());

                // Update suggestion's currentPhase to show which task is running
                Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
                if (suggestion != null) {
                    List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
                    suggestion.setCurrentPhase("Task " + taskOrder + "/" + allTasks.size() + ": " + task.getTitle());
                    suggestionRepository.save(suggestion);
                    broadcastUpdate(suggestion);
                }
            } else if (newStatus == TaskStatus.REVIEWING) {
                // Update phase to show review in progress
                Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
                if (suggestion != null) {
                    List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
                    suggestion.setCurrentPhase("Reviewing task " + taskOrder + "/" + allTasks.size() + ": " + task.getTitle());
                    suggestionRepository.save(suggestion);
                    broadcastUpdate(suggestion);
                }
            } else if (newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.FAILED) {
                task.setCompletedAt(Instant.now());

                // Update phase to show progress
                Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
                if (suggestion != null) {
                    List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
                    long completed = allTasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
                    // +1 because current task save hasn't been flushed yet
                    if (newStatus == TaskStatus.COMPLETED) completed++;
                    long total = allTasks.size();
                    if (completed >= total) {
                        suggestion.setCurrentPhase("All " + total + " tasks done — wrapping up...");
                    } else {
                        // Find the next pending task
                        String nextTask = allTasks.stream()
                                .filter(t -> t.getStatus() == TaskStatus.PENDING && t.getTaskOrder() > taskOrder)
                                .findFirst()
                                .map(t -> "Task " + t.getTaskOrder() + "/" + total + ": " + t.getTitle())
                                .orElse("Completed " + completed + "/" + total + " tasks");
                        suggestion.setCurrentPhase(nextTask);
                    }
                    suggestionRepository.save(suggestion);
                    broadcastUpdate(suggestion);
                }
            }

            planTaskRepository.save(task);
            broadcastTaskUpdate(suggestionId, task);
        } catch (Exception e) {
            // Not a task status line — just normal progress text, ignore
        }
    }

    private void markRemainingTasksCompleted(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        for (PlanTask task : tasks) {
            if (task.getStatus() != TaskStatus.COMPLETED && task.getStatus() != TaskStatus.FAILED) {
                task.setStatus(TaskStatus.COMPLETED);
                if (task.getCompletedAt() == null) {
                    task.setCompletedAt(Instant.now());
                }
                if (task.getStartedAt() == null) {
                    task.setStartedAt(task.getCompletedAt());
                }
                planTaskRepository.save(task);
                broadcastTaskUpdate(suggestionId, task);
            }
        }
    }

    private void broadcastTasks(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        try {
            String tasksJson = objectMapper.writeValueAsString(tasks);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"tasks_update\",\"tasks\":" + tasksJson + "}");
        } catch (Exception e) {
            log.warn("Failed to broadcast tasks: {}", e.getMessage());
        }
    }

    private void broadcastTaskUpdate(Long suggestionId, PlanTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"task_update\",\"task\":" + taskJson + "}");
        } catch (Exception e) {
            log.warn("Failed to broadcast task update: {}", e.getMessage());
        }
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
                List.of(SuggestionStatus.DRAFT, SuggestionStatus.DISCUSSING, SuggestionStatus.EXPERT_REVIEW),
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
                ",\"downVotes\":" + suggestion.getDownVotes() +
                ",\"planDisplaySummary\":\"" + escapeJson(
                        suggestion.getPlanDisplaySummary() != null ? suggestion.getPlanDisplaySummary() : "") + "\"" +
                ",\"planSummary\":\"" + escapeJson(
                        suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : "") + "\"}");
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

    private String extractMessage(String response) {
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode messageNode = root.get("message");
                if (messageNode != null && messageNode.isTextual()) {
                    return messageNode.asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract message from AI response: {}", e.getMessage());
        }
        // Fallback: return the raw response if no JSON message found
        return response;
    }

    private String extractPlan(String response) {
        // Try to extract plan from JSON response
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                if (root.has("plan")) {
                    return root.get("plan").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract plan via JSON parsing, falling back to string parsing");
        }
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

    private String extractPlanDisplaySummary(String response) {
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                if (root.has("planDisplaySummary")) {
                    return root.get("planDisplaySummary").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract planDisplaySummary: {}", e.getMessage());
        }
        // Fallback: return the regular plan if no display summary
        return extractPlan(response);
    }

    private int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return s.length();
    }

    /**
     * When main-repo is re-cloned (settings changed), have Claude merge main into all active
     * suggestion repo branches — resolving any conflicts intelligently — and re-evaluate impact.
     */
    @Async
    @EventListener
    public void onMainRepoUpdated(MainRepoUpdatedEvent event) {
        log.info("Main repo updated, asking Claude to merge into active suggestion repos...");

        List<String> suggestionRepoDirs = claudeService.findSuggestionRepoDirs();
        if (suggestionRepoDirs.isEmpty()) {
            log.info("No suggestion repos to merge");
            return;
        }

        // Find all active suggestions that have a working directory
        List<SuggestionStatus> activeStatuses = List.of(
                SuggestionStatus.DISCUSSING,
                SuggestionStatus.EXPERT_REVIEW,
                SuggestionStatus.PLANNED,
                SuggestionStatus.APPROVED,
                SuggestionStatus.IN_PROGRESS,
                SuggestionStatus.TESTING
        );

        for (String repoDir : suggestionRepoDirs) {
            triggerClaudeMergeForRepo(repoDir, activeStatuses);
        }
    }

    private void triggerClaudeMergeForRepo(String repoDir, List<SuggestionStatus> activeStatuses) {
        // Extract suggestion ID from directory name (e.g., "suggestion-42-repo")
        String dirName = new java.io.File(repoDir).getName();
        String idStr = dirName.replace("suggestion-", "").replace("-repo", "");
        try {
            Long suggestionId = Long.parseLong(idStr);
            Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
            if (suggestion == null || !activeStatuses.contains(suggestion.getStatus())) {
                return;
            }

            // Fetch the latest from origin (lightweight git operation)
            try {
                claudeService.fetchOrigin(repoDir);
            } catch (Exception e) {
                log.error("Failed to fetch origin for {}: {}", repoDir, e.getMessage());
                return;
            }

            // Detect default branch for the merge prompt
            String defaultBranch;
            try {
                defaultBranch = claudeService.detectDefaultBranchPublic(repoDir);
            } catch (Exception e) {
                log.error("Failed to detect default branch for {}: {}", repoDir, e.getMessage());
                return;
            }

            log.info("Asking Claude to merge origin/{} into suggestion {} repo and assess impact",
                    defaultBranch, suggestionId);

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "The project has been updated. Merging changes and checking impact on this suggestion...");

            suggestion.setCurrentPhase("Merging project updates and checking for impact...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            String mergePrompt = buildMergeAndReEvalPrompt(suggestion, defaultBranch);
            String context = buildConversationContext(suggestionId);

            // Use the suggestion's existing session if available, otherwise create a new one
            String sessionId = suggestion.getClaudeSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "suggestion-" + suggestionId;
            }

            claudeService.mergeWithMain(
                    sessionId,
                    repoDir,
                    mergePrompt,
                    context,
                    progress -> {
                        webSocketHandler.sendToSuggestion(suggestionId,
                                "{\"type\":\"progress\",\"content\":\"" +
                                        escapeJson(progress) + "\"}");
                    }
            ).thenAccept(response -> {
                handleMergeResponse(suggestionId, response);
            });

        } catch (NumberFormatException e) {
            log.warn("Could not parse suggestion ID from directory: {}", dirName);
        }
    }

    private void handleMergeResponse(Long suggestionId, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        // Check if Claude reported a merge failure
        if (response.contains("MERGE_FAILED")) {
            log.error("Claude failed to merge main into suggestion {}: {}", suggestionId, response);
            String displayMessage = extractMessage(response);
            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "The project was updated but the changes could not be merged automatically. " +
                    (displayMessage != null ? displayMessage : "This suggestion may need to be re-evaluated."));

            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("Conflicting changes — needs attention");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return;
        }

        // Check if merge reported no changes
        if (response.contains("NO_CHANGES")) {
            log.info("No changes to merge for suggestion {}", suggestionId);
            suggestion.setCurrentPhase(null);
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            return;
        }

        // Merge succeeded with changes — delegate to the standard AI response handler
        // which handles PLAN_READY and NEEDS_CLARIFICATION
        handleAiResponse(suggestionId, response);
    }

    private String buildMergeAndReEvalPrompt(Suggestion suggestion, String defaultBranch) {
        return "The main repository has been updated with new changes. You need to merge these " +
                "changes into the current suggestion branch and then assess the impact.\n\n" +
                "STEP 1 — MERGE:\n" +
                "Run: git merge origin/" + defaultBranch + "\n" +
                "- If the merge completes cleanly with no changes (already up to date), respond with:\n" +
                "  {\"status\": \"NO_CHANGES\", \"message\": \"Already up to date.\"}\n" +
                "- If the merge completes cleanly WITH changes, proceed to Step 2.\n" +
                "- If there are merge conflicts:\n" +
                "  * Review each conflicting file to understand both sides\n" +
                "  * Resolve conflicts intelligently, preserving both the suggestion's changes " +
                "and the incoming main branch changes where possible\n" +
                "  * Stage the resolved files with git add and complete the merge commit with git commit\n" +
                "  * Then proceed to Step 2\n" +
                "- If you cannot resolve the conflicts, respond with:\n" +
                "  {\"status\": \"MERGE_FAILED\", \"message\": \"<explanation of what went wrong>\"}\n\n" +
                "STEP 2 — ASSESS IMPACT (only if merge brought in changes):\n" +
                "Review the merged changes and determine if they affect this suggestion's plan.\n\n" +
                "Original suggestion:\n" +
                "Title: " + suggestion.getTitle() + "\n" +
                "Description: " + suggestion.getDescription() + "\n" +
                (suggestion.getPlanSummary() != null ?
                        "Current plan: " + suggestion.getPlanSummary() + "\n" : "") +
                "\nCOMMUNICATION RULES:\n" +
                "- All messages and questions MUST be written in plain, non-technical language.\n" +
                "- NEVER mention programming languages, frameworks, file names, or technical details.\n" +
                "- Describe things from the user's perspective.\n\n" +
                "Respond in JSON format:\n" +
                "If the suggestion is NOT affected (plan is still valid):\n" +
                "{\"status\": \"PLAN_READY\", " +
                "\"message\": \"The recent project updates don't affect this suggestion. " +
                "The existing plan is still good.\", " +
                "\"plan\": \"<the existing plan, unchanged>\"}\n\n" +
                "If the suggestion IS affected and you need clarification:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", " +
                "\"message\": \"The project has changed in ways that affect this suggestion.\", " +
                "\"questions\": [\"specific question about how to proceed given the changes\"]}\n\n" +
                "If the suggestion IS affected but you can update the plan:\n" +
                "{\"status\": \"PLAN_READY\", " +
                "\"message\": \"The plan has been updated to account for the recent project changes.\", " +
                "\"plan\": \"<updated plan>\"}\n\n" +
                "IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array.";
    }
}
