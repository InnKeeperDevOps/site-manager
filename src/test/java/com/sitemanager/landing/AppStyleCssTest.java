package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that the app stylesheet contains all rules required for the
 * contributor dashboard, leaderboard, and submission history panel.
 */
class AppStyleCssTest {

    private static String css;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("src", "main", "resources", "static", "css", "style.css");
        assertTrue(Files.exists(path), "src/main/resources/static/css/style.css must exist");
        css = Files.readString(path);
    }

    @Test
    void leaderboardTableRuleExists() {
        assertTrue(css.contains(".leaderboard-table"),
                "Stylesheet must define .leaderboard-table");
    }

    @Test
    void leaderboardTableIsFullWidth() {
        assertTrue(css.contains("width: 100%") || css.contains("width:100%"),
                ".leaderboard-table must set width to 100%");
    }

    @Test
    void leaderboardTableBorderCollapse() {
        assertTrue(css.contains("border-collapse: collapse") || css.contains("border-collapse:collapse"),
                ".leaderboard-table must set border-collapse: collapse");
    }

    @Test
    void leaderboardTableCellPadding() {
        assertTrue(css.contains(".leaderboard-table th") && css.contains(".leaderboard-table td"),
                "Stylesheet must apply padding rules to leaderboard th and td elements");
    }

    @Test
    void leaderboardTableCellBorderUsesVariable() {
        assertTrue(css.contains("var(--border)"),
                "Leaderboard cell border must use the --border CSS variable");
    }

    @Test
    void leaderboardTableCellTextAlign() {
        assertTrue(css.contains("text-align: left") || css.contains("text-align:left"),
                "Leaderboard cells must be left-aligned");
    }

    @Test
    void leaderboardTableHoverRuleExists() {
        assertTrue(css.contains(".leaderboard-table tbody tr:hover"),
                "Stylesheet must define a hover rule for leaderboard rows");
    }

    @Test
    void leaderboardTableHoverUsesVariable() {
        int hoverIdx = css.indexOf(".leaderboard-table tbody tr:hover");
        assertTrue(hoverIdx >= 0, ".leaderboard-table tbody tr:hover rule not found");
        String afterHover = css.substring(hoverIdx, Math.min(hoverIdx + 120, css.length()));
        assertTrue(afterHover.contains("var(--bg)"),
                "Leaderboard row hover background must use the --bg CSS variable");
    }

    @Test
    void rankMedalRuleExists() {
        assertTrue(css.contains(".rank-medal"),
                "Stylesheet must define .rank-medal");
    }

    @Test
    void rankMedalFontSize() {
        int idx = css.indexOf(".rank-medal");
        assertTrue(idx >= 0, ".rank-medal rule not found");
        String block = css.substring(idx, Math.min(idx + 80, css.length()));
        assertTrue(block.contains("font-size"),
                ".rank-medal must set font-size");
    }

    @Test
    void contributorLinkRuleExists() {
        assertTrue(css.contains(".contributor-link"),
                "Stylesheet must define .contributor-link");
    }

    @Test
    void contributorLinkColorUsesVariable() {
        int idx = css.indexOf(".contributor-link");
        assertTrue(idx >= 0, ".contributor-link rule not found");
        String block = css.substring(idx, Math.min(idx + 120, css.length()));
        assertTrue(block.contains("var(--primary)"),
                ".contributor-link color must use the --primary CSS variable");
    }

    @Test
    void contributorLinkIsCursorPointer() {
        int idx = css.indexOf(".contributor-link");
        assertTrue(idx >= 0, ".contributor-link rule not found");
        String block = css.substring(idx, Math.min(idx + 120, css.length()));
        assertTrue(block.contains("cursor: pointer") || block.contains("cursor:pointer"),
                ".contributor-link must set cursor: pointer");
    }

    @Test
    void contributorLinkHasUnderline() {
        int idx = css.indexOf(".contributor-link");
        assertTrue(idx >= 0, ".contributor-link rule not found");
        String block = css.substring(idx, Math.min(idx + 120, css.length()));
        assertTrue(block.contains("text-decoration: underline") || block.contains("text-decoration:underline"),
                ".contributor-link must set text-decoration: underline");
    }

    @Test
    void userHistoryPanelRuleExists() {
        assertTrue(css.contains("#userHistoryPanel"),
                "Stylesheet must define #userHistoryPanel");
    }

    @Test
    void userHistoryPanelHasMarginTop() {
        int idx = css.indexOf("#userHistoryPanel");
        assertTrue(idx >= 0, "#userHistoryPanel rule not found");
        String block = css.substring(idx, Math.min(idx + 150, css.length()));
        assertTrue(block.contains("margin-top"),
                "#userHistoryPanel must set margin-top");
    }

    @Test
    void userHistoryPanelHasBorderRadius() {
        int idx = css.indexOf("#userHistoryPanel");
        assertTrue(idx >= 0, "#userHistoryPanel rule not found");
        String block = css.substring(idx, Math.min(idx + 150, css.length()));
        assertTrue(block.contains("border-radius"),
                "#userHistoryPanel must set border-radius");
    }

    @Test
    void userHistoryPanelBorderUsesVariable() {
        int idx = css.indexOf("#userHistoryPanel");
        assertTrue(idx >= 0, "#userHistoryPanel rule not found");
        String block = css.substring(idx, Math.min(idx + 150, css.length()));
        assertTrue(block.contains("var(--border)"),
                "#userHistoryPanel border must use the --border CSS variable");
    }

    @Test
    void historySuggestionCardRuleExists() {
        assertTrue(css.contains(".history-suggestion-card"),
                "Stylesheet must define .history-suggestion-card");
    }

    @Test
    void historySuggestionCardIsFlexbox() {
        int idx = css.indexOf(".history-suggestion-card");
        assertTrue(idx >= 0, ".history-suggestion-card rule not found");
        String block = css.substring(idx, Math.min(idx + 200, css.length()));
        assertTrue(block.contains("display: flex") || block.contains("display:flex"),
                ".history-suggestion-card must use flexbox layout");
    }

    @Test
    void historySuggestionCardSpaceBetween() {
        int idx = css.indexOf(".history-suggestion-card");
        assertTrue(idx >= 0, ".history-suggestion-card rule not found");
        String block = css.substring(idx, Math.min(idx + 200, css.length()));
        assertTrue(block.contains("justify-content: space-between") || block.contains("justify-content:space-between"),
                ".history-suggestion-card must use justify-content: space-between");
    }

    @Test
    void historySuggestionCardBorderUsesVariable() {
        int idx = css.indexOf(".history-suggestion-card");
        assertTrue(idx >= 0, ".history-suggestion-card rule not found");
        String block = css.substring(idx, Math.min(idx + 200, css.length()));
        assertTrue(block.contains("var(--border)"),
                ".history-suggestion-card border must use the --border CSS variable");
    }
}
