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

        return sendToClaudeAsync(prompt, sessionId, null, null, progressCallback);
    }

    public CompletableFuture<String> continueConversation(String sessionId, String userMessage,
                                                           String conversationContext,
                                                           Consumer<String> progressCallback) {
        return sendToClaudeAsync(userMessage, sessionId, null, conversationContext, progressCallback);
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

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback);
    }

    private CompletableFuture<String> sendToClaudeAsync(String prompt, String sessionId,
                                                         String workingDir, String conversationContext,
                                                         Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendToClaude(prompt, sessionId, workingDir, conversationContext, progressCallback);
            } catch (Exception e) {
                log.error("Claude CLI error for session {}: {}", sessionId, e.getMessage(), e);
                return "{\"status\": \"ERROR\", \"message\": \"" +
                        e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }, executor);
    }

    private String sendToClaude(String prompt, String sessionId, String workingDir,
                                 String conversationContext,
                                 Consumer<String> progressCallback) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();

        String cliSessionId = sessionMap.get(sessionId);
        boolean isResume = cliSessionId != null;

        if (isResume) {
            pb.command(claudeCliPath, "--resume", cliSessionId,
                    "-p", prompt, "--output-format", "json");
        } else {
            pb.command(claudeCliPath, "-p", prompt, "--output-format", "json");
        }

        if (workingDir != null) {
            pb.directory(new File(workingDir));
        }

        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
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
        String rawOutput = output.toString().trim();

        // Try to parse as JSON to extract session_id and result
        String resultText = rawOutput;
        String parsedCliSessionId = null;

        try {
            String jsonStr = extractJsonObject(rawOutput);
            if (jsonStr != null) {
                JsonNode root = objectMapper.readTree(jsonStr);

                // Extract the CLI session ID for future --resume calls
                if (root.has("session_id")) {
                    parsedCliSessionId = root.get("session_id").asText();
                }

                // Extract the actual result text
                if (root.has("result")) {
                    resultText = root.get("result").asText();
                }

                // Check for error responses (e.g., dead session)
                if (root.has("is_error") && root.get("is_error").asBoolean()) {
                    String errorMsg = root.has("result") ? root.get("result").asText() : rawOutput;
                    if (isResume && isDeadSessionError(errorMsg)) {
                        return handleDeadSession(prompt, sessionId, workingDir,
                                conversationContext, progressCallback);
                    }
                    log.warn("Claude CLI returned error for session {}: {}", sessionId, errorMsg);
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse Claude CLI output as JSON: {}", e.getMessage());
        }

        // Fallback: check raw output for dead session error
        if (isResume && isDeadSessionError(rawOutput)) {
            return handleDeadSession(prompt, sessionId, workingDir,
                    conversationContext, progressCallback);
        }

        // Store the CLI session ID for future --resume calls
        if (parsedCliSessionId != null && !parsedCliSessionId.isBlank()) {
            sessionMap.put(sessionId, parsedCliSessionId);
        }

        if (exitCode != 0) {
            log.warn("Claude CLI exited with code {} for session {}", exitCode, sessionId);
        }

        return resultText;
    }

    private boolean isDeadSessionError(String output) {
        return output != null && (
                output.contains("No conversation found") ||
                output.contains("session not found") ||
                output.contains("Session not found"));
    }

    private String handleDeadSession(String prompt, String sessionId, String workingDir,
                                      String conversationContext,
                                      Consumer<String> progressCallback) throws Exception {
        log.warn("Session {} is dead, starting fresh session with conversation context", sessionId);
        sessionMap.remove(sessionId);

        String rebuiltPrompt;
        if (conversationContext != null && !conversationContext.isBlank()) {
            rebuiltPrompt = "Here is the prior conversation history for context. " +
                    "Continue naturally from where the conversation left off.\n\n" +
                    "--- CONVERSATION HISTORY ---\n" +
                    conversationContext +
                    "\n--- END HISTORY ---\n\n" +
                    "Now, the user says:\n\n" + prompt;
        } else {
            rebuiltPrompt = prompt;
        }

        // Retry with a fresh session (no resume, no context to avoid infinite loop)
        return sendToClaude(rebuiltPrompt, sessionId, workingDir, null, progressCallback);
    }

    /**
     * Extract the outermost JSON object from output that may contain
     * non-JSON lines (warnings, verbose output, etc.) before or after the JSON.
     */
    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return raw.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
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
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
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
