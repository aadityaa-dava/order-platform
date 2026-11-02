package dev.aadityaa.orderplatform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aadityaa.orderplatform.order.api.CreateOrderRequest;
import dev.aadityaa.orderplatform.order.application.OrderService;
import dev.aadityaa.orderplatform.platform.messaging.OutboxWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false"
})
@AutoConfigureMockMvc
class OrderApiIT extends AbstractOrderIT {

    private static final String BODY = """
            {"customerId":"cust-1","customerEmail":"jane@example.com","currency":"USD",
             "items":[{"sku":"SKU-RED-MUG","quantity":2,"unitPriceMinor":1250}]}""";

    private static final String OTHER_BODY = BODY.replace("\"quantity\":2", "\"quantity\":3");

    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean OutboxWriter outboxWriter;

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE idempotency_keys, order_items, orders, outbox, processed_events CASCADE").update();
    }

    @Test
    void createReturns202WithLocationAndAPendingOrder() throws Exception {
        mvc.perform(post("/orders").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmountMinor").value(2500));

        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void replayWithTheSameKeyAndBodyReturnsTheSameOrder() throws Exception {
        String key = key();

        String first = mvc.perform(post("/orders").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/orders").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.<String>read(first, "$.id"));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedWith422() throws Exception {
        String key = key();
        mvc.perform(post("/orders").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        mvc.perform(post("/orders").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(OTHER_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Idempotency key reuse"));

        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsRejectedWith400() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        assertThat(count("orders")).isZero();
    }

    @Test
    void invalidBodyListsEveryBadField() throws Exception {
        String bad = "{\"customerId\":\"\",\"customerEmail\":\"not-an-email\",\"currency\":\"usd\",\"items\":[]}";

        mvc.perform(post("/orders").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerId").exists())
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.errors.items").exists());

        assertThat(count("orders")).isZero();
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mvc.perform(get("/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void getReturnsTheOrderWithItsItems() throws Exception {
        String created = mvc.perform(post("/orders").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("SKU-RED-MUG"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        String key = key();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return orders.create(key, request()).id();
            }));
        }
        start.countDown();
        Set<UUID> ids = new HashSet<>();
        for (Future<UUID> future : futures) {
            ids.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(ids).hasSize(1); // every caller got the same order back
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("idempotency_keys")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void ifTheOutboxWriteFailsTheOrderIsNotSavedEither() {
        doThrow(new IllegalStateException("boom")).when(outboxWriter)
                .write(any(), any(), any(), any(), anyInt(), any());

        assertThatThrownBy(() -> orders.create(key(), request())).isInstanceOf(IllegalStateException.class);

        // the transactional-outbox promise: the state change and its event commit together or not at all
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(count("idempotency_keys")).isZero();
        assertThat(count("outbox")).isZero();
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static CreateOrderRequest request() {
        return new CreateOrderRequest("cust-1", "jane@example.com", "USD",
                List.of(new CreateOrderRequest.Item("SKU-RED-MUG", 2, 1250)));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
