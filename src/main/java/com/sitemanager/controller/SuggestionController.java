package com.sitemanager.controller;

import com.sitemanager.dto.ClarificationRequest;
import com.sitemanager.dto.MessageRequest;
import com.sitemanager.dto.SuggestionRequest;
import com.sitemanager.dto.VoteRequest;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.service.SiteSettingsService;
import com.sitemanager.service.SuggestionService;
import com.sitemanager.service.VoteService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/suggestions")
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final VoteService voteService;
    private final SiteSettingsService settingsService;

    public SuggestionController(SuggestionService suggestionService, VoteService voteService,
                                SiteSettingsService settingsService) {
        this.suggestionService = suggestionService;
        this.voteService = voteService;
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<List<Suggestion>> list() {
        return ResponseEntity.ok(suggestionService.getAllSuggestions());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return suggestionService.getSuggestion(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<SuggestionMessage>> getMessages(@PathVariable Long id) {
        return ResponseEntity.ok(suggestionService.getMessages(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody SuggestionRequest request, HttpSession session) {
        String username = (String) session.getAttribute("username");
        Long userId = (Long) session.getAttribute("userId");

        // Check if anonymous suggestions are allowed
        if (username == null && !settingsService.getSettings().isAllowAnonymousSuggestions()) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Anonymous suggestions are not allowed. Please log in."));
        }

        String authorName = username != null ? username :
                (request.getAuthorName() != null ? request.getAuthorName() : "Anonymous");

        Suggestion suggestion = suggestionService.createSuggestion(
                request.getTitle(),
                request.getDescription(),
                userId,
                authorName
        );

        return ResponseEntity.ok(suggestion);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> reply(@PathVariable Long id, @Valid @RequestBody MessageRequest request,
                                   HttpSession session) {
        String username = (String) session.getAttribute("username");
        String senderName = username != null ? username :
                (request.getSenderName() != null ? request.getSenderName() : "Anonymous");

        suggestionService.handleUserReply(id, senderName, request.getContent());
        return ResponseEntity.ok(Map.of("message", "Reply sent"));
    }

    @PostMapping("/{id}/clarifications")
    public ResponseEntity<?> submitClarifications(@PathVariable Long id,
                                                   @Valid @RequestBody ClarificationRequest request,
                                                   HttpSession session) {
        String username = (String) session.getAttribute("username");
        String senderName = username != null ? username :
                (request.getSenderName() != null ? request.getSenderName() : "Anonymous");

        suggestionService.handleClarificationAnswers(id, senderName, request.getAnswers());
        return ResponseEntity.ok(Map.of("message", "Clarification answers submitted"));
    }

    @GetMapping("/{id}/clarification-questions")
    public ResponseEntity<?> getClarificationQuestions(@PathVariable Long id) {
        java.util.List<String> questions = suggestionService.getPendingQuestions(id);
        if (questions == null || questions.isEmpty()) {
            return ResponseEntity.ok(Map.of("questions", java.util.List.of(), "hasPending", false));
        }
        return ResponseEntity.ok(Map.of("questions", questions, "hasPending", true));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, HttpSession session) {
        String role = (String) session.getAttribute("role");
        if (!isAdmin(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(suggestionService.approveSuggestion(id));
    }

    @PostMapping("/{id}/deny")
    public ResponseEntity<?> deny(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                  HttpSession session) {
        String role = (String) session.getAttribute("role");
        if (!isAdmin(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(suggestionService.denySuggestion(id, reason));
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<?> vote(@PathVariable Long id, @Valid @RequestBody VoteRequest request,
                                  HttpSession session) {
        String username = (String) session.getAttribute("username");
        String voter = username != null ? username :
                (request.getVoterIdentifier() != null ? request.getVoterIdentifier() :
                        session.getId());
        try {
            Suggestion updated = voteService.vote(id, voter, request.getValue());
            return ResponseEntity.ok(Map.of(
                    "upVotes", updated.getUpVotes(),
                    "downVotes", updated.getDownVotes()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdmin(String role) {
        return "ROOT_ADMIN".equals(role) || "ADMIN".equals(role);
    }
}
