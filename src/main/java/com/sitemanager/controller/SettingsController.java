package com.sitemanager.controller;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.service.SiteSettingsService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SiteSettingsService settingsService;

    public SettingsController(SiteSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<SiteSettings> get() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody SiteSettings settings, HttpSession session) {
        String role = (String) session.getAttribute("role");
        if (!"ROOT_ADMIN".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(settingsService.updateSettings(settings));
    }
}
