package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T01: GET /api/v1/orders?customerId=&page=&size= lists a customer's orders, newest first, with paging. */
@OracleSpringBootTest
class T01ListOrdersOracleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void listsOnlyTheCustomersOrdersNewestFirst() throws Exception {
        String customer = customer();
        List<String> created = createOrders(customer, 3);
        createOrders(customer(), 2);

        JsonNode page = list("customerId=" + customer);

        assertThat(ids(page)).containsExactlyElementsOf(created.reversed());
        assertThat(page.path("totalItems").asLong()).isEqualTo(3);
        assertThat(page.path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void pagesThroughOrders() throws Exception {
        String customer = customer();
        List<String> newestFirst = createOrders(customer, 5).reversed();

        JsonNode first = list("customerId=" + customer + "&page=0&size=2");
        JsonNode second = list("customerId=" + customer + "&page=1&size=2");
        JsonNode third = list("customerId=" + customer + "&page=2&size=2");
        JsonNode beyond = list("customerId=" + customer + "&page=3&size=2");

        assertThat(ids(first)).containsExactlyElementsOf(newestFirst.subList(0, 2));
        assertThat(ids(second)).containsExactlyElementsOf(newestFirst.subList(2, 4));
        assertThat(ids(third)).containsExactlyElementsOf(newestFirst.subList(4, 5));
        assertThat(ids(beyond)).isEmpty();
        for (JsonNode page : List.of(first, second, third, beyond)) {
            assertThat(page.path("size").asInt()).isEqualTo(2);
            assertThat(page.path("totalItems").asLong()).isEqualTo(5);
            assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        }
        assertThat(second.path("page").asInt()).isEqualTo(1);
    }

    @Test
    void defaultsPageAndSize() throws Exception {
        String customer = customer();
        createOrders(customer, 1);

        JsonNode page = list("customerId=" + customer);

        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
    }

    @Test
    void customerWithoutOrders() throws Exception {
        JsonNode page = list("customerId=" + customer());

        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("totalItems").asLong()).isZero();
        assertThat(page.path("totalPages").asInt()).isZero();
    }

    @Test
    void itemsHaveTheSameFormatAsGetById() throws Exception {
        String customer = customer();
        String id = createOrders(customer, 1).getFirst();

        JsonNode item = list("customerId=" + customer).path("items").get(0);
        JsonNode single = jsonMapper.readTree(mockMvc.perform(get("/api/v1/orders/" + id))
                .andReturn().getResponse().getContentAsString());

        assertThat(item).isEqualTo(single);
    }

    @Test
    void rejectsInvalidParameters() throws Exception {
        String customer = "customerId=" + customer();
        for (String query : List.of("", "page=0&size=10", customer + "&size=0", customer + "&size=101",
                customer + "&page=-1", customer + "&size=abc")) {
            MvcResult result = mockMvc.perform(get("/api/v1/orders?" + query)).andReturn();

            assertThat(result.getResponse().getStatus()).as("GET ?%s", query).isEqualTo(400);
            assertThat(result.getResponse().getContentType()).as("GET ?%s", query)
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
    }

    private JsonNode list(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders?" + query)).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET ?%s", query).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> createOrders(String customer, int count) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customerId\":\"" + customer + "\",\"currency\":\"EUR\",\"lines\":"
                                    + "[{\"productId\":\"p-1\",\"quantity\":1,\"unitPrice\":5.00}]}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            ids.add(jsonMapper.readTree(result.getResponse().getContentAsString()).path("id").asString());
            Thread.sleep(5); // distinct createdAt values, so "newest first" is well-defined
        }
        return ids;
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    private static String customer() {
        return "t01-" + UUID.randomUUID();
    }
}
