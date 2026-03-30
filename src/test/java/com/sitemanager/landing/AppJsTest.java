package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that the dashboard and navigation functions exist in the JS modules.
 * Plain file-read checks — no server required.
 *
 * After the ES-module refactor the implementation lives in modules/dashboard.js
 * and modules/navigation.js; app.js is the entry point that imports them all.
 */
class AppJsTest {

    private static String appJs;
    private static String dashboardJs;
    private static String navigationJs;

    @BeforeAll
    static void loadFiles() throws IOException {
        Path appPath = Paths.get("src", "main", "resources", "static", "js", "app.js");
        assertTrue(Files.exists(appPath), "src/main/resources/static/js/app.js must exist");
        appJs = Files.readString(appPath);

        Path dashboardPath = Paths.get("src", "main", "resources", "static", "js", "modules", "dashboard.js");
        assertTrue(Files.exists(dashboardPath), "modules/dashboard.js must exist");
        dashboardJs = Files.readString(dashboardPath);

        Path navigationPath = Paths.get("src", "main", "resources", "static", "js", "modules", "navigation.js");
        assertTrue(Files.exists(navigationPath), "modules/navigation.js must exist");
        navigationJs = Files.readString(navigationPath);
    }

    @Test
    void navigateSwitchHasDashboardCase() {
        assertTrue(navigationJs.contains("case 'dashboard'"),
                "navigate() switch in navigation.js must contain a 'dashboard' case");
    }

    @Test
    void dashboardCaseCallsLoadDashboardView() {
        // The entry point imports and exposes loadDashboardView; navigation module calls the callback
        assertTrue(navigationJs.contains("loadDashboardView") || appJs.contains("loadDashboardView()"),
                "dashboard case must call loadDashboardView()");
    }

    @Test
    void loadDashboardViewFunctionExists() {
        assertTrue(dashboardJs.contains("loadDashboardView") || appJs.contains("loadDashboardView"),
                "loadDashboardView must be defined in dashboard.js or app.js");
    }

    @Test
    void loadDashboardViewFetchesLeaderboardEndpoint() {
        assertTrue(dashboardJs.contains("/api/contributors/leaderboard"),
                "loadDashboardView in dashboard.js must fetch /api/contributors/leaderboard");
    }

    @Test
    void loadDashboardViewCallsRenderLeaderboard() {
        assertTrue(dashboardJs.contains("renderLeaderboard("),
                "loadDashboardView must call renderLeaderboard");
    }

    @Test
    void renderLeaderboardFunctionExists() {
        assertTrue(dashboardJs.contains("renderLeaderboard(contributors)"),
                "dashboard.js must define renderLeaderboard(contributors)");
    }

    @Test
    void renderLeaderboardUsesMedalEmojis() {
        // Medals can be stored as unicode escapes or literal characters
        boolean hasMedals = dashboardJs.contains("uD83E") || dashboardJs.contains("\uD83E\uDD47")
                || dashboardJs.contains("🥇") || dashboardJs.contains("\\uD83E");
        assertTrue(hasMedals, "renderLeaderboard must include medal emoji for top-3 ranks");
    }

    @Test
    void renderLeaderboardCreatesContributorLinks() {
        assertTrue(dashboardJs.contains("contributor-link"),
                "renderLeaderboard must create elements with class 'contributor-link'");
    }

    @Test
    void renderLeaderboardStoresAuthorIdOnLink() {
        assertTrue(dashboardJs.contains("data-author-id"),
                "renderLeaderboard must store data-author-id on contributor links");
    }

    @Test
    void renderLeaderboardUsesEventDelegationForClicks() {
        assertTrue(dashboardJs.contains("closest('.contributor-link')"),
                "renderLeaderboard must use event delegation with closest('.contributor-link')");
    }

    @Test
    void renderLeaderboardFetchesHistoryByAuthorId() {
        assertTrue(dashboardJs.contains("/api/contributors/") && dashboardJs.contains("/history"),
                "renderLeaderboard click handler must fetch /api/contributors/{authorId}/history");
    }

    @Test
    void renderLeaderboardCallsRenderUserHistory() {
        assertTrue(dashboardJs.contains("renderUserHistory("),
                "renderLeaderboard click handler must call renderUserHistory");
    }

    @Test
    void renderUserHistoryFunctionExists() {
        assertTrue(dashboardJs.contains("renderUserHistory(username, suggestions)"),
                "dashboard.js must define renderUserHistory(username, suggestions)");
    }

    @Test
    void renderUserHistoryShowsPanel() {
        assertTrue(dashboardJs.contains("userHistoryPanel"),
                "renderUserHistory must reference userHistoryPanel element");
    }

    @Test
    void renderUserHistoryShowsUsername() {
        assertTrue(dashboardJs.contains("historyUsername"),
                "renderUserHistory must set historyUsername element text");
    }

    @Test
    void renderUserHistoryLinksToDetailView() {
        // Matches both navigate('detail', ...) and navigate(\'detail\', ...) in template strings
        assertTrue(dashboardJs.contains("navigate") && dashboardJs.contains("detail"),
                "renderUserHistory must link each suggestion to the detail view");
    }

    @Test
    void renderUserHistoryShowsUpvotes() {
        assertTrue(dashboardJs.contains("upVotes"),
                "renderUserHistory must display the upvote count for each suggestion");
    }

    @Test
    void renderUserHistoryShowsDate() {
        assertTrue(dashboardJs.contains("timeAgo(s.createdAt)"),
                "renderUserHistory must display relative date using timeAgo");
    }

    @Test
    void renderUserHistoryHandlesEmptySuggestions() {
        assertTrue(dashboardJs.contains("No submissions found"),
                "renderUserHistory must handle empty suggestions list gracefully");
    }
}
