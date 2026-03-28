package com.sitemanager.controller;

import com.sitemanager.dto.GroupDto;
import com.sitemanager.dto.GroupRequest;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.UserGroupService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final UserGroupService userGroupService;
    private final PermissionService permissionService;

    public GroupController(UserGroupService userGroupService, PermissionService permissionService) {
        this.userGroupService = userGroupService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<?> listGroups(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        List<GroupDto> groups = userGroupService.findAll().stream()
                .map(GroupDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(groups);
    }

    @PostMapping
    public ResponseEntity<?> createGroup(@Valid @RequestBody GroupRequest request, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        try {
            UserGroup group = userGroupService.create(request);
            return ResponseEntity.ok(GroupDto.from(group));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGroup(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        return userGroupService.findById(id)
                .map(group -> ResponseEntity.ok(GroupDto.from(group)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateGroup(@PathVariable Long id, @Valid @RequestBody GroupRequest request,
                                         HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        try {
            UserGroup group = userGroupService.update(id, request);
            return ResponseEntity.ok(GroupDto.from(group));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        try {
            userGroupService.delete(id);
            return ResponseEntity.ok(Map.of("message", "Group deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
