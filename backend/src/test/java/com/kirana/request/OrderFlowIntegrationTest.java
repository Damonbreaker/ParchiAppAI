package com.kirana.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.kirana.IntegrationTestBase;

class OrderFlowIntegrationTest extends IntegrationTestBase {

    OwnerStore near;   // quotes 380, packing 20
    OwnerStore mid;    // quotes 400, packing 10
    OwnerStore silent; // targeted, never quotes
    String customer;
    long requestId;
    long item1;
    long item2;
    long nearQuote;
    long midQuote;

    @BeforeEach
    void setUp() throws Exception {
        near = ownerWithStore("9800000001", "Near Store", LAT + 0.3 * KM, LNG, true);
        mid = ownerWithStore("9800000002", "Mid Store", LAT + 1.0 * KM, LNG, true);
        silent = ownerWithStore("9800000003", "Silent Store", LAT + 1.5 * KM, LNG, true);
        customer = register("9811111111", "CUSTOMER");
        JsonNode r = read(mvc.perform(post("/api/requests").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"NEAREST","lat":%s,"lng":%s,"items":[
                                 {"name":"Aashirvaad atta","quantity":5,"unit":"KG"},
                                 {"name":"Eggs","quantity":1,"unit":"DOZEN"}]}""".formatted(LAT, LNG)))
                .andExpect(status().isCreated()));
        requestId = r.get("id").asLong();
        item1 = r.get("items").get(0).get("id").asLong();
        item2 = r.get("items").get(1).get("id").asLong();
        nearQuote = read(quote(near, 20, 296, 84).andExpect(status().isOk())).get("quoteId").asLong();
        midQuote = read(quote(mid, 10, 310, 90).andExpect(status().isOk())).get("quoteId").asLong();
    }

    @Test
    void twoConcurrentAcceptsExactlyOneWins() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<MockHttpServletResponse>> calls = List.of(
                    () -> { start.await(); return accept(nearQuote, "380").andReturn().getResponse(); },
                    () -> { start.await(); return accept(midQuote, "400").andReturn().getResponse(); });
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (Callable<MockHttpServletResponse> c : calls) {
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            String loserBody = null;
            for (Future<MockHttpServletResponse> f : futures) {
                MockHttpServletResponse res = f.get(30, TimeUnit.SECONDS);
                codes.add(res.getStatus());
                if (res.getStatus() == 409) {
                    loserBody = res.getContentAsString();
                }
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
            assertThat(json.readTree(loserBody).get("code").asText()).isEqualTo("REQUEST_NOT_OPEN");
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from quotes where status = 'ACCEPTED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from quotes where status = 'REJECTED'", Integer.class))
                .isEqualTo(1);
        Long acceptedId = jdbc.queryForObject("select accepted_quote_id from order_requests", Long.class);
        assertThat(jdbc.queryForObject("select id from quotes where status = 'ACCEPTED'", Long.class))
                .isEqualTo(acceptedId);
        assertThat(jdbc.queryForObject("select status from order_requests", String.class)).isEqualTo("ACCEPTED");
    }

    @Test
    void quoteChangedWhenStoreEditedThePriceAfterCustomerLoadedIt() throws Exception {
        // customer saw 380; store then lowers the atta price -> 370
        quote(near, 20, 286, 84).andExpect(status().isOk());

        accept(nearQuote, "380")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_CHANGED"))
                .andExpect(jsonPath("$.quote.quoteId").value(nearQuote))
                .andExpect(jsonPath("$.quote.totalPrice").value(370))
                .andExpect(jsonPath("$.quote.items", hasSize(2)))
                .andExpect(jsonPath("$.quote.phone").doesNotExist());
        assertThat(jdbc.queryForObject("select status from order_requests", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForList("select status from quotes", String.class)).containsOnly("SUBMITTED");

        accept(nearQuote, "370.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void storePhoneVisibleToCustomerOnlyAfterAcceptAndOnlyForAcceptedStore() throws Exception {
        String before = mvc.perform(get("/api/requests/" + requestId).header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.acceptedStore").value(nullValue()))
                .andExpect(jsonPath("$.pickupCode").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat(before).doesNotContain("9800000001", "9800000002", "9800000003");

        String after = accept(nearQuote, "380")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedQuoteId").value(nearQuote))
                .andExpect(jsonPath("$.pickupCode", matchesPattern("[0-9]{4}")))
                .andExpect(jsonPath("$.acceptedStore.name").value("Near Store"))
                .andExpect(jsonPath("$.acceptedStore.phone").value("9800000001"))
                .andExpect(jsonPath("$.acceptedStore.address").value("Main Market"))
                .andExpect(jsonPath("$.acceptedStore.lat").exists())
                .andExpect(jsonPath("$.quotes[?(@.quoteId == %d)].status".formatted(nearQuote)).value("ACCEPTED"))
                .andExpect(jsonPath("$.quotes[?(@.quoteId == %d)].status".formatted(midQuote)).value("REJECTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(after).doesNotContain("9800000002", "9800000003");

        JsonNode d = json.readTree(after);
        Instant acceptedAt = Instant.parse(d.get("acceptedAt").asText());
        assertThat(Instant.parse(d.get("readyBy").asText())).isEqualTo(acceptedAt.plus(Duration.ofMinutes(20)));

        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].acceptedStoreName").value("Near Store"))
                .andExpect(jsonPath("$.items[0].acceptedTotal").value(380))
                .andExpect(jsonPath("$.items[0].readyBy").value(d.get("readyBy").asText()));
    }

    @Test
    void winningStoreSeesCustomerLosingStoreSeesLostAndCannotMoveTheOrder() throws Exception {
        accept(nearQuote, "380").andExpect(status().isOk());

        // winner
        String won = mvc.perform(get("/api/store/requests/" + requestId).header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.outcome").value("WON"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.customer.name").value("Test"))
                .andExpect(jsonPath("$.customer.phone").value("9811111111"))
                .andExpect(jsonPath("$.acceptedAt").isNotEmpty())
                .andExpect(jsonPath("$.readyBy").isNotEmpty())
                .andExpect(jsonPath("$.pickupCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String code = jdbc.queryForObject("select pickup_code from order_requests", String.class);
        assertThat(won).doesNotContain("\"" + code + "\"");
        mvc.perform(get("/api/store/requests?status=ORDERS").header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].outcome").value("WON"))
                .andExpect(jsonPath("$.items[0].customerName").value("Test"))
                .andExpect(jsonPath("$.items[0].myQuoteTotal").value(380))
                .andExpect(jsonPath("$.items[0].readyBy").isNotEmpty());
        mvc.perform(get("/api/store/requests?status=OPEN").header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.items", hasSize(0)));

        // losers: quoted (mid) and never quoted (silent)
        for (OwnerStore loser : List.of(mid, silent)) {
            String lost = mvc.perform(get("/api/store/requests/" + requestId)
                            .header("Authorization", bearer(loser.token())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("LOST"))
                    .andExpect(jsonPath("$.customer").value(nullValue()))
                    .andExpect(jsonPath("$.acceptedAt").value(nullValue()))
                    .andExpect(jsonPath("$.readyBy").value(nullValue()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(lost).doesNotContain("9811111111", "Near Store", "\"" + code + "\"");
            mvc.perform(get("/api/store/requests?status=CLOSED").header("Authorization", bearer(loser.token())))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].outcome").value("LOST"))
                    .andExpect(jsonPath("$.items[0].customerName").value(nullValue()));
            mvc.perform(get("/api/store/requests?status=ORDERS").header("Authorization", bearer(loser.token())))
                    .andExpect(jsonPath("$.items", hasSize(0)));
            mvc.perform(post("/api/store/requests/" + requestId + "/ready")
                            .header("Authorization", bearer(loser.token())))
                    .andExpect(status().isNotFound());
            pickedUp(loser, code).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/store/requests/" + requestId).header("Authorization", bearer(mid.token())))
                .andExpect(jsonPath("$.myQuote.status").value("REJECTED"));
    }

    @Test
    void readyThenPickupNeedsTheRightCode() throws Exception {
        accept(nearQuote, "380").andExpect(status().isOk());
        String code = jdbc.queryForObject("select pickup_code from order_requests", String.class);
        String wrong = code.equals("0000") ? "1111" : "0000";

        mvc.perform(post("/api/store/requests/" + requestId + "/ready").header("Authorization", bearer(near.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.readyAt").isNotEmpty());
        mvc.perform(post("/api/store/requests/" + requestId + "/ready").header("Authorization", bearer(near.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        pickedUp(near, wrong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PICKUP_CODE"));
        pickedUp(near, "12a").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.pickupCode").value("must be 4 digits"));
        assertThat(jdbc.queryForObject("select status from order_requests", String.class)).isEqualTo("READY");

        pickedUp(near, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.pickedUpAt").isNotEmpty());
        pickedUp(near, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        mvc.perform(get("/api/store/requests?status=CLOSED").header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.items[0].outcome").value("WON"));
        mvc.perform(get("/api/store/requests?status=ORDERS").header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/requests/" + requestId).header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.readyAt").isNotEmpty())
                .andExpect(jsonPath("$.pickedUpAt").isNotEmpty());
    }

    @Test
    void pickupStraightFromAcceptedIsAllowed() throws Exception {
        accept(nearQuote, "380").andExpect(status().isOk());
        String code = jdbc.queryForObject("select pickup_code from order_requests", String.class);
        pickedUp(near, code).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED_UP"));
    }

    @Test
    void quoteEditAfterAcceptIs409() throws Exception {
        accept(nearQuote, "380").andExpect(status().isOk());

        quote(near, 15, 290, 84).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CLOSED"));
        quote(mid, 15, 290, 84).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CLOSED"));
        quote(silent, 15, 290, 84).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select total_price::text from quotes where id = ?", String.class, nearQuote))
                .isEqualTo("380.00");
    }

    @Test
    void acceptRules() throws Exception {
        accept(999_999L, "380").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        String other = register("9822222222", "CUSTOMER");
        mvc.perform(post("/api/requests/" + requestId + "/accept/" + nearQuote).header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\":380}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        mvc.perform(post("/api/requests/" + requestId + "/accept/" + nearQuote).header("Authorization",
                        bearer(customer)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.expectedTotal").exists());
        mvc.perform(post("/api/requests/" + requestId + "/accept/" + nearQuote).header("Authorization",
                        bearer(near.token())).contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\":380}"))
                .andExpect(status().isForbidden());

        accept(nearQuote, "380").andExpect(status().isOk());
        accept(midQuote, "400").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_OPEN"));
        mvc.perform(post("/api/requests/" + requestId + "/cancel").header("Authorization", bearer(customer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void acceptingACancelledRequestIs409() throws Exception {
        mvc.perform(post("/api/requests/" + requestId + "/cancel").header("Authorization", bearer(customer)))
                .andExpect(status().isOk());
        accept(nearQuote, "380").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_OPEN"));
        mvc.perform(get("/api/store/requests/" + requestId).header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.outcome").value("CANCELLED"));
        mvc.perform(get("/api/store/requests?status=CLOSED").header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.items[0].outcome").value("CANCELLED"));
    }

    // ------------------------------------------------------------ helpers

    private ResultActions quote(OwnerStore store, int packing, int attaPrice, int eggsPrice) throws Exception {
        String body = """
                {"packingTimeMins":%d,"items":[
                 {"requestItemId":%d,"offeredQty":5,"unit":"KG","linePrice":%d,"status":"AVAILABLE"},
                 {"requestItemId":%d,"offeredQty":1,"unit":"DOZEN","linePrice":%d,"status":"AVAILABLE"}]}"""
                .formatted(packing, item1, attaPrice, item2, eggsPrice);
        return mvc.perform(put("/api/store/requests/" + requestId + "/quote")
                .header("Authorization", bearer(store.token()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions accept(long quoteId, String expectedTotal) throws Exception {
        return mvc.perform(post("/api/requests/" + requestId + "/accept/" + quoteId)
                .header("Authorization", bearer(customer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTotal\":" + expectedTotal + "}"));
    }

    private ResultActions pickedUp(OwnerStore store, String code) throws Exception {
        return mvc.perform(post("/api/store/requests/" + requestId + "/picked-up")
                .header("Authorization", bearer(store.token()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pickupCode\":\"" + code + "\"}"));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
