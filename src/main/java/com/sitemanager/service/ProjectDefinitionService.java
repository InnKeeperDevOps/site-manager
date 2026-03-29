package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.dto.ProjectDefinitionStateResponse;
import com.sitemanager.model.ProjectDefinitionSession;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.ProjectDefinitionStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ProjectDefinitionSessionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual QA smoke-test checklist:
 * <ol>
 *   <li>Log in as an admin and open the Project Definition page.</li>
 *   <li>Click "Start Interview" — confirm the first question appears.</li>
 *   <li>Answer all 9 questions; verify each answer advances the progress bar.</li>
 *   <li>After the last answer, confirm the status transitions to "Generating"
 *       and then "Saving" (visible via the WebSocket status badge).</li>
 *   <li>In the target GitHub repository, verify that
 *       {@code PROJECT_DEFINITION.md} has been committed on a new branch.</li>
 *   <li>Confirm a pull request was created and its URL is shown in the UI.</li>
 *   <li>If auto-merge is enabled in Settings, confirm the PR was merged
 *       automatically and the status shows "Completed".</li>
 *   <li>If auto-merge is disabled, confirm the status shows "PR Open"
 *       with a link to the pull request for manual merging.</li>
 * </ol>
 */
@Service
public class ProjectDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDefinitionService.class);

    static final List<ProjectDefinitionStatus> NON_TERMINAL_STATUSES = List.of(
            ProjectDefinitionStatus.ACTIVE,
            ProjectDefinitionStatus.GENERATING,
            ProjectDefinitionStatus.SAVING,
            ProjectDefinitionStatus.PR_OPEN
    );

    private final ProjectDefinitionSessionRepository sessionRepository;
    private final ClaudeService claudeService;
    private final SiteSettingsService settingsService;
    private final UserNotificationWebSocketHandler notificationHandler;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${app.workspace-dir:/workspace}")
    private String workspaceDir;

    public ProjectDefinitionService(ProjectDefinitionSessionRepository sessionRepository,
                                    ClaudeService claudeService,
                                    SiteSettingsService settingsService,
                                    UserNotificationWebSocketHandler notificationHandler,
                                    UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.claudeService = claudeService;
        this.settingsService = settingsService;
        this.notificationHandler = notificationHandler;
        this.userRepository = userRepository;
    }

    /**
     * Start a new project definition interview session.
     * Throws IllegalStateException if a non-terminal session already exists.
     * Uses SERIALIZABLE isolation to prevent concurrent session creation.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ProjectDefinitionStateResponse startSession() {
        if (sessionRepository.findFirstByStatusIn(NON_TERMINAL_STATUSES).isPresent()) {
            throw new IllegalStateException("A project definition session is already in progress");
        }

        ProjectDefinitionSession session = new ProjectDefinitionSession();
        session.setStatus(ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId(claudeService.generateSessionId());
        session.setConversationHistory("[]");
        session = sessionRepository.save(session);

        final Long sessionId = session.getId();
        final String claudeSessionId = session.getClaudeSessionId();
        final String prompt = buildInterviewPrompt();

        claudeService.continueConversation(claudeSessionId, prompt, null,
                claudeService.getMainRepoDir(), null)
                .thenAccept(response -> handleClaudeResponse(sessionId, response))
                .exceptionally(ex -> {
                    log.error("Async Claude call failed for session {}: {}", sessionId, ex.getMessage(), ex);
                    markFailed(sessionId, "Something went wrong while starting the interview");
                    return null;
                });

        return toStateResponse(session);
    }

    /**
     * Submit an answer to the current interview question.
     * The next question (or completion notice) arrives via WebSocket broadcast.
     */
    public ProjectDefinitionStateResponse submitAnswer(Long sessionId, String answer) {
        ProjectDefinitionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getStatus() != ProjectDefinitionStatus.ACTIVE) {
            throw new IllegalStateException("Session is not active: " + session.getStatus());
        }

        session.setConversationHistory(appendMessage(session.getConversationHistory(), "user", answer));
        sessionRepository.save(session);

        final Long sid = session.getId();

        claudeService.continueConversation(session.getClaudeSessionId(), answer,
                session.getConversationHistory(), claudeService.getMainRepoDir(), null)
                .thenAccept(response -> handleClaudeResponse(sid, response))
                .exceptionally(ex -> {
                    log.error("Async Claude call failed for session {}: {}", sid, ex.getMessage(), ex);
                    markFailed(sid, "Something went wrong while processing your answer");
                    return null;
                });

        return toStateResponse(session);
    }

    /**
     * Called from the async Claude callback after each conversation turn.
     * Parses the JSON response and transitions the session state accordingly.
     */
    void handleClaudeResponse(Long sessionId, String rawResponse) {
        ProjectDefinitionSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("Session {} not found in handleClaudeResponse", sessionId);
            return;
        }
        if (session.getStatus() != ProjectDefinitionStatus.ACTIVE) {
            log.info("Session {} is no longer ACTIVE ({}), ignoring response", sessionId, session.getStatus());
            return;
        }

        try {
            String json = extractJsonBlock(rawResponse);
            JsonNode root = null;
            if (json != null) {
                try {
                    root = objectMapper.readTree(json);
                } catch (Exception parseEx) {
                    log.debug("Could not parse Claude response as JSON for session {}: {}",
                            sessionId, parseEx.getMessage());
                }
            }
            String type = root != null ? root.path("type").asText("question") : "question";

            session.setConversationHistory(appendMessage(session.getConversationHistory(), "assistant", rawResponse));

            if ("complete".equals(type)) {
                session.setStatus(ProjectDefinitionStatus.GENERATING);
                sessionRepository.save(session);
                broadcastState(session, root);
                generateAndSave(session);
            } else {
                sessionRepository.save(session);
                broadcastState(session, root);
            }
        } catch (Exception e) {
            log.error("Error handling Claude response for session {}", sessionId, e);
            markFailed(sessionId, "Something went wrong while processing the response");
        }
    }

    /**
     * Compile the conversation history into a PROJECT_DEFINITION.md document,
     * then push it to a branch and open a pull request.
     * Annotated @Async so it can be triggered from the Spring proxy; also runs
     * naturally async when called from a thenAccept callback.
     */
    @Async
    public void generateAndSave(ProjectDefinitionSession session) {
        try {
            String compilationPrompt = buildCompilationPrompt();

            String generatedContent = claudeService.continueConversation(
                    session.getClaudeSessionId(),
                    compilationPrompt,
                    session.getConversationHistory(),
                    claudeService.getMainRepoDir(),
                    null
            ).get();

            session.setGeneratedContent(generatedContent);
            session.setStatus(ProjectDefinitionStatus.SAVING);
            sessionRepository.save(session);
            broadcastSimple(session);

            saveAndPushDefinition(session);

        } catch (Exception e) {
            log.error("Failed to generate project definition for session {}", session.getId(), e);
            markFailed(session.getId(), "Something went wrong while creating your project definition");
        }
    }

    /**
     * Commit PROJECT_DEFINITION.md to a new branch, push it, create a PR,
     * and optionally auto-merge. Sets status to COMPLETED or PR_OPEN.
     */
    void saveAndPushDefinition(ProjectDefinitionSession session) {
        SiteSettings settings = settingsService.getSettings();
        String repoUrl = settings.getTargetRepoUrl();
        String githubToken = settings.getGithubToken();
        String mainRepoDir = claudeService.getMainRepoDir();

        try {
            String defaultBranch = detectDefaultBranch(mainRepoDir);

            String timestamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd-HHmmss")
                    .format(LocalDateTime.now());
            String branchName = "project-definition-" + timestamp;

            runGitCommand(mainRepoDir, "git", "fetch", "origin");
            runGitCommand(mainRepoDir, "git", "checkout", defaultBranch);
            runGitCommand(mainRepoDir, "git", "pull", "origin", defaultBranch);
            runGitCommand(mainRepoDir, "git", "checkout", "-b", branchName);

            Files.writeString(Paths.get(mainRepoDir, "PROJECT_DEFINITION.md"),
                    session.getGeneratedContent());

            runGitCommand(mainRepoDir, "git", "add", "PROJECT_DEFINITION.md");
            runGitCommand(mainRepoDir, "git", "commit", "-m", "Add PROJECT_DEFINITION.md");
            runGitCommand(mainRepoDir, "git", "push", "origin", branchName);

            session.setBranchName(branchName);

            if (githubToken == null || githubToken.isBlank()) {
                log.warn("No GitHub token configured — branch pushed but no PR created for session {}",
                        session.getId());
                session.setStatus(ProjectDefinitionStatus.PR_OPEN);
                session.setErrorMessage(
                        "Branch was submitted — create a pull request manually (no GitHub token configured)");
                session.setCompletedAt(LocalDateTime.now());
                sessionRepository.save(session);
                broadcastSimple(session);
                return;
            }

            Map<String, Object> prResult = claudeService.createGitHubPullRequest(
                    repoUrl, branchName,
                    "Add Project Definition",
                    session.getGeneratedContent(),
                    githubToken
            );

            String prUrl = (String) prResult.get("html_url");
            int prNumber = ((Number) prResult.get("number")).intValue();

            session.setPrUrl(prUrl);
            session.setPrNumber(String.valueOf(prNumber));

            if (settings.isAutoMergePr()) {
                boolean merged = claudeService.mergePullRequest(repoUrl, prNumber, githubToken);
                if (merged) {
                    session.setStatus(ProjectDefinitionStatus.COMPLETED);
                } else {
                    session.setStatus(ProjectDefinitionStatus.PR_OPEN);
                    session.setErrorMessage("Auto-merge blocked — manual merge required");
                }
            } else {
                session.setStatus(ProjectDefinitionStatus.PR_OPEN);
            }

            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            broadcastSimple(session);

        } catch (Exception e) {
            log.error("Failed in saveAndPushDefinition for session {}", session.getId(), e);
            markFailed(session.getId(), "Something went wrong while saving the project definition");
        }
    }

    /**
     * Return the most recent session's state, or null if no sessions exist.
     */
    public ProjectDefinitionStateResponse getState() {
        return sessionRepository.findTopByOrderByCreatedAtDesc()
                .map(this::toStateResponse)
                .orElse(null);
    }

    /**
     * Cancel any ACTIVE session by marking it FAILED.
     */
    @Transactional
    public void resetSession() {
        sessionRepository.findFirstByStatusIn(List.of(ProjectDefinitionStatus.ACTIVE))
                .ifPresent(session -> {
                    session.setStatus(ProjectDefinitionStatus.FAILED);
                    session.setErrorMessage("Session was reset");
                    session.setCompletedAt(LocalDateTime.now());
                    sessionRepository.save(session);
                    broadcastSimple(session);
                });
    }

    // ---- Prompt builders ----

    private String buildInterviewPrompt() {
        return "You are helping define a software project through a structured interview. " +
                "Ask the following 9 questions one at a time, in order. " +
                "Wait for the user to answer each question before asking the next one.\n\n" +
                "The 9 questions cover:\n" +
                "1. Project description (what the project is about)\n" +
                "2. Motivation (why it needs to exist)\n" +
                "3. Goals (what the project aims to achieve)\n" +
                "4. Target users — multiple choice: individuals/consumers, small businesses, " +
                "enterprise teams, developers/technical users, internal team only, combination\n" +
                "5. Interaction mode — multiple choice: web application, API/backend service, " +
                "command-line tool, mobile app, background service, combination\n" +
                "6. Project stage — multiple choice: just an idea/concept, early development, " +
                "has an MVP already, in production\n" +
                "7. Top 2-3 features to build first (free text)\n" +
                "8. Technical constraints or preferences (free text)\n" +
                "9. What success looks like in 6 months (free text)\n\n" +
                "For each question respond in JSON:\n" +
                "Open-ended: {\"type\":\"question\",\"questionType\":\"open\"," +
                "\"question\":\"...\",\"options\":[],\"progress\":<0-100>}\n" +
                "Multiple choice: {\"type\":\"question\",\"questionType\":\"multiple_choice\"," +
                "\"question\":\"...\",\"options\":[\"opt1\",\"opt2\",...],\"progress\":<0-100>}\n" +
                "After all 9 answers: {\"type\":\"complete\",\"message\":\"Thank you! " +
                "Your project definition is ready to be compiled.\"}\n\n" +
                "IMPORTANT: Use plain, non-technical language in questions. " +
                "Do not mention programming languages, frameworks, or technical terms. " +
                "Start with question 1 now.";
    }

    private String buildCompilationPrompt() {
        return "Based on our conversation so far, please compile a comprehensive " +
                "PROJECT_DEFINITION.md document that captures everything we discussed " +
                "about this software project.\n\n" +
                "Structure the document with these sections:\n" +
                "# Project Definition\n" +
                "## Overview\n" +
                "## Motivation\n" +
                "## Goals\n" +
                "## Target Users\n" +
                "## How Users Will Interact With It\n" +
                "## Current Stage\n" +
                "## Key Features\n" +
                "## Technical Constraints\n" +
                "## 6-Month Vision\n\n" +
                "Write the complete markdown document. " +
                "Do not include any JSON wrapper or preamble — output only the markdown.";
    }

    // ---- State response builder ----

    ProjectDefinitionStateResponse toStateResponse(ProjectDefinitionSession session) {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        resp.setSessionId(session.getId());
        resp.setStatus(session.getStatus());
        resp.setGeneratedContent(session.getGeneratedContent());
        resp.setPrUrl(session.getPrUrl());
        resp.setErrorMessage(session.getErrorMessage());
        return resp;
    }

    // ---- Broadcast helpers ----

    private void broadcastState(ProjectDefinitionSession session, JsonNode questionNode) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.getId());
        data.put("status", session.getStatus().name());

        if (questionNode != null) {
            data.put("currentQuestion", questionNode.path("question").asText(""));
            data.put("questionType", questionNode.path("questionType").asText("open"));
            data.put("progressPercent", questionNode.path("progress").asInt(0));

            JsonNode optionsNode = questionNode.path("options");
            List<String> options = new ArrayList<>();
            if (optionsNode.isArray()) {
                for (JsonNode opt : optionsNode) {
                    options.add(opt.asText());
                }
            }
            data.put("options", options);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PROJECT_DEFINITION_UPDATE");
        payload.put("data", data);
        broadcastToAdmins(payload);
    }

    void broadcastSimple(ProjectDefinitionSession session) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.getId());
        data.put("status", session.getStatus().name());
        if (session.getPrUrl() != null) {
            data.put("prUrl", session.getPrUrl());
        }
        if (session.getErrorMessage() != null) {
            data.put("errorMessage", session.getErrorMessage());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PROJECT_DEFINITION_UPDATE");
        payload.put("data", data);
        broadcastToAdmins(payload);
    }

    private void broadcastToAdmins(Map<String, Object> payload) {
        List<User> admins = new ArrayList<>();
        admins.addAll(userRepository.findByRole(UserRole.ROOT_ADMIN));
        admins.addAll(userRepository.findByRole(UserRole.ADMIN));
        for (User admin : admins) {
            if (admin.getUsername() != null) {
                notificationHandler.sendNotificationToUser(admin.getUsername(), payload);
            }
        }
    }

    // ---- Failure handling ----

    void markFailed(Long sessionId, String message) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(ProjectDefinitionStatus.FAILED);
            session.setErrorMessage(message);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            broadcastSimple(session);
        });
    }

    // ---- JSON helpers ----

    String appendMessage(String historyJson, String role, String content) {
        try {
            ArrayNode array = (historyJson != null && !historyJson.isBlank())
                    ? (ArrayNode) objectMapper.readTree(historyJson)
                    : objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", content);
            array.add(msg);
            return objectMapper.writeValueAsString(array);
        } catch (Exception e) {
            log.warn("Failed to append message to conversation history: {}", e.getMessage());
            return historyJson != null ? historyJson : "[]";
        }
    }

    String extractJsonBlock(String response) {
        if (response == null) return null;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    // ---- Git helpers (protected for testability) ----

    protected String detectDefaultBranch(String repoDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--verify", "origin/main");
            pb.directory(new File(repoDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            return exitCode == 0 ? "main" : "master";
        } catch (Exception e) {
            log.warn("Could not detect default branch, defaulting to main: {}", e.getMessage());
            return "main";
        }
    }

    protected void runGitCommand(String repoDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("Git {}: {}", command[1], line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Git command failed (" + String.join(" ", command) + "): " + output.toString().trim());
        }
    }
}
