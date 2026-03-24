package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SiteSettingsServiceTest {

    @Autowired
    private SiteSettingsService settingsService;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @BeforeEach
    void setUp() {
        settingsRepository.deleteAll();
    }

    @Test
    void getSettings_createsDefaultWhenNoneExist() {
        SiteSettings settings = settingsService.getSettings();

        assertNotNull(settings);
        assertTrue(settings.isAllowAnonymousSuggestions());
        assertTrue(settings.isAllowVoting());
        assertEquals(1440, settings.getSuggestionTimeoutMinutes());
    }

    @Test
    void getSettings_returnsSameInstance() {
        SiteSettings first = settingsService.getSettings();
        SiteSettings second = settingsService.getSettings();

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void updateSettings_updatesValues() {
        SiteSettings update = new SiteSettings();
        update.setAllowAnonymousSuggestions(false);
        update.setAllowVoting(false);
        update.setTargetRepoUrl("https://github.com/test/repo.git");
        update.setSuggestionTimeoutMinutes(720);
        update.setSiteName("My Site");
        update.setRequireApproval(false);

        SiteSettings result = settingsService.updateSettings(update);

        assertFalse(result.isAllowAnonymousSuggestions());
        assertFalse(result.isAllowVoting());
        assertEquals("https://github.com/test/repo.git", result.getTargetRepoUrl());
        assertEquals(720, result.getSuggestionTimeoutMinutes());
        assertEquals("My Site", result.getSiteName());
        assertFalse(result.isRequireApproval());
    }
}
