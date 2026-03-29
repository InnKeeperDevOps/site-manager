package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that app.js contains the required dashboard functions and wiring.
 * Plain file-read checks — no server required.
 */
class AppJsTest {

    private static String js;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("src", "main", "resources", "static", "js", "app.js");
        assertTrue(Files.exists(path), "src/main/resources/static/js/app.js must exist");
        js = Files.readString(path);
    }

    @Test
    void navigateSwitchHasDashboardCase() {
        assertTrue(js.contains("case 'dashboard'"),
                "navigate() switch must contain a 'dashboard' case");
    }

    @Test
    void dashboardCaseCallsLoadDashboardView() {
        assertTrue(js.contains("loadDashboardView()"),
                "dashboard case must call loadDashboardView()");
    }

    @Test
    void loadDashboardViewFunctionExists() {
        assertTrue(js.contains("loadDashboardView()") || js.contains("async loadDashboardView()"),
                "app.js must define loadDashboardView");
    }

    @Test
    void loadDashboardViewFetchesLeaderboardEndpoint() {
        assertTrue(js.contains("/api/contributors/leaderboard"),
                "loadDashboardView must fetch /api/contributors/leaderboard");
    }

    @Test
    void loadDashboardViewCallsRenderLeaderboard() {
        assertTrue(js.contains("renderLeaderboard("),
                "loadDashboardView must call renderLeaderboard");
    }

    @Test
    void renderLeaderboardFunctionExists() {
        assertTrue(js.contains("renderLeaderboard(contributors)"),
                "app.js must define renderLeaderboard(contributors)");
    }

    @Test
    void renderLeaderboardUsesMedalEmojis() {
        // Medals can be stored as unicode escapes or literal characters
        boolean hasMedals = js.contains("uD83E") || js.contains("\uD83E\uDD47")
                || js.contains("🥇") || js.contains("\\uD83E");
        assertTrue(hasMedals, "renderLeaderboard must include medal emoji for top-3 ranks");
    }

    @Test
    void renderLeaderboardCreatesContributorLinks() {
        assertTrue(js.contains("contributor-link"),
                "renderLeaderboard must create elements with class 'contributor-link'");
    }

    @Test
    void renderLeaderboardStoresAuthorIdOnLink() {
        assertTrue(js.contains("data-author-id"),
                "renderLeaderboard must store data-author-id on contributor links");
    }

    @Test
    void renderLeaderboardUsesEventDelegationForClicks() {
        assertTrue(js.contains("closest('.contributor-link')"),
                "renderLeaderboard must use event delegation with closest('.contributor-link')");
    }

    @Test
    void renderLeaderboardFetchesHistoryByAuthorId() {
        assertTrue(js.contains("/api/contributors/") && js.contains("/history"),
                "renderLeaderboard click handler must fetch /api/contributors/{authorId}/history");
    }

    @Test
    void renderLeaderboardCallsRenderUserHistory() {
        assertTrue(js.contains("renderUserHistory("),
                "renderLeaderboard click handler must call renderUserHistory");
    }

    @Test
    void renderUserHistoryFunctionExists() {
        assertTrue(js.contains("renderUserHistory(username, suggestions)"),
                "app.js must define renderUserHistory(username, suggestions)");
    }

    @Test
    void renderUserHistoryShowsPanel() {
        assertTrue(js.contains("userHistoryPanel"),
                "renderUserHistory must reference userHistoryPanel element");
    }

    @Test
    void renderUserHistoryShowsUsername() {
        assertTrue(js.contains("historyUsername"),
                "renderUserHistory must set historyUsername element text");
    }

    @Test
    void renderUserHistoryLinksToDetailView() {
        assertTrue(js.contains("navigate('detail'") || js.contains("navigate(\\\"detail\\\""),
                "renderUserHistory must link each suggestion to the detail view");
    }

    @Test
    void renderUserHistoryShowsUpvotes() {
        assertTrue(js.contains("upVotes"),
                "renderUserHistory must display the upvote count for each suggestion");
    }

    @Test
    void renderUserHistoryShowsDate() {
        assertTrue(js.contains("timeAgo(s.createdAt)"),
                "renderUserHistory must display relative date using timeAgo");
    }

    @Test
    void renderUserHistoryHandlesEmptySuggestions() {
        assertTrue(js.contains("No submissions found"),
                "renderUserHistory must handle empty suggestions list gracefully");
    }
}
