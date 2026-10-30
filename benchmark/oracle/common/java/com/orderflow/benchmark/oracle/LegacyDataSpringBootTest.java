package com.orderflow.benchmark.oracle;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Context on a {@link LegacyDatabase}; each test class registers its own database and gets its own context. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "orderflow.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(OracleKafka.class)
public @interface LegacyDataSpringBootTest {
}
