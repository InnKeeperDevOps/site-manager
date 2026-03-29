package com.sitemanager.controller;

import com.sitemanager.dto.ProjectDefinitionAnswerRequest;
import com.sitemanager.dto.ProjectDefinitionStateResponse;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.ProjectDefinitionService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/project-definition")
public class ProjectDefinitionController {

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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }
}
