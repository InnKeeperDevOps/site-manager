package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SiteSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SiteSettingsService.class);

    private final SiteSettingsRepository settingsRepository;
    private final ClaudeService claudeService;

    public SiteSettingsService(SiteSettingsRepository settingsRepository, ClaudeService claudeService) {
        this.settingsRepository = settingsRepository;
        this.claudeService = claudeService;
    }

    public SiteSettings getSettings() {
        return settingsRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> settingsRepository.save(new SiteSettings()));
    }

    public SiteSettings updateSettings(SiteSettings updated) {
        SiteSettings current = getSettings();
        current.setAllowAnonymousSuggestions(updated.isAllowAnonymousSuggestions());
        current.setAllowVoting(updated.isAllowVoting());
        current.setTargetRepoUrl(updated.getTargetRepoUrl());
        current.setSuggestionTimeoutMinutes(updated.getSuggestionTimeoutMinutes());
        current.setRequireApproval(updated.isRequireApproval());
        current.setSiteName(updated.getSiteName());
        SiteSettings saved = settingsRepository.save(current);

        // Re-clone the target repository into main-repo/ so files are up to date
        String repoUrl = saved.getTargetRepoUrl();
        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                log.info("Settings updated, re-cloning repository: {}", repoUrl);
                claudeService.cloneMainRepository(repoUrl);
            } catch (Exception e) {
                log.error("Failed to re-clone repository after settings update: {}", e.getMessage(), e);
            }
        }

        return saved;
    }
}
