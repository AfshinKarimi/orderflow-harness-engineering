package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T09: the order domain represents customers as CustomerId. Reflection only, so it compiles on any solution. */
class T09CustomerIdOracleTest {

    private static final String CUSTOMER_ID = "com.orderflow.order.domain.CustomerId";

    @Test
    void orderExposesACustomerId() throws Exception {
        Class<?> order = Class.forName("com.orderflow.order.domain.Order");

        assertThat(order.getMethod("customerId").getReturnType().getName()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void customerIdWrapsTheValue() throws Exception {
        Object customerId = constructor().newInstance("c-1");

        assertThat(customerId.getClass().getMethod("value").invoke(customerId)).isEqualTo("c-1");
        assertThat(customerId).isEqualTo(constructor().newInstance("c-1"));
    }

    @Test
    void customerIdValidates() throws Exception {
        Constructor<?> constructor = constructor();

        assertThat(constructor.newInstance("c".repeat(100))).isNotNull();
        for (String invalid : new String[] {"", "  ", null, "c".repeat(101)}) {
            assertThatThrownBy(() -> constructor.newInstance(invalid))
                    .as("new CustomerId(%s)", invalid)
                    .isInstanceOf(InvocationTargetException.class)
                    .cause().isInstanceOf(RuntimeException.class);
        }
    }

    private static Constructor<?> constructor() throws Exception {
        return Class.forName(CUSTOMER_ID).getConstructor(String.class);
    }
}
