package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the structure of src/main/resources/static/index.html.
 * Plain file-read checks — no server required.
 */
class AppIndexHtmlTest {

    private static String html;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("src", "main", "resources", "static", "index.html");
        assertTrue(Files.exists(path), "src/main/resources/static/index.html must exist");
        html = Files.readString(path);
    }

    @Test
    void dashboardViewExists() {
        assertTrue(html.contains("id=\"dashboardView\""),
                "Page must contain dashboardView div");
    }

    @Test
    void dashboardViewIsHiddenByDefault() {
        assertTrue(html.contains("id=\"dashboardView\"") && html.contains("id=\"dashboardView\" class=\"view\" style=\"display:none\""),
                "dashboardView must be hidden by default with style=\"display:none\"");
    }

    @Test
    void dashboardViewHasHeading() {
        assertTrue(html.contains("Contributor Activity"),
                "dashboardView must contain 'Contributor Activity' heading");
    }

    @Test
    void leaderboardTableExists() {
        assertTrue(html.contains("id=\"leaderboardTable\""),
                "Page must contain leaderboardTable element");
    }

    @Test
    void leaderboardBodyExists() {
        assertTrue(html.contains("id=\"leaderboardBody\""),
                "Page must contain leaderboardBody tbody element");
    }

    @Test
    void leaderboardHasAllColumns() {
        assertTrue(html.contains("Rank"), "Leaderboard must have Rank column");
        assertTrue(html.contains("Contributor"), "Leaderboard must have Contributor column");
        assertTrue(html.contains("Submissions"), "Leaderboard must have Submissions column");
        assertTrue(html.contains("Merged"), "Leaderboard must have Merged column");
        assertTrue(html.contains("Approved"), "Leaderboard must have Approved column");
        assertTrue(html.contains("Upvotes"), "Leaderboard must have Upvotes column");
        assertTrue(html.contains("Score"), "Leaderboard must have Score column");
    }

    @Test
    void userHistoryPanelExists() {
        assertTrue(html.contains("id=\"userHistoryPanel\""),
                "Page must contain userHistoryPanel element");
    }

    @Test
    void userHistoryPanelIsHiddenByDefault() {
        assertTrue(html.contains("id=\"userHistoryPanel\"") && html.contains("id=\"userHistoryPanel\" style=\"display:none"),
                "userHistoryPanel must be hidden by default with display:none");
    }

    @Test
    void historyUsernameElementExists() {
        assertTrue(html.contains("id=\"historyUsername\""),
                "Page must contain historyUsername element");
    }

    @Test
    void historyListElementExists() {
        assertTrue(html.contains("id=\"historyList\""),
                "Page must contain historyList element");
    }

    @Test
    void dashboardNavButtonExists() {
        assertTrue(html.contains("id=\"dashboardBtn\""),
                "Page must contain a Dashboard navigation button");
    }

    @Test
    void dashboardNavButtonNavigatesToDashboard() {
        assertTrue(html.contains("app.navigate('dashboard')"),
                "Dashboard button must call app.navigate('dashboard')");
    }

    @Test
    void dashboardNavButtonHasCorrectLabel() {
        assertTrue(html.contains(">Dashboard<"),
                "Dashboard button must display 'Dashboard' label");
    }
}
