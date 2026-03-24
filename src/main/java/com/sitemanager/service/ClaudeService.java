package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Service
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();

    @Value("${app.claude-cli-path:claude}")
    private String claudeCliPath;

    @Value("${app.workspace-dir:/workspace}")
    private String workspaceDir;

    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public CompletableFuture<String> evaluateSuggestion(String suggestionTitle, String suggestionDescription,
                                                         String repoUrl, String sessionId,
                                                         Consumer<String> progressCallback) {
        String prompt = String.format(
                "You are evaluating a site suggestion for a repository at: %s\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "Please evaluate this suggestion:\n" +
                "1. Is this suggestion detailed enough to implement? Does it clearly describe what changes are needed?\n" +
                "2. If NOT detailed enough, ask specific clarifying questions to the user.\n" +
                "3. If it IS detailed enough, look at the repository and create a concrete implementation plan.\n\n" +
                "Respond in this JSON format:\n" +
                "If clarification needed:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", " +
                "\"message\": \"brief summary of what you need to know\", " +
                "\"questions\": [\"specific question 1\", \"specific question 2\", ...]}\n\n" +
                "If ready to plan:\n" +
                "{\"status\": \"PLAN_READY\", " +
                "\"message\": \"your response to the user\", " +
                "\"plan\": \"implementation plan\"}\n\n" +
                "IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array with each clarifying question as a separate string element. Each question should be self-contained and specific.",
                repoUrl != null ? repoUrl : "not configured",
                suggestionTitle,
                suggestionDescription
        );

        return sendToClaudeAsync(prompt, sessionId, null, progressCallback);
    }

    public CompletableFuture<String> continueConversation(String sessionId, String userMessage,
                                                           Consumer<String> progressCallback) {
        return sendToClaudeAsync(userMessage, sessionId, null, progressCallback);
    }

    public CompletableFuture<String> executePlan(String sessionId, String plan, String workingDir,
                                                  Consumer<String> progressCallback) {
        String prompt = String.format(
                "Execute the following implementation plan in the repository at %s.\n\n" +
                "Plan:\n%s\n\n" +
                "Instructions:\n" +
                "1. Implement each step of the plan\n" +
                "2. Write unit tests for all new code\n" +
                "3. Run existing tests to ensure nothing is broken\n" +
                "4. Provide progress updates as you work\n" +
                "5. Respond in JSON format for each phase:\n" +
                "{\"phase\": \"description\", \"status\": \"IN_PROGRESS\" or \"COMPLETED\" or \"FAILED\", " +
                "\"message\": \"details\", \"testsRun\": number, \"testsPassed\": number}",
                workingDir, plan
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, progressCallback);
    }

    private CompletableFuture<String> sendToClaudeAsync(String prompt, String sessionId,
                                                         String workingDir,
                                                         Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendToClaude(prompt, sessionId, workingDir, progressCallback);
            } catch (Exception e) {
                log.error("Claude CLI error for session {}: {}", sessionId, e.getMessage(), e);
                return "{\"status\": \"ERROR\", \"message\": \"" +
                        e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }, executor);
    }

    private String sendToClaude(String prompt, String sessionId, String workingDir,
                                 Consumer<String> progressCallback) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();

        boolean isResume = sessionMap.containsKey(sessionId);

        if (isResume) {
            pb.command(claudeCliPath, "--resume", sessionMap.get(sessionId),
                    "-p", prompt, "--output-format", "text", "--verbose");
        } else {
            pb.command(claudeCliPath, "-p", prompt, "--output-format", "text", "--verbose");
        }

        if (workingDir != null) {
            pb.directory(new File(workingDir));
        }

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (progressCallback != null) {
                    progressCallback.accept(line);
                }
            }
        }

        int exitCode = process.waitFor();
        String result = output.toString().trim();

        if (!isResume) {
            // Store the session for future continuation
            // The actual session ID from Claude would be extracted from verbose output
            // For now, use a mapping approach
            sessionMap.put(sessionId, sessionId);
        }

        if (exitCode != 0) {
            log.warn("Claude CLI exited with code {} for session {}", exitCode, sessionId);
        }

        return result;
    }

    public String cloneRepository(String repoUrl, String suggestionId) throws Exception {
        String targetDir = workspaceDir + "/suggestion-" + suggestionId;
        File dir = new File(targetDir);
        if (dir.exists()) {
            // Clean and re-clone
            ProcessBuilder cleanup = new ProcessBuilder("rm", "-rf", targetDir);
            cleanup.start().waitFor();
        }

        ProcessBuilder pb = new ProcessBuilder("git", "clone", repoUrl, targetDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Git clone: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to clone repository: exit code " + exitCode);
        }

        return targetDir;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
