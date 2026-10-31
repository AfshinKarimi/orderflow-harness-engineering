package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Hidden copy of architecture rule 11: no foreign key references a table in another module's schema. */
@OracleSpringBootTest
class SchemaOwnershipOracleIT {

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
        assertThat(jdbcTemplate.queryForList(FOREIGN_KEYS + " AND src.nspname <> tgt.nspname", String.class))
                .as("foreign keys referencing another module's schema").isEmpty();
    }

    @Test
    void foreignKeyQueryDetectsExistingKeys() {
        List<String> all = jdbcTemplate.queryForList(FOREIGN_KEYS, String.class);
        assertThat(all).anyMatch(fk -> fk.startsWith("orders.order_lines -> orders.orders"));
    }
}
