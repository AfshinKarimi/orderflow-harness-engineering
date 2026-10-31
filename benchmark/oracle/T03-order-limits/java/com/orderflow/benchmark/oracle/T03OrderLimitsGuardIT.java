package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T03 guards: orders exactly at the limits stay accepted (these pass on the base commit by design). */
@OracleSpringBootTest
class T03OrderLimitsGuardIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void twentyLinesAreAccepted() throws Exception {
        create(T03OrderLimitsOracleIT.lines(20, "1.00"));
    }

    @Test
    void totalOfExactlyTenThousandIsAccepted() throws Exception {
        create(T03OrderLimitsOracleIT.lines(1, "10000.00"));
    }

    @Test
    void scaleOfTheAmountDoesNotMatter() throws Exception {
        create(T03OrderLimitsOracleIT.lines(4, "2500.0000"));
    }

    private void create(String lines) throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + T03OrderLimitsOracleIT.customer()
                                + "\",\"currency\":\"EUR\",\"lines\":" + lines + "}"))
                .andExpect(status().isCreated());
    }
}
