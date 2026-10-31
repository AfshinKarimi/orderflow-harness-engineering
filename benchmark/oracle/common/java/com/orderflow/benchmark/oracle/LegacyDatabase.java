package com.orderflow.benchmark.oracle;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A database as it looked before the task: migrated with the base commit's V1-V5 (copies kept by the oracle) and
 * filled with rows in that schema. The application under test then migrates it to its latest version at startup,
 * so a migration that cannot handle existing data, or an edited V1-V5 (checksum mismatch), fails the context.
 * Usage, in the test class: {@code @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
 * LegacyDatabase.register(r, "t05", "INSERT ...") }}.
 */
final class LegacyDatabase {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private LegacyDatabase() {
    }

    static synchronized void register(DynamicPropertyRegistry registry, String name, String... legacyRows) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name);
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:benchmark/base-migrations")
                .target("5")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            for (String row : legacyRows) {
                statement.execute(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
