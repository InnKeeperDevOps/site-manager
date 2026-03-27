package com.sitemanager.config;

import com.sitemanager.model.enums.SuggestionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
@Order(1)
public class DataMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public DataMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        migrateStatusCheckConstraint();

        int updated = jdbcTemplate.update(
                "UPDATE suggestions SET status = 'DEV_COMPLETE' WHERE status = 'COMPLETED'");
        if (updated > 0) {
            log.info("Data migration: updated {} suggestion(s) from COMPLETED to DEV_COMPLETE", updated);
        }
    }

    /**
     * SQLite CHECK constraints are baked into the CREATE TABLE statement and cannot be
     * altered in place. When the SuggestionStatus enum changes, the constraint must be
     * updated via the standard SQLite table-rebuild pattern: create a new table with the
     * correct constraint, copy data, drop the old table, and rename.
     */
    private void migrateStatusCheckConstraint() {
        String currentSql = jdbcTemplate.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='suggestions'",
                String.class);

        if (currentSql == null) {
            return;
        }

        String validStatuses = Arrays.stream(SuggestionStatus.values())
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(","));

        boolean needsRebuild = Arrays.stream(SuggestionStatus.values())
                .anyMatch(s -> !currentSql.contains("'" + s.name() + "'"));

        if (!needsRebuild) {
            return;
        }

        log.info("Data migration: rebuilding suggestions table to update status CHECK constraint");

        // Replace the old CHECK constraint with one derived from the current enum
        String newSql = currentSql.replaceFirst(
                "(?i)check\\s*\\(\\s*status\\s+in\\s*\\([^)]+\\)\\)",
                "check(status in (" + validStatuses + "))");

        // Use the rebuilt CREATE TABLE statement with a temporary name
        String tempCreateSql = newSql.replaceFirst(
                "(?i)create\\s+table\\s+suggestions\\b",
                "CREATE TABLE suggestions_rebuild");

        jdbcTemplate.execute("PRAGMA foreign_keys=OFF");
        jdbcTemplate.execute(tempCreateSql);
        jdbcTemplate.execute("INSERT INTO suggestions_rebuild SELECT * FROM suggestions");
        jdbcTemplate.execute("DROP TABLE suggestions");
        jdbcTemplate.execute("ALTER TABLE suggestions_rebuild RENAME TO suggestions");
        jdbcTemplate.execute("PRAGMA foreign_keys=ON");

        log.info("Data migration: status CHECK constraint updated to: {}", validStatuses);
    }
}
