package com.sitemanager.controller;

import com.sitemanager.dto.ProjectDefinitionAnswerRequest;
import com.sitemanager.dto.ProjectDefinitionStateResponse;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.ProjectDefinitionService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/project-definition")
public class ProjectDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(ProjectDefinitionController.class);

    private final ProjectDefinitionService projectDefinitionService;
    private final PermissionService permissionService;

    public ProjectDefinitionController(ProjectDefinitionService projectDefinitionService,
                                       PermissionService permissionService) {
        this.projectDefinitionService = projectDefinitionService;
        this.permissionService = permissionService;
    }

    @PostMapping("/start")
    public ResponseEntity<?> startSession(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        ProjectDefinitionStateResponse state = projectDefinitionService.startSession();
        return ResponseEntity.ok(state);
    }

    @PostMapping("/{id}/answer")
    public ResponseEntity<?> submitAnswer(@PathVariable Long id,
                                          @Valid @RequestBody ProjectDefinitionAnswerRequest request,
                                          HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        ProjectDefinitionStateResponse state = projectDefinitionService.submitAnswer(id, request.getAnswer());
        return ResponseEntity.ok(state);
    }

    @GetMapping("/state")
    public ResponseEntity<?> getState(HttpSession session) {
        if (session.getAttribute("role") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        ProjectDefinitionStateResponse state = projectDefinitionService.getState();
        if (state == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(state);
    }

    @PostMapping("/reset")
    public ResponseEntity<?> resetSession(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        projectDefinitionService.resetSession();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String content = projectDefinitionService.readExistingProjectDefinition();
        if (content == null) {
            return ResponseEntity.status(404).body(Map.of("error", "No project definition found"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"PROJECT_DEFINITION.md\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/import")
    public ResponseEntity<?> importDefinition(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "content", required = false) String content,
            HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        String definitionContent = null;
        try {
            if (file != null && !file.isEmpty()) {
                definitionContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            } else if (content != null && !content.isBlank()) {
                definitionContent = content;
            }
        } catch (Exception e) {
            log.error("[PROJECT-DEFINITION] Error reading uploaded file: {}", e.getMessage(), e);
            return ResponseEntity.status(400).body(Map.of("error", "Could not read the uploaded file"));
        }

        if (definitionContent == null || definitionContent.isBlank()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "No content provided. Upload a file or paste the definition text."));
        }

        try {
            projectDefinitionService.importProjectDefinition(definitionContent);
            return ResponseEntity.ok(Map.of("message", "Project definition imported successfully"));
        } catch (Exception e) {
            log.error("[PROJECT-DEFINITION] Import failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to import project definition: " + e.getMessage()));
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }
}
