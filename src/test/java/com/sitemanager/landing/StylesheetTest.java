package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the structure of landing_site/css/styles.css.
 * These tests are plain file-read checks — no browser required.
 */
class StylesheetTest {

    private static String css;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("landing_site", "css", "styles.css");
        assertTrue(Files.exists(path), "landing_site/css/styles.css must exist");
        css = Files.readString(path);
    }

    // ---- Custom properties (CSS variables) ----

    @Test
    void definesDarkBackground() {
        assertTrue(css.contains("--color-bg"),
                "Stylesheet must define a --color-bg custom property");
        // Value should be a dark hex close to #0d1117
        assertTrue(css.matches("(?s).*--color-bg\\s*:\\s*#0[a-fA-F0-9]{5}.*"),
                "Background color must be a dark hex value (starting with #0...)");
    }

    @Test
    void definesAccentColor() {
        assertTrue(css.contains("--color-accent"),
                "Stylesheet must define a --color-accent custom property");
    }

    @Test
    void definesTextColors() {
        assertTrue(css.contains("--color-text"),
                "Stylesheet must define --color-text");
        assertTrue(css.contains("--color-text-muted"),
                "Stylesheet must define --color-text-muted");
    }

    @Test
    void definesFontStack() {
        assertTrue(css.contains("--font-sans"),
                "Stylesheet must define a --font-sans custom property");
        assertTrue(css.contains("--font-mono"),
                "Stylesheet must define a --font-mono custom property for code blocks");
    }

    // ---- Hero section ----

    @Test
    void heroSectionStyled() {
        assertTrue(css.contains(".hero"),
                "Stylesheet must style the .hero section");
    }

    @Test
    void heroTitleStyled() {
        assertTrue(css.contains(".hero-title"),
                "Stylesheet must style the .hero-title element");
    }

    @Test
    void ctaButtonStyled() {
        assertTrue(css.contains(".cta-button"),
                "Stylesheet must style the .cta-button element");
    }

    @Test
    void ctaButtonHoverStyled() {
        assertTrue(css.contains(".cta-button:hover"),
                "Stylesheet must include a hover state for .cta-button");
    }

    // ---- Features grid ----

    @Test
    void featuresGridUsesCssGrid() {
        assertTrue(css.contains(".features-grid"),
                "Stylesheet must define .features-grid");
        assertTrue(css.contains("display: grid") || css.contains("display:grid"),
                "Features layout must use CSS grid");
    }

    @Test
    void featureCardStyled() {
        assertTrue(css.contains(".feature-card"),
                "Stylesheet must style .feature-card");
    }

    // ---- Footer ----

    @Test
    void footerStyled() {
        assertTrue(css.contains(".site-footer"),
                "Stylesheet must style .site-footer");
    }

    @Test
    void footerLinkStyled() {
        assertTrue(css.contains(".footer-link"),
                "Stylesheet must style .footer-link");
    }

    // ---- pre / code blocks (for docs.html) ----

    @Test
    void preBlockStyled() {
        assertTrue(css.contains("pre {") || css.contains("pre{"),
                "Stylesheet must include styles for <pre> blocks");
    }

    @Test
    void inlineCodeStyled() {
        // A bare `code {` rule (not inside pre)
        assertTrue(css.contains("code {") || css.contains("code{"),
                "Stylesheet must include styles for inline <code> elements");
    }

    @Test
    void preCodeResetsInheritedStyles() {
        assertTrue(css.contains("pre code"),
                "Stylesheet must reset styles for <code> inside <pre>");
    }

    // ---- Docs nav anchors ----

    @Test
    void docsNavStyled() {
        assertTrue(css.contains(".docs-nav"),
                "Stylesheet must include .docs-nav styles for in-page navigation");
    }

    @Test
    void docsNavAnchorStyled() {
        assertTrue(css.contains(".docs-nav a"),
                "Stylesheet must style anchor links inside .docs-nav");
    }

    // ---- Callout ----

    @Test
    void calloutStyled() {
        assertTrue(css.contains(".callout"),
                "Stylesheet must include .callout styles for warning blocks in docs.html");
    }

    // ---- Mobile breakpoints ----

    @Test
    void hasMobileMediaQuery() {
        assertTrue(css.contains("@media"),
                "Stylesheet must include at least one @media query");
    }

    @Test
    void mobileBreakpointAt768() {
        assertTrue(css.contains("768px"),
                "Stylesheet must include a breakpoint at 768px for mobile");
    }

    @Test
    void featuresGridSingleColumnOnMobile() {
        // Inside the @media block, the grid should collapse to a single column
        int mediaIdx = css.indexOf("@media");
        assertTrue(mediaIdx >= 0, "No @media block found");
        String afterMedia = css.substring(mediaIdx);
        assertTrue(afterMedia.contains("grid-template-columns: 1fr") ||
                   afterMedia.contains("grid-template-columns:1fr"),
                "Features grid must collapse to a single column on mobile");
    }
}
