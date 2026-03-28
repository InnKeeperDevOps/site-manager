package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class SlackNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationService.class);
    private static final String ALLOWED_WEBHOOK_PREFIX = "https://hooks.slack.com/services/";

    private static final Map<SuggestionStatus, String> STATUS_EMOJI = Map.ofEntries(
            Map.entry(SuggestionStatus.DRAFT, ":pencil:"),
            Map.entry(SuggestionStatus.DISCUSSING, ":speech_balloon:"),
            Map.entry(SuggestionStatus.EXPERT_REVIEW, ":mag:"),
            Map.entry(SuggestionStatus.PLANNED, ":calendar:"),
            Map.entry(SuggestionStatus.APPROVED, ":white_check_mark:"),
            Map.entry(SuggestionStatus.IN_PROGRESS, ":hammer_and_wrench:"),
            Map.entry(SuggestionStatus.TESTING, ":test_tube:"),
            Map.entry(SuggestionStatus.DEV_COMPLETE, ":rocket:"),
            Map.entry(SuggestionStatus.FINAL_REVIEW, ":eyes:"),
            Map.entry(SuggestionStatus.DENIED, ":x:"),
            Map.entry(SuggestionStatus.TIMED_OUT, ":hourglass_flowing_sand:")
    );

    private final SiteSettingsService siteSettingsService;
    private final HttpClient httpClient;

    @Autowired
    public SlackNotificationService(SiteSettingsService siteSettingsService) {
        this(siteSettingsService, HttpClient.newHttpClient());
    }

    SlackNotificationService(SiteSettingsService siteSettingsService, HttpClient httpClient) {
        this.siteSettingsService = siteSettingsService;
        this.httpClient = httpClient;
    }

    public CompletableFuture<Void> sendNotification(Suggestion suggestion, String eventLabel) {
        return CompletableFuture.runAsync(() -> doSend(suggestion, eventLabel));
    }

    private void doSend(Suggestion suggestion, String eventLabel) {
        String webhookUrl = siteSettingsService.getSettings().getSlackWebhookUrl();

        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        if (!webhookUrl.startsWith(ALLOWED_WEBHOOK_PREFIX)) {
            log.warn("Slack webhook URL does not start with '{}' — skipping notification to prevent SSRF", ALLOWED_WEBHOOK_PREFIX);
            return;
        }

        String payload = buildPayload(suggestion, eventLabel);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Slack webhook returned non-success status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Failed to send Slack notification for suggestion {}: {}", suggestion.getId(), e.getMessage());
        }
    }

    String buildPayload(Suggestion suggestion, String eventLabel) {
        String emoji = STATUS_EMOJI.getOrDefault(suggestion.getStatus(), ":bell:");
        String title = escapeJson(suggestion.getTitle());
        String description = suggestion.getDescription() != null ? suggestion.getDescription() : "";
        String truncatedDescription = description.length() > 100
                ? description.substring(0, 100) + "..."
                : description;
        String author = suggestion.getAuthorName() != null ? escapeJson(suggestion.getAuthorName()) : "Unknown";
        String label = escapeJson(eventLabel);

        StringBuilder blocks = new StringBuilder();
        blocks.append("{\"blocks\":[");

        // Header block
        blocks.append("{\"type\":\"header\",\"text\":{\"type\":\"plain_text\",\"text\":")
              .append("\"").append(emoji).append(" ").append(title).append("\"")
              .append(",\"emoji\":true}},");

        // Section with event label and author
        blocks.append("{\"type\":\"section\",\"fields\":[")
              .append("{\"type\":\"mrkdwn\",\"text\":\"*Status Update:*\\n").append(label).append("\"},")
              .append("{\"type\":\"mrkdwn\",\"text\":\"*Submitted by:*\\n").append(author).append("\"}")
              .append("]},");

        // Description context block
        blocks.append("{\"type\":\"context\",\"elements\":[")
              .append("{\"type\":\"mrkdwn\",\"text\":\"").append(escapeJson(truncatedDescription)).append("\"}")
              .append("]}");

        // PR link section if available
        if (suggestion.getPrUrl() != null && !suggestion.getPrUrl().isBlank()) {
            blocks.append(",{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"*Pull Request:* <")
                  .append(escapeJson(suggestion.getPrUrl())).append("|View PR #").append(suggestion.getPrNumber()).append(">\"}}");
        }

        blocks.append("]}");
        return blocks.toString();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    String getAllowedWebhookPrefix() {
        return ALLOWED_WEBHOOK_PREFIX;
    }
}
