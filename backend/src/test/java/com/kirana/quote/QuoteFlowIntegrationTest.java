package com.kirana.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.kirana.IntegrationTestBase;

class QuoteFlowIntegrationTest extends IntegrationTestBase {

    static final String ITEMS = """
            [{"name":"Aashirvaad atta","quantity":5,"unit":"KG"},
             {"name":"चीनी","quantity":2,"unit":"KG"},
             {"name":"Amul butter","quantity":500,"unit":"G"},
             {"name":"Eggs","quantity":1,"unit":"DOZEN"}]""";

    OwnerStore near;
    OwnerStore mid;
    OwnerStore far;
    String customer;
    long requestId;
    List<Long> itemIds;

    @BeforeEach
    void setUp() throws Exception {
        near = ownerWithStore("9800000001", "Near Store", LAT + 0.3 * KM, LNG, true);
        mid = ownerWithStore("9800000002", "Mid Store", LAT + 1.0 * KM, LNG, true);
        far = ownerWithStore("9800000003", "Far 4km", LAT + 4 * KM, LNG, true); // not targeted
        customer = register("9811111111", "CUSTOMER");
        String body = mvc.perform(post("/api/requests").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":" + ITEMS + ",\"lat\":" + LAT + ",\"lng\":" + LNG
                                + ",\"mode\":\"NEAREST\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        requestId = r.get("id").asLong();
        itemIds = new ArrayList<>();
        r.get("items").forEach(i -> itemIds.add(i.get("id").asLong()));
    }

    @Test
    void missingItemIs400() throws Exception {
        List<Map<String, Object>> lines = fullLines();
        lines.remove(2);
        putQuote(near.token(), body(20, lines))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.items").value(
                        "missing requestItemId " + itemIds.get(2)
                                + " (every item of the request must be answered once)"));

        putQuote(near.token(), body(4, fullLines()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.packingTimeMins").value("must be between 5 and 240"));

        List<Map<String, Object>> bad = fullLines();
        bad.get(0).put("linePrice", 0);
        bad.get(1).remove("unit");
        putQuote(near.token(), body(20, bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['items[0].linePrice']").value("must be greater than 0"))
                .andExpect(jsonPath("$.fields['items[1].unit']").value("is required"));
        assertThat(count("quotes")).isZero();
    }

    @Test
    void unavailableForcesZeroAndUnitMayDiffer() throws Exception {
        List<Map<String, Object>> lines = fullLines();
        lines.set(3, line(itemIds.get(3), 12, "DOZEN", 99, "UNAVAILABLE")); // sent values must be ignored
        lines.set(2, line(itemIds.get(2), 1, "PACKET", 285, "AVAILABLE")); // asked 500 G, offered 1 PACKET

        putQuote(near.token(), body(20, lines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[3].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.items[3].offeredQty").value(0))
                .andExpect(jsonPath("$.items[3].linePrice").value(0))
                .andExpect(jsonPath("$.items[2].unit").value("PACKET"))
                .andExpect(jsonPath("$.totalPrice").value(280 + 90 + 285));
    }

    @Test
    void totalIsComputedOnServerIgnoringAnyTotalSent() throws Exception {
        Map<String, Object> body = body(30, fullLines());
        body.put("totalPrice", 1);
        body.put("total", 1);

        putQuote(near.token(), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.totalPrice").value(280 + 90 + 60 + 84))
                .andExpect(jsonPath("$.packingTimeMins").value(30));
        assertThat(jdbc.queryForObject("select total_price::text from quotes", String.class)).isEqualTo("514.00");
    }

    @Test
    void secondPutUpdatesTheSameQuote() throws Exception {
        JsonNode first = read(putQuote(near.token(), body(20, fullLines())).andExpect(status().isOk()));

        List<Map<String, Object>> changed = fullLines();
        changed.set(0, line(itemIds.get(0), 5, "KG", 275.5, "AVAILABLE"));
        changed.set(1, line(itemIds.get(1), 1, "KG", 45, "PARTIAL"));
        JsonNode second = read(putQuote(near.token(), body(45, changed)).andExpect(status().isOk()));

        assertThat(second.get("quoteId").asLong()).isEqualTo(first.get("quoteId").asLong());
        assertThat(second.get("totalPrice").decimalValue()).isEqualByComparingTo("464.50");
        assertThat(second.get("updatedAt").asText()).isNotEqualTo(first.get("updatedAt").asText());
        assertThat(count("quotes")).isEqualTo(1);
        assertThat(count("quote_items")).isEqualTo(4);

        mvc.perform(get("/api/store/requests/" + requestId).header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.myQuote.quoteId").value(first.get("quoteId").asLong()))
                .andExpect(jsonPath("$.myQuote.packingTimeMins").value(45))
                .andExpect(jsonPath("$.myQuote.items[1].status").value("PARTIAL"));
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.items[0].myQuoteTotal").value(464.5));
        mvc.perform(get("/api/store/requests/" + requestId).header("Authorization", bearer(mid.token())))
                .andExpect(jsonPath("$.myQuote").value(nullValue()));
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(mid.token())))
                .andExpect(jsonPath("$.items[0].myQuoteTotal").value(nullValue()));
    }

    @Test
    void quotingACancelledRequestIs409() throws Exception {
        mvc.perform(post("/api/requests/" + requestId + "/cancel").header("Authorization", bearer(customer)))
                .andExpect(status().isOk());

        putQuote(near.token(), body(20, fullLines()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CLOSED"));
        assertThat(count("quotes")).isZero();
    }

    @Test
    void storeNotTargetedIs404() throws Exception {
        putQuote(far.token(), body(20, fullLines()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        putQuote(customer, body(20, fullLines())).andExpect(status().isForbidden());
        assertThat(count("quotes")).isZero();
    }

    @Test
    void cancellingARequestRejectsItsQuotes() throws Exception {
        putQuote(near.token(), body(20, fullLines())).andExpect(status().isOk());
        putQuote(mid.token(), body(30, fullLines())).andExpect(status().isOk());

        mvc.perform(post("/api/requests/" + requestId + "/cancel").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.quotes[*].status", contains("REJECTED", "REJECTED")));
        assertThat(jdbc.queryForList("select status from quotes", String.class))
                .containsOnly("REJECTED");
        mvc.perform(get("/api/store/requests/" + requestId).header("Authorization", bearer(near.token())))
                .andExpect(jsonPath("$.myQuote.status").value("REJECTED"));
    }

    @Test
    void customerDetailShowsAllQuotesWithCounts() throws Exception {
        // near: 1 available, 1 partial, 1 unavailable, 1 available -> 280 + 45 + 0 + 84 = 409
        List<Map<String, Object>> nearLines = fullLines();
        nearLines.set(1, line(itemIds.get(1), 1, "KG", 45, "PARTIAL"));
        nearLines.set(2, line(itemIds.get(2), null, null, null, "UNAVAILABLE"));
        putQuote(near.token(), body(20, nearLines)).andExpect(status().isOk());
        // mid: all available -> 514
        putQuote(mid.token(), body(10, fullLines())).andExpect(status().isOk());

        mvc.perform(get("/api/requests/" + requestId).header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotes", hasSize(2)))
                .andExpect(jsonPath("$.quotes[*].storeName", contains("Near Store", "Mid Store"))) // cheapest first
                .andExpect(jsonPath("$.quotes[0].totalPrice").value(409))
                .andExpect(jsonPath("$.quotes[0].distanceKm").value(0.3))
                .andExpect(jsonPath("$.quotes[0].packingTimeMins").value(20))
                .andExpect(jsonPath("$.quotes[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.quotes[0].availableCount").value(2))
                .andExpect(jsonPath("$.quotes[0].partialCount").value(1))
                .andExpect(jsonPath("$.quotes[0].unavailableCount").value(1))
                .andExpect(jsonPath("$.quotes[0].phone").doesNotExist())
                .andExpect(jsonPath("$.quotes[0].items", hasSize(4)))
                .andExpect(jsonPath("$.quotes[0].items[1].name").value("चीनी"))
                .andExpect(jsonPath("$.quotes[0].items[1].askedQty").value(2))
                .andExpect(jsonPath("$.quotes[0].items[1].askedUnit").value("KG"))
                .andExpect(jsonPath("$.quotes[0].items[1].offeredQty").value(1))
                .andExpect(jsonPath("$.quotes[0].items[1].linePrice").value(45))
                .andExpect(jsonPath("$.quotes[1].availableCount").value(4))
                .andExpect(jsonPath("$.quotes[1].unavailableCount").value(0))
                .andExpect(jsonPath("$.targets[*].quoted", contains(true, true)));

        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items[0].quotesCount").value(2))
                .andExpect(jsonPath("$.items[0].lowestTotal").value(409));
    }

    @Test
    void mineHasNullLowestTotalBeforeQuotes() throws Exception {
        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.items[0].quotesCount").value(0))
                .andExpect(jsonPath("$.items[0].lowestTotal").value(nullValue()));
        mvc.perform(get("/api/requests/" + requestId).header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.quotes", hasSize(0)))
                .andExpect(jsonPath("$.targets[*].quoted", contains(false, false)));
    }

    // ------------------------------------------------------------ helpers

    /** Every item AVAILABLE at the asked quantity: atta 280, cheeni 90, butter 60, eggs 84 (total 514). */
    private List<Map<String, Object>> fullLines() {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(line(itemIds.get(0), 5, "KG", 280, "AVAILABLE"));
        lines.add(line(itemIds.get(1), 2, "KG", 90, "AVAILABLE"));
        lines.add(line(itemIds.get(2), 500, "G", 60, "AVAILABLE"));
        lines.add(line(itemIds.get(3), 1, "DOZEN", 84, "AVAILABLE"));
        return lines;
    }

    private static Map<String, Object> line(long requestItemId, Number qty, String unit, Number price, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestItemId", requestItemId);
        if (qty != null) {
            m.put("offeredQty", qty);
        }
        if (unit != null) {
            m.put("unit", unit);
        }
        if (price != null) {
            m.put("linePrice", price);
        }
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> body(int packingTimeMins, List<Map<String, Object>> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packingTimeMins", packingTimeMins);
        m.put("items", lines);
        return m;
    }

    private ResultActions putQuote(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/store/requests/" + requestId + "/quote").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
