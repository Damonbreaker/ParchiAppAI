package com.kirana.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.kirana.IntegrationTestBase;

class RequestFlowIntegrationTest extends IntegrationTestBase {

    static final String ITEMS = """
            [{"name":"Aashirvaad atta","quantity":5,"unit":"KG"},
             {"name":"चीनी","quantity":0.5,"unit":"KG"},
             {"name":"Amul butter","quantity":1,"unit":"PACKET"},
             {"name":"Eggs","quantity":1,"unit":"DOZEN"}]""";

    @Test
    void nearestPicksAtMostFiveOpenStoresSortedByDistance() throws Exception {
        // created out of order on purpose
        ownerWithStore("9800000006", "S 1.8km", LAT + 1.8 * KM, LNG, true);
        ownerWithStore("9800000002", "S 0.6km", LAT + 0.6 * KM, LNG, true);
        ownerWithStore("9800000005", "S 1.5km", LAT - 1.5 * KM, LNG, true);
        ownerWithStore("9800000001", "S 0.3km", LAT - 0.3 * KM, LNG, true);
        ownerWithStore("9800000004", "S 1.2km", LAT + 1.2 * KM, LNG, true);
        ownerWithStore("9800000003", "S 0.9km", LAT - 0.9 * KM, LNG, true);
        ownerWithStore("9800000007", "Closed 0.1km", LAT + 0.1 * KM, LNG, false);
        ownerWithStore("9800000008", "Far 4km", LAT + 4 * KM, LNG, true);
        String customer = register("9811111111", "CUSTOMER");

        createRequest(customer, "NEAREST", null, LAT, LNG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.mode").value("NEAREST"))
                .andExpect(jsonPath("$.targets", hasSize(5)))
                .andExpect(jsonPath("$.targets[*].storeName",
                        contains("S 0.3km", "S 0.6km", "S 0.9km", "S 1.2km", "S 1.5km")))
                .andExpect(jsonPath("$.targets[0].distanceKm").value(0.3))
                .andExpect(jsonPath("$.targets[0].seen").value(false))
                .andExpect(jsonPath("$.targets[0].phone").doesNotExist())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[1].name").value("चीनी"))
                .andExpect(jsonPath("$.items[1].quantity").value(0.5))
                .andExpect(jsonPath("$.items[1].position").value(2))
                .andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    @Test
    void noStoresNearbyIs422AndSavesNothing() throws Exception {
        ownerWithStore("9800000001", "Far 4km", LAT + 4 * KM, LNG, true);
        ownerWithStore("9800000002", "Closed near", LAT, LNG, false);
        String customer = register("9811111111", "CUSTOMER");

        createRequest(customer, "NEAREST", null, LAT, LNG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_STORES_NEARBY"));
        assertThat(jdbc.queryForObject("select count(*) from order_requests", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from request_items", Integer.class)).isZero();
    }

    @Test
    void directRules() throws Exception {
        OwnerStore closed = ownerWithStore("9800000001", "Closed", LAT, LNG, false);
        OwnerStore far = ownerWithStore("9800000002", "Far but chosen 8km", LAT + 8 * KM, LNG, true);
        String customer = register("9811111111", "CUSTOMER");

        createRequest(customer, "DIRECT", closed.storeId(), LAT, LNG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_CLOSED"));

        createRequest(customer, "DIRECT", far.storeId(), LAT, LNG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets", hasSize(1)))
                .andExpect(jsonPath("$.targets[0].distanceKm").value(8.0));

        createRequest(customer, "DIRECT", null, LAT, LNG)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.storeId").value("is required for DIRECT"));
        createRequest(customer, "DIRECT", 999999L, LAT, LNG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        createRequest(customer, "NEAREST", far.storeId(), LAT, LNG)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.storeId").exists());
    }

    @Test
    void itemValidation() throws Exception {
        String customer = register("9811111111", "CUSTOMER");

        postRequest(customer, """
                {"items":[],"lat":28.5,"lng":77.38,"mode":"NEAREST"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.items").value("must have 1 to 50 items"));

        postRequest(customer, """
                {"items":[{"name":"","quantity":0,"unit":"KG"},{"name":"Atta","quantity":1.2345,"unit":"KG"}],
                 "lat":28.5,"lng":77.38,"mode":"NEAREST"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['items[0].name']").exists())
                .andExpect(jsonPath("$.fields['items[0].quantity']").value("must be greater than 0"))
                .andExpect(jsonPath("$.fields['items[1].quantity']").value("must have at most 3 decimals"));

        postRequest(customer, """
                {"items":[{"name":"Atta","quantity":1,"unit":"BORI"}],"lat":28.5,"lng":77.38,"mode":"NEAREST"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['items[0].unit']").exists());

        String fiftyOne = "[" + String.join(",",
                java.util.Collections.nCopies(51, "{\"name\":\"x\",\"quantity\":1,\"unit\":\"PCS\"}")) + "]";
        postRequest(customer, "{\"items\":" + fiftyOne + ",\"lat\":28.5,\"lng\":77.38,\"mode\":\"NEAREST\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.items").exists());
    }

    @Test
    void mineListsNewestFirstWithSummary() throws Exception {
        ownerWithStore("9800000001", "A", LAT, LNG, true);
        ownerWithStore("9800000002", "B", LAT + KM, LNG, true);
        String customer = register("9811111111", "CUSTOMER");
        long first = id(createRequest(customer, "NEAREST", null, LAT, LNG));
        long second = id(createRequest(customer, "NEAREST", null, LAT, LNG));

        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.items[0].itemCount").value(4))
                .andExpect(jsonPath("$.items[0].storesSentCount").value(2))
                .andExpect(jsonPath("$.items[0].quotesCount").value(0))
                .andExpect(jsonPath("$.items[0].preview", contains("Aashirvaad atta", "चीनी", "Amul butter")));
    }

    @Test
    void customerCannotSeeAnotherCustomersRequest() throws Exception {
        ownerWithStore("9800000001", "A", LAT, LNG, true);
        String alice = register("9811111111", "CUSTOMER");
        String bob = register("9822222222", "CUSTOMER");
        long id = id(createRequest(alice, "NEAREST", null, LAT, LNG));

        mvc.perform(get("/api/requests/" + id).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        mvc.perform(post("/api/requests/" + id + "/cancel").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void storeCannotSeeRequestNotSentToIt() throws Exception {
        ownerWithStore("9800000001", "Near", LAT, LNG, true);
        OwnerStore far = ownerWithStore("9800000002", "Far 4km", LAT + 4 * KM, LNG, true);
        String noStoreOwner = register("9800000003", "STORE_OWNER");
        String customer = register("9811111111", "CUSTOMER");
        long id = id(createRequest(customer, "NEAREST", null, LAT, LNG));

        mvc.perform(get("/api/store/requests/" + id).header("Authorization", bearer(far.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(far.token())))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(noStoreOwner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_STORE"));
    }

    @Test
    void openingARequestMarksItSeen() throws Exception {
        OwnerStore store = ownerWithStore("9800000001", "Near", LAT + 0.5 * KM, LNG, true);
        String customer = register("9811111111", "CUSTOMER");
        long id = id(createRequest(customer, "NEAREST", null, LAT, LNG));

        mvc.perform(get("/api/store/requests").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].isNew").value(true))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.5))
                .andExpect(jsonPath("$.items[0].itemCount").value(4))
                .andExpect(jsonPath("$.items[0].preview", hasSize(3)));

        mvc.perform(get("/api/store/requests/" + id).header("Authorization", bearer(store.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.seenAt").isNotEmpty())
                .andExpect(jsonPath("$.customer").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.lat").doesNotExist());

        String firstSeen = json.readTree(mvc.perform(get("/api/store/requests/" + id)
                .header("Authorization", bearer(store.token()))).andReturn().getResponse().getContentAsString())
                .get("seenAt").asText();
        String secondSeen = json.readTree(mvc.perform(get("/api/store/requests/" + id)
                .header("Authorization", bearer(store.token()))).andReturn().getResponse().getContentAsString())
                .get("seenAt").asText();
        assertThat(secondSeen).isEqualTo(firstSeen); // only set the first time

        mvc.perform(get("/api/store/requests").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items[0].isNew").value(false));
        mvc.perform(get("/api/requests/" + id).header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$.targets[0].seen").value(true));
    }

    @Test
    void cancelOnlyFromOpenAndMovesToClosedInbox() throws Exception {
        OwnerStore store = ownerWithStore("9800000001", "Near", LAT, LNG, true);
        String customer = register("9811111111", "CUSTOMER");
        long id = id(createRequest(customer, "NEAREST", null, LAT, LNG));

        mvc.perform(get("/api/store/requests?status=OPEN").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(1)));

        mvc.perform(post("/api/requests/" + id + "/cancel").header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/requests/" + id + "/cancel").header("Authorization", bearer(customer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        mvc.perform(get("/api/store/requests?status=OPEN").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/store/requests?status=CLOSED").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(store.token())))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/store/requests?status=WHATEVER").header("Authorization", bearer(store.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.status").exists());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        OwnerStore store = ownerWithStore("9800000001", "Near", LAT, LNG, true);
        String customer = register("9811111111", "CUSTOMER");

        createRequest(store.token(), "NEAREST", null, LAT, LNG).andExpect(status().isForbidden());
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ helpers

    private ResultActions createRequest(String token, String mode, Long storeId, double lat, double lng)
            throws Exception {
        String body = "{\"items\":" + ITEMS + ",\"lat\":" + lat + ",\"lng\":" + lng + ",\"mode\":\"" + mode + "\""
                + (storeId != null ? ",\"storeId\":" + storeId : "") + "}";
        return postRequest(token, body);
    }

    private ResultActions postRequest(String token, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/requests")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long id(ResultActions result) throws Exception {
        JsonNode node = json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        return node.get("id").asLong();
    }

}
