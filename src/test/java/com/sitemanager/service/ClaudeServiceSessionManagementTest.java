package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceSessionManagementTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @InjectMocks
    private ClaudeService claudeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(claudeService, "workspaceDir", "/workspace");
    }

    @Test
    void generateSessionId_returnsValidUuidFormat() {
        String id = claudeService.generateSessionId();
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void generateSessionId_returnsUniqueValues() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(claudeService.generateSessionId());
        }
        assertEquals(1000, ids.size());
    }

    @Test
    void sessionMap_isInitiallyEmpty() {
        Object map = ReflectionTestUtils.getField(claudeService, "sessionMap");
        assertThat((Map<?, ?>) map).isEmpty();
    }

    @Test
    void sessionMap_isConcurrentHashMap() {
        Object map = ReflectionTestUtils.getField(claudeService, "sessionMap");
        assertThat(map).isInstanceOf(ConcurrentHashMap.class);
    }

    @Test
    void requestCounter_startsAtZero() {
        Object counter = ReflectionTestUtils.getField(claudeService, "requestCounter");
        assertEquals(0L, ((AtomicLong) counter).get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionMap_allowsPutAndRemove() {
        Object raw = ReflectionTestUtils.getField(claudeService, "sessionMap");
        Map<String, String> map = (Map<String, String>) raw;

        map.put("appId", "cliId");
        assertEquals("cliId", map.get("appId"));

        map.remove("appId");
        assertNull(map.get("appId"));
    }

    @Test
    void getMainRepoDir_returnsConfiguredWorkspaceSubdir() {
        ReflectionTestUtils.setField(claudeService, "workspaceDir", "/tmp/test");
        assertEquals("/tmp/test/main-repo", claudeService.getMainRepoDir());
    }
}
