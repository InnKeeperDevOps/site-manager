package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the structure of landing_site/index.html.
 * These tests are plain file-read checks — no server required.
 */
class LandingPageTest {

    private static String html;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("landing_site", "index.html");
        assertTrue(Files.exists(path), "landing_site/index.html must exist");
        html = Files.readString(path);
    }

    @Test
    void hasDoctype() {
        assertTrue(html.trim().toLowerCase().startsWith("<!doctype html"),
                "Page must begin with <!DOCTYPE html>");
    }

    @Test
    void hasViewportMeta() {
        assertTrue(html.contains("name=\"viewport\""),
                "Page must contain a viewport meta tag for responsiveness");
    }

    @Test
    void titleContainsAibe() {
        assertTrue(html.contains("<title>") && html.toLowerCase().contains("aibe"),
                "Page <title> must mention Aibe");
    }

    @Test
    void linksSharedStylesheet() {
        assertTrue(html.contains("css/styles.css"),
                "Page must link to css/styles.css");
    }

    @Test
    void heroSectionPresent() {
        assertTrue(html.contains("class=\"hero\""),
                "Page must have a hero section with class 'hero'");
    }

    @Test
    void heroTitlePresent() {
        assertTrue(html.contains("Aibe.app"),
                "Hero must display 'Aibe.app' as the main heading");
    }

    @Test
    void ctaButtonLinksToApp() {
        assertTrue(html.contains("https://suggest.aibe.app"),
                "CTA button must link to https://suggest.aibe.app");
    }

    @Test
    void featuresSectionPresent() {
        assertTrue(html.contains("class=\"features\""),
                "Page must have a features section with class 'features'");
    }

    @Test
    void featureGridPresent() {
        assertTrue(html.contains("class=\"features-grid\""),
                "Page must have a features grid");
    }

    @Test
    void hasAtLeastFiveFeatureCards() {
        long count = html.lines()
                .filter(line -> line.contains("class=\"feature-card\""))
                .count();
        assertTrue(count >= 5,
                "Page must have at least 5 feature cards, found: " + count);
    }

    @Test
    void footerPresent() {
        assertTrue(html.contains("class=\"site-footer\""),
                "Page must have a footer with class 'site-footer'");
    }

    @Test
    void footerLinksToApp() {
        // Footer must also link to suggest.aibe.app (not just the CTA)
        long appLinkCount = html.lines()
                .filter(line -> line.contains("https://suggest.aibe.app"))
                .count();
        assertTrue(appLinkCount >= 1,
                "Footer must contain a link to suggest.aibe.app");
    }

    @Test
    void footerLinksToDocsPage() {
        assertTrue(html.contains("docs.html"),
                "Footer must link to docs.html for documentation");
    }

    @Test
    void noExternalJsFrameworks() {
        String lower = html.toLowerCase();
        assertFalse(lower.contains("react") || lower.contains("vue") || lower.contains("angular"),
                "Page must not load external JS frameworks");
        assertFalse(lower.contains("jquery"),
                "Page must not load jQuery");
    }
}
