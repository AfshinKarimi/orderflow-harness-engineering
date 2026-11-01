package com.orderflow.architecture;

import com.orderflow.KafkaTestcontainersConfiguration;
import com.orderflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture rule 11 (data ownership), which ArchUnit cannot see: no foreign key may reference
 * a table in another module's schema. Runs against the real Flyway-migrated schema.
 */
// Same annotations as the other @SpringBootTest ITs so the cached context (and its container) is reused.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class SchemaOwnershipIT {

    private static final String FOREIGN_KEYS = """
            SELECT src.nspname || '.' || s.relname || ' -> '
                   || tgt.nspname || '.' || t.relname || ' (' || c.conname || ')'
            FROM pg_constraint c
                JOIN pg_class s ON s.oid = c.conrelid
                JOIN pg_namespace src ON src.oid = s.relnamespace
                JOIN pg_class t ON t.oid = c.confrelid
                JOIN pg_namespace tgt ON tgt.oid = t.relnamespace
            WHERE c.contype = 'f'
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noForeignKeyCrossesModuleSchemas() {
        List<String> crossSchema =
                jdbcTemplate.queryForList(FOREIGN_KEYS + " AND src.nspname <> tgt.nspname", String.class);

        assertThat(crossSchema).as("foreign keys referencing another module's schema").isEmpty();
    }

    @Test
    void foreignKeyQueryDetectsExistingKeys() {
        // Guards against the rule above passing vacuously (wrong database, broken query).
        List<String> all = jdbcTemplate.queryForList(FOREIGN_KEYS, String.class);

        assertThat(all).anyMatch(fk -> fk.startsWith("orders.order_lines -> orders.orders"));
    }
}
