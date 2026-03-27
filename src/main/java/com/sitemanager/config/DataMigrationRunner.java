package com.sitemanager.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
        int updated = jdbcTemplate.update(
                "UPDATE suggestions SET status = 'DEV_COMPLETE' WHERE status = 'COMPLETED'");
        if (updated > 0) {
            log.info("Data migration: updated {} suggestion(s) from COMPLETED to DEV_COMPLETE", updated);
        }
    }
}
