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

    @Test
    void updateSettings_slackWebhookUrl_savedAndRetrieved() {
        SiteSettings update = new SiteSettings();
        update.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals("https://hooks.slack.com/services/T00/B00/xxx", result.getSlackWebhookUrl());
    }

    @Test
    void updateSettings_slackWebhookUrl_clearedWhenSetToNull() {
        SiteSettings withUrl = new SiteSettings();
        withUrl.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        settingsService.updateSettings(withUrl);

        SiteSettings clearUrl = new SiteSettings();
        clearUrl.setSlackWebhookUrl(null);
        SiteSettings result = settingsService.updateSettings(clearUrl);

        assertNull(result.getSlackWebhookUrl());
    }

    @Test
    void getSettings_slackWebhookUrl_nullByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertNull(settings.getSlackWebhookUrl());
    }

    @Test
    void getSettings_autoMergePr_falseByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertFalse(settings.isAutoMergePr());
    }

    @Test
    void updateSettings_autoMergePr_canBeEnabled() {
        SiteSettings update = new SiteSettings();
        update.setAutoMergePr(true);

        SiteSettings result = settingsService.updateSettings(update);

        assertTrue(result.isAutoMergePr());
    }

    @Test
    void updateSettings_autoMergePr_canBeDisabledAfterEnabled() {
        SiteSettings enable = new SiteSettings();
        enable.setAutoMergePr(true);
        settingsService.updateSettings(enable);

        SiteSettings disable = new SiteSettings();
        disable.setAutoMergePr(false);
        SiteSettings result = settingsService.updateSettings(disable);

        assertFalse(result.isAutoMergePr());
    }

    @Test
    void getSettings_requireRegistrationApproval_falseByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertFalse(settings.isRequireRegistrationApproval());
    }

    @Test
    void updateSettings_requireRegistrationApproval_canBeEnabled() {
        SiteSettings update = new SiteSettings();
        update.setRequireRegistrationApproval(true);

        SiteSettings result = settingsService.updateSettings(update);

        assertTrue(result.isRequireRegistrationApproval());
    }

    @Test
    void updateSettings_requireRegistrationApproval_canBeDisabled() {
        SiteSettings enable = new SiteSettings();
        enable.setRequireRegistrationApproval(true);
        settingsService.updateSettings(enable);

        SiteSettings disable = new SiteSettings();
        disable.setRequireRegistrationApproval(false);
        SiteSettings result = settingsService.updateSettings(disable);

        assertFalse(result.isRequireRegistrationApproval());
    }
}
