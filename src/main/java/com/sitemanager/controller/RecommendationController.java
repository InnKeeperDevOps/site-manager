package com.sitemanager.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.SiteSettingsService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

    private final PermissionService permissionService;
    private final SiteSettingsService settingsService;
    private final SuggestionRepository suggestionRepository;
    private final ClaudeService claudeService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, TaskResult> tasks = new ConcurrentHashMap<>();

    public RecommendationController(PermissionService permissionService,
                                    SiteSettingsService settingsService,
                                    SuggestionRepository suggestionRepository,
                                    ClaudeService claudeService,
                                    ObjectMapper objectMapper) {
        this.permissionService = permissionService;
        this.settingsService = settingsService;
        this.suggestionRepository = suggestionRepository;
        this.claudeService = claudeService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> getRecommendations(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new TaskResult("pending", null, null));

        SiteSettings settings = settingsService.getSettings();
        List<Suggestion> suggestions = suggestionRepository.findAllByOrderByCreatedAtDesc();
        Map<SuggestionStatus, Long> statusCounts = suggestions.stream()
                .collect(Collectors.groupingBy(Suggestion::getStatus, Collectors.counting()));
        int total = suggestions.size();

        executor.submit(() -> {
            try {
                log.info("[RECOMMENDATIONS] Starting recommendation request (taskId={})", taskId);
                ensureMainRepoAvailable(settings);
                String projectDefinition = readProjectDefinition();
                String prompt = buildPrompt(settings, statusCounts, total, projectDefinition);
                log.info("[RECOMMENDATIONS] Sending prompt to Claude ({} total suggestions)", total);
                String rawResponse = claudeService.getRecommendations(prompt);
                List<Map<String, String>> recommendations = parseRecommendations(rawResponse);
                log.info("[RECOMMENDATIONS] Completed successfully with {} recommendations (taskId={})", recommendations.size(), taskId);
                tasks.put(taskId, new TaskResult("done", recommendations, null));
            } catch (IllegalStateException e) {
                log.warn("[RECOMMENDATIONS] Failed to parse response: {}", e.getMessage());
                tasks.put(taskId, new TaskResult("error", null,
                        "The AI returned an unexpected response. Please try again."));
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    log.warn("[RECOMMENDATIONS] Request timed out: {}", e.getMessage());
                    tasks.put(taskId, new TaskResult("error", null,
                            "The AI took too long to respond. Please try again in a moment."));
                } else {
                    log.error("[RECOMMENDATIONS] Unexpected error: {}", e.getMessage(), e);
                    tasks.put(taskId, new TaskResult("error", null,
                            "Unable to get recommendations right now. Please try again."));
                }
            } catch (Exception e) {
                log.error("[RECOMMENDATIONS] Unexpected error: {}", e.getMessage(), e);
                tasks.put(taskId, new TaskResult("error", null,
                        "Unable to get recommendations right now. Please try again."));
            }
        });

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> getStatus(@PathVariable String taskId) {
        TaskResult result = tasks.get(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        if ("pending".equals(result.status)) {
            return ResponseEntity.ok(Map.of("status", "pending"));
        }
        // Clean up completed task
        tasks.remove(taskId);
        if ("error".equals(result.status)) {
            return ResponseEntity.ok(Map.of("status", "error", "error", result.error));
        }
        return ResponseEntity.ok(Map.of("status", "done", "data", result.data));
    }

    /**
     * Pull or clone the main repository so Claude has up-to-date source to analyze.
     */
    private void ensureMainRepoAvailable(SiteSettings settings) {
        String repoUrl = settings.getTargetRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return;
        }
        try {
            claudeService.pullMainRepository(repoUrl);
        } catch (Exception e) {
            log.warn("[RECOMMENDATIONS] Could not update main-repo (will use existing state): {}", e.getMessage());
        }
    }

    /**
     * Read PROJECT_DEFINITION.md from the main-repo directory, if it exists.
     */
    String readProjectDefinition() {
        try {
            java.nio.file.Path filePath = Paths.get(claudeService.getMainRepoDir(), "PROJECT_DEFINITION.md");
            return Files.readString(filePath);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            log.warn("[RECOMMENDATIONS] Could not read PROJECT_DEFINITION.md: {}", e.getMessage());
            return null;
        }
    }

    String buildPrompt(SiteSettings settings, Map<SuggestionStatus, Long> statusCounts,
                        int total, String projectDefinition) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a technical advisor analyzing a software project to identify gaps between ");
        sb.append("the intended vision and the current implementation.\n\n");

        sb.append("Project: ")
                .append(settings.getSiteName() != null ? settings.getSiteName() : "Software Project")
                .append("\n");
        if (settings.getTargetRepoUrl() != null && !settings.getTargetRepoUrl().isBlank()) {
            sb.append("Repository: ").append(settings.getTargetRepoUrl()).append("\n");
        }

        if (projectDefinition != null && !projectDefinition.isBlank()) {
            sb.append("\n=== PROJECT DEFINITION (the intended vision) ===\n");
            sb.append(projectDefinition);
            sb.append("\n=== END PROJECT DEFINITION ===\n");
        }

        sb.append("\nCurrent suggestion statistics (total: ").append(total).append("):\n");
        statusCounts.forEach((status, count) ->
                sb.append("  ").append(status.name()).append(": ").append(count).append("\n"));

        sb.append("\nINSTRUCTIONS:\n");
        sb.append("You are running in the project's repository directory. ");
        sb.append("Explore the codebase — read key files, understand the architecture, ");
        sb.append("and examine what has been implemented so far.\n\n");

        if (projectDefinition != null && !projectDefinition.isBlank()) {
            sb.append("Compare the PROJECT DEFINITION above against the ACTUAL implementation in the repository. ");
            sb.append("Identify the most important gaps — features described in the definition that are ");
            sb.append("missing, incomplete, or differ significantly from what was envisioned.\n\n");
            sb.append("Focus on:\n");
            sb.append("- Features or capabilities described in the definition that are not yet implemented\n");
            sb.append("- Features that are partially implemented but missing key aspects\n");
            sb.append("- Architectural or design discrepancies between vision and reality\n");
            sb.append("- Quality gaps (e.g. missing tests, error handling, performance concerns mentioned in the definition)\n\n");
        } else {
            sb.append("There is no PROJECT_DEFINITION.md yet. Analyze the codebase and suggest the most ");
            sb.append("impactful improvements based on what you find in the code.\n\n");
            sb.append("Focus on:\n");
            sb.append("- Missing features that would make the project more complete\n");
            sb.append("- Code quality improvements (tests, error handling, documentation)\n");
            sb.append("- Architectural improvements\n");
            sb.append("- User experience enhancements\n\n");
        }

        sb.append("Suggest exactly 5 concrete, actionable improvements ranked by impact.\n\n");
        sb.append("Respond ONLY with a valid JSON array of exactly 5 objects. ");
        sb.append("Each object must have a \"title\" (short, under 100 characters) and a \"description\" (1-2 sentences). ");
        sb.append("Do not include any text before or after the JSON array.\n");
        sb.append("Example: [{\"title\": \"Add email notifications\", ");
        sb.append("\"description\": \"The project definition calls for email alerts on status changes, but no notification system exists in the codebase.\"}]");
        return sb.toString();
    }

    List<Map<String, String>> parseRecommendations(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("Empty response from AI");
        }
        try {
            int start = rawResponse.indexOf('[');
            int end = rawResponse.lastIndexOf(']');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("No JSON array found in response");
            }
            String jsonArray = rawResponse.substring(start, end + 1);
            JsonNode arr = objectMapper.readTree(jsonArray);
            if (!arr.isArray() || arr.size() == 0) {
                throw new IllegalArgumentException("Empty or non-array JSON in response");
            }
            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode node : arr) {
                String title = node.path("title").asText("").trim();
                String description = node.path("description").asText("").trim();
                if (!title.isEmpty()) {
                    result.add(Map.of("title", title, "description", description));
                }
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("No valid recommendations in parsed response");
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Malformed response from AI: " + e.getMessage(), e);
        }
    }

    private static class TaskResult {
        final String status;
        final Object data;
        final String error;

        TaskResult(String status, Object data, String error) {
            this.status = status;
            this.data = data;
            this.error = error;
        }
    }
}
