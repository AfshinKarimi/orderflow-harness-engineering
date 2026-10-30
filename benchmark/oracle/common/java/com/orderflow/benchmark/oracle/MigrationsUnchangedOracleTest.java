package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applied Flyway migrations must never be edited, renamed or deleted: the base commit's V1-V5 must be present
 * with identical content. New migrations are allowed.
 */
class MigrationsUnchangedOracleTest {

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    void baseMigrationsAreUnchanged() throws IOException {
        Map<String, String> base = read("classpath:benchmark/base-migrations/*.sql");
        Map<String, String> current = read("classpath:db/migration/*.sql");

        assertThat(base).as("oracle baseline").hasSize(5);
        for (Map.Entry<String, String> migration : base.entrySet()) {
            assertThat(current).as("migration %s still exists", migration.getKey()).containsKey(migration.getKey());
            assertThat(current.get(migration.getKey())).as("content of %s", migration.getKey())
                    .isEqualTo(migration.getValue());
        }
    }

    private Map<String, String> read(String pattern) throws IOException {
        List<Resource> resources = Arrays.asList(resolver.getResources(pattern));
        return resources.stream().collect(Collectors.toMap(Resource::getFilename, Function.identity()))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    try {
                        return entry.getValue().getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }
}
