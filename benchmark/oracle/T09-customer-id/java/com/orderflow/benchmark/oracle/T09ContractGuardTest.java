package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09 guard: the order module's use case interfaces (port.in, its public API) keep exactly the signatures of the
 * base commit. Snapshot: benchmark/t09-port-in-signatures.txt (written with -Dbenchmark.snapshot.write=path).
 */
class T09ContractGuardTest {

    private static final String PACKAGE = "com.orderflow.order.application.port.in";

    @Test
    void useCaseInterfacesAreUnchanged() throws Exception {
        List<String> current = signatures();
        String write = System.getProperty("benchmark.snapshot.write");
        if (write != null) {
            Files.write(Path.of(write), current, StandardCharsets.UTF_8);
            return;
        }
        Resource snapshot = new PathMatchingResourcePatternResolver()
                .getResource("classpath:benchmark/t09-port-in-signatures.txt");
        List<String> expected = snapshot.getContentAsString(StandardCharsets.UTF_8).lines().toList();

        assertThat(current).containsExactlyElementsOf(expected);
    }

    static List<String> signatures() throws IOException, ClassNotFoundException {
        List<String> lines = new ArrayList<>();
        Resource[] classes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + PACKAGE.replace('.', '/') + "/*.class");
        List<String> names = new ArrayList<>();
        for (Resource resource : classes) {
            names.add(PACKAGE + "." + resource.getFilename().replace(".class", ""));
        }
        names.sort(null);
        for (String name : names) {
            Class<?> type = Class.forName(name);
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            lines.add(type.getName() + " " + kind(type));
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    lines.add("  component " + component.getName() + ": " + component.getGenericType().getTypeName());
                }
            } else if (type.isEnum()) {
                lines.add("  constants " + Arrays.toString(type.getEnumConstants()));
            } else if (type.isInterface()) {
                Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic() && Modifier.isPublic(method.getModifiers()))
                        .map(T09ContractGuardTest::describe)
                        .sorted()
                        .forEach(lines::add);
            } else {
                Arrays.stream(type.getConstructors())
                        .map(T09ContractGuardTest::describe)
                        .sorted()
                        .forEach(lines::add);
            }
        }
        return lines;
    }

    private static String kind(Class<?> type) {
        return type.isRecord() ? "record" : type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class";
    }

    private static String describe(Method method) {
        return "  method " + method.getName() + Arrays.stream(method.getGenericParameterTypes())
                .map(java.lang.reflect.Type::getTypeName).toList() + ": " + method.getGenericReturnType().getTypeName();
    }

    private static String describe(Constructor<?> constructor) {
        return "  constructor" + Arrays.stream(constructor.getGenericParameterTypes())
                .map(java.lang.reflect.Type::getTypeName).toList();
    }
}
