package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.springframework.stereotype.Service;

@Service
public class SiteSettingsService {

    private final SiteSettingsRepository settingsRepository;

    public SiteSettingsService(SiteSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
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
        return settingsRepository.save(current);
    }
}
