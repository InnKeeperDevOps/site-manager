package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceJsonExtractionTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @InjectMocks
    private ClaudeService claudeService;

    @Test
    void extractsCleanJson() {
        String input = "{\"key\":\"val\"}";
        String result = claudeService.extractJsonObject(input);
        assertEquals(input, result);
    }

    @Test
    void stripsPrefixLogLines() {
        String input = "INFO: starting\n{\"key\":\"val\"}";
        String result = claudeService.extractJsonObject(input);
        assertEquals("{\"key\":\"val\"}", result);
    }

    @Test
    void stripsSuffixText() {
        String input = "{\"key\":\"val\"}\nDone.";
        String result = claudeService.extractJsonObject(input);
        assertEquals("{\"key\":\"val\"}", result);
    }

    @Test
    void handlesMixedCliOutput() {
        String input = "Connecting to Claude...\nWARN: retrying\n{\"session_id\":\"abc\",\"result\":\"ok\"}\nProcess complete.";
        String result = claudeService.extractJsonObject(input);
        assertEquals("{\"session_id\":\"abc\",\"result\":\"ok\"}", result);
    }

    @Test
    void handlesNestedObjects() {
        String input = "{\"outer\":{\"inner\":\"v\"}}";
        String result = claudeService.extractJsonObject(input);
        assertEquals("{\"outer\":{\"inner\":\"v\"}}", result);
    }

    @Test
    void handlesEmptyObject() {
        String input = "{}";
        String result = claudeService.extractJsonObject(input);
        assertEquals("{}", result);
    }

    @Test
    void returnsNullOnNoJson() {
        // Input with no '{' character — implementation returns null
        String input = "no json here at all";
        String result = claudeService.extractJsonObject(input);
        assertNull(result, "Expected null when input contains no '{' character");
    }

    @Test
    void handlesMultilineJson() {
        String input = "{\n  \"key\": \"value\",\n  \"num\": 42\n}";
        String result = claudeService.extractJsonObject(input);
        assertEquals(input, result);
    }

    @Test
    void doesNotReturnPartialJson() {
        // Input with '{' but no matching '}' — implementation returns null
        String input = "{no closing brace...";
        String result = claudeService.extractJsonObject(input);
        assertNull(result, "Expected null rather than a partial JSON string");
    }
}
