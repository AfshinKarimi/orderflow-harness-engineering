package com.orderflow.benchmark.oracle;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring context of the hidden oracle: the application with its own containers. Pins the outbox relay setting so
 * results do not depend on the agent's test configuration. Oracle classes with the same properties share one
 * context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(OracleContainers.class)
public @interface OracleSpringBootTest {

    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {"orderflow.outbox.relay.enabled=false"};
}
