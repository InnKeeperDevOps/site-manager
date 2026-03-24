package com.sitemanager.controller;

import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.model.User;
import com.sitemanager.service.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        boolean setupRequired = authService.isSetupRequired();
        String currentUser = (String) session.getAttribute("username");
        String role = (String) session.getAttribute("role");

        return ResponseEntity.ok(Map.of(
                "setupRequired", setupRequired,
                "loggedIn", currentUser != null,
                "username", currentUser != null ? currentUser : "",
                "role", role != null ? role : ""
        ));
    }

    @PostMapping("/setup")
    public ResponseEntity<?> setup(@Valid @RequestBody SetupRequest request, HttpSession session) {
        if (!authService.isSetupRequired()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Root admin already exists"));
        }

        User admin = authService.setupRootAdmin(request);
        session.setAttribute("username", admin.getUsername());
        session.setAttribute("role", admin.getRole().name());
        session.setAttribute("userId", admin.getId());

        return ResponseEntity.ok(Map.of(
                "message", "Root admin created successfully",
                "username", admin.getUsername(),
                "role", admin.getRole().name()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        return authService.authenticate(request)
                .map(user -> {
                    session.setAttribute("username", user.getUsername());
                    session.setAttribute("role", user.getRole().name());
                    session.setAttribute("userId", user.getId());
                    return ResponseEntity.ok(Map.of(
                            "message", "Login successful",
                            "username", user.getUsername(),
                            "role", user.getRole().name()
                    ));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @PostMapping("/create-admin")
    public ResponseEntity<?> createAdmin(@Valid @RequestBody SetupRequest request, HttpSession session) {
        String role = (String) session.getAttribute("role");
        if (!"ROOT_ADMIN".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Only root admin can create admins"));
        }
        try {
            User admin = authService.createAdmin(request.getUsername(), request.getPassword());
            return ResponseEntity.ok(Map.of(
                    "message", "Admin created",
                    "username", admin.getUsername()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
