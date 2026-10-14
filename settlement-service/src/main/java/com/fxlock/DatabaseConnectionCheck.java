package com.fxlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseConnectionCheck implements CommandLineRunner {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseConnectionCheck.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConnectionCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        String databaseUser = jdbcTemplate.queryForObject(
                "SELECT USER FROM dual",
                String.class
        );

        log.info("Oracle connection verified. Database user: {}", databaseUser);
    }
}