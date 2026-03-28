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

import java.net.http.HttpTimeoutException;
import java.util.*;
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

        SiteSettings settings = settingsService.getSettings();
        List<Suggestion> suggestions = suggestionRepository.findAllByOrderByCreatedAtDesc();

        Map<SuggestionStatus, Long> statusCounts = suggestions.stream()
                .collect(Collectors.groupingBy(Suggestion::getStatus, Collectors.counting()));

        String prompt = buildPrompt(settings, statusCounts, suggestions.size());

        try {
            String rawResponse = claudeService.getRecommendations(prompt);
            List<Map<String, String>> recommendations = parseRecommendations(rawResponse);
            return ResponseEntity.ok(recommendations);
        } catch (HttpTimeoutException e) {
            log.warn("[RECOMMENDATIONS] Request timed out: {}", e.getMessage());
            return ResponseEntity.status(504).body(Map.of(
                    "error", "The AI took too long to respond. Please try again in a moment."
            ));
        } catch (IllegalStateException e) {
            log.warn("[RECOMMENDATIONS] Failed to parse response: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "error", "The AI returned an unexpected response. Please try again."
            ));
        } catch (Exception e) {
            log.error("[RECOMMENDATIONS] Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unable to get recommendations right now. Please try again."
            ));
        }
    }

    String buildPrompt(SiteSettings settings, Map<SuggestionStatus, Long> statusCounts, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a product advisor helping improve a website called: ")
                .append(settings.getSiteName() != null ? settings.getSiteName() : "Site Suggestion Platform")
                .append("\n");
        if (settings.getTargetRepoUrl() != null && !settings.getTargetRepoUrl().isBlank()) {
            sb.append("Repository: ").append(settings.getTargetRepoUrl()).append("\n");
        }
        sb.append("\nCurrent suggestion statistics (total: ").append(total).append("):\n");
        statusCounts.forEach((status, count) ->
                sb.append("  ").append(status.name()).append(": ").append(count).append("\n"));
        sb.append("\nFeature configuration:\n");
        sb.append("  Anonymous suggestions: ")
                .append(settings.isAllowAnonymousSuggestions() ? "enabled" : "disabled").append("\n");
        sb.append("  Voting: ").append(settings.isAllowVoting() ? "enabled" : "disabled").append("\n");
        sb.append("  Require admin approval: ").append(settings.isRequireApproval() ? "yes" : "no").append("\n");
        sb.append("  Auto-merge pull requests: ").append(settings.isAutoMergePr() ? "yes" : "no").append("\n");
        sb.append("\nBased on this information, suggest exactly 5 concrete improvements to the site or workflow. ");
        sb.append("Each suggestion should be specific, actionable, and valuable to the team.\n\n");
        sb.append("Respond ONLY with a valid JSON array of exactly 5 objects. ");
        sb.append("Each object must have a \"title\" (short, under 100 characters) and a \"description\" (1-2 sentences). ");
        sb.append("Do not include any text before or after the JSON array.\n");
        sb.append("Example: [{\"title\": \"Add email notifications\", ");
        sb.append("\"description\": \"Send email alerts when suggestions change status.\"}]");
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
}
