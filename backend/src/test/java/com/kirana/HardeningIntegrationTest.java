package com.kirana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

/** Phase 7 Part B over HTTP: pickup lock, rate limits, pagination. */
class HardeningIntegrationTest extends IntegrationTestBase {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};

    OwnerStore store;
    String customer;

    @BeforeEach
    void setUp() throws Exception {
        store = ownerWithStore("9800000201", "Near Store", LAT + 0.3 * KM, LNG, true);
        customer = register("9811111201", "CUSTOMER");
    }

    // ------------------------------------------------------------ pickup lock

    @Test
    void sixWrongPickupCodesLockTheOrder() throws Exception {
        long id = acceptedOrder();
        for (int i = 1; i <= 5; i++) {
            pickedUp(id, "0000".equals(code(id)) ? "1111" : "0000")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WRONG_PICKUP_CODE"));
        }
        pickedUp(id, code(id)) // 6th try, even the right code
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("select status from order_requests where id = ?", String.class, id))
                .isEqualTo("ACCEPTED");

        // after the lock expires the right code works and clears the counter
        jdbc.update("update order_requests set pickup_locked_until = now() - interval '1 second' where id = ?", id);
        pickedUp(id, code(id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED_UP"));
        assertThat(jdbc.queryForObject("select pickup_failed_attempts from order_requests where id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void parallelWrongCodesAreCountedOneByOne() throws Exception {
        long id = acceptedOrder();
        String wrong = "0000".equals(code(id)) ? "1111" : "0000";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> codes = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return pickedUp(id, wrong).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> f : futures) {
                codes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(codes).filteredOn(c -> c == 400).hasSize(5);
        assertThat(codes).filteredOn(c -> c == 429).hasSize(5);
        assertThat(jdbc.queryForObject("select pickup_failed_attempts from order_requests where id = ?",
                Integer.class, id)).isEqualTo(5);
    }

    // ------------------------------------------------------------ rate limits

    @Test
    void fiveFailedLoginsBlockThatPhone() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("9811111201", "wrong-pass").andExpect(status().isUnauthorized());
        }
        login("9811111201", "secret1") // right password, still blocked
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        register("9811111202", "CUSTOMER");
        login("9811111202", "secret1").andExpect(status().isOk()); // other phones unaffected
    }

    @Test
    void successfulLoginClearsFailures() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("9811111201", "wrong-pass").andExpect(status().isUnauthorized());
        }
        login("9811111201", "secret1").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login("9811111201", "wrong-pass").andExpect(status().isUnauthorized());
        }
        login("9811111201", "secret1").andExpect(status().isOk());
    }

    @Test
    void twentyScansPerHour() throws Exception {
        for (int i = 0; i < 20; i++) {
            scan().andExpect(status().isOk());
        }
        scan().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    void tenRequestsPerHourAndFailedOnesDontCount() throws Exception {
        mvc.perform(post("/api/requests").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NEAREST\"}"))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 10; i++) {
            createRequest(1).andExpect(status().isCreated());
        }
        createRequest(1).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    // ------------------------------------------------------------ pagination

    @Test
    void mineAndInboxArePaged() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(read(createRequest(1)).get("id").asLong());
        }
        mvc.perform(get("/api/requests/mine?page=0&size=2").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/requests/mine?page=1&size=2").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(get("/api/store/requests?status=OPEN&size=2").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/store/requests?page=1&size=2").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(get("/api/requests/mine?size=51").header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.size").value("must be between 1 and 50"));
        mvc.perform(get("/api/store/requests?page=-1").header("Authorization", bearer(store.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.page").value("must be 0 or more"));
    }

    // ------------------------------------------------------------ helpers

    /** A request with one item, quoted by the store and accepted by the customer. */
    private long acceptedOrder() throws Exception {
        JsonNode r = read(createRequest(1).andExpect(status().isCreated()));
        long id = r.get("id").asLong();
        long item = r.get("items").get(0).get("id").asLong();
        long quoteId = read(mvc.perform(put("/api/store/requests/" + id + "/quote")
                        .header("Authorization", bearer(store.token())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"packingTimeMins":10,"items":[
                                 {"requestItemId":%d,"offeredQty":1,"unit":"KG","linePrice":50,"status":"AVAILABLE"}]}"""
                                .formatted(item)))
                .andExpect(status().isOk())).get("quoteId").asLong();
        mvc.perform(post("/api/requests/" + id + "/accept/" + quoteId).header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\":50}"))
                .andExpect(status().isOk());
        return id;
    }

    private String code(long id) {
        return jdbc.queryForObject("select pickup_code from order_requests where id = ?", String.class, id);
    }

    private ResultActions pickedUp(long id, String code) throws Exception {
        return mvc.perform(post("/api/store/requests/" + id + "/picked-up")
                .header("Authorization", bearer(store.token()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pickupCode\":\"" + code + "\"}"));
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions scan() throws Exception {
        return mvc.perform(multipart("/api/parchi/scan")
                .file(new MockMultipartFile("image", "p.png", "image/png", PNG))
                .header("Authorization", bearer(customer)));
    }

    private ResultActions createRequest(int items) throws Exception {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < items; i++) {
            list.append(i == 0 ? "" : ",").append("{\"name\":\"item ").append(i).append("\",\"quantity\":1,\"unit\":\"KG\"}");
        }
        return mvc.perform(post("/api/requests").header("Authorization", bearer(customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"NEAREST\",\"lat\":%s,\"lng\":%s,\"items\":[%s]}".formatted(LAT, LNG, list)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
