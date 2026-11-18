package com.kirana.store;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.kirana.IntegrationTestBase;

class StoreFlowIntegrationTest extends IntegrationTestBase {

    static final double LAT = 28.5050;
    static final double LNG = 77.3800;
    static final double KM = 1 / 111.32; // degrees of latitude per km

    @Test
    void ownerCanHaveOnlyOneStore() throws Exception {
        String owner = register("9800000001", "STORE_OWNER");

        mvc.perform(get("/api/stores/mine").header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_STORE"));

        createStore(owner, "Sharma General Store", "Sector 93", LAT, LNG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("9800000001")) // defaults to owner's phone
                .andExpect(jsonPath("$.isOpen").value(true));

        createStore(owner, "Second Store", "Sector 93", LAT, LNG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_EXISTS"));
    }

    @Test
    void ownerCanToggleOpen() throws Exception {
        String owner = register("9800000001", "STORE_OWNER");
        createStore(owner, "Gupta Kirana", "Sector 128", LAT, LNG).andExpect(status().isCreated());

        mvc.perform(patch("/api/stores/mine").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isOpen\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOpen").value(false))
                .andExpect(jsonPath("$.name").value("Gupta Kirana"));
    }

    @Test
    void storeValidation() throws Exception {
        String owner = register("9800000001", "STORE_OWNER");
        mvc.perform(post("/api/stores").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","address":"","area":"Sector 93","lat":100,"lng":-200}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.address").exists())
                .andExpect(jsonPath("$.fields.lat").value("must be between -90 and 90"))
                .andExpect(jsonPath("$.fields.lng").value("must be between -180 and 180"));

        String customer = register("9800000009", "CUSTOMER");
        mvc.perform(get("/api/stores/nearby?lat=200&lng=77.38").header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.lat").exists());
        mvc.perform(get("/api/stores/nearby?lng=77.38").header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.lat").value("is required"));
    }

    @Test
    void rolesAreEnforced() throws Exception {
        String customer = register("9800000009", "CUSTOMER");
        String owner = register("9800000001", "STORE_OWNER");

        createStore(customer, "X", "Y", LAT, LNG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/stores/mine").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/stores/mine").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isOpen\":false}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/stores/nearby?lat=28.5&lng=77.38").header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nearbyExcludesClosedAndFarAndSortsByDistance() throws Exception {
        String b = storeWithOwner("9800000001", "B Store 1.5km", "Sector 93", LAT + 1.5 * KM, LNG, true);
        String a = storeWithOwner("9800000002", "A Store 0.5km", "Sector 93", LAT - 0.5 * KM, LNG, true);
        storeWithOwner("9800000003", "Closed 1km", "Sector 93", LAT + 1.0 * KM, LNG, false);
        storeWithOwner("9800000004", "Far 4km", "Sector 93", LAT + 4.0 * KM, LNG, true);
        String customer = register("9800000009", "CUSTOMER");

        mvc.perform(get("/api/stores/nearby").param("lat", "" + LAT).param("lng", "" + LNG)
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value(a))
                .andExpect(jsonPath("$[0].distanceKm").value(0.5))
                .andExpect(jsonPath("$[1].name").value(b))
                .andExpect(jsonPath("$[1].distanceKm").value(1.5));
    }

    @Test
    void searchMatchesAreaAndNameIncludingHindiAndFlagsClosed() throws Exception {
        storeWithOwner("9800000001", "Gupta Kirana", "Sector 128", LAT + 2 * KM, LNG, true);
        storeWithOwner("9800000002", "Bansal Grocery", "sector 128", LAT + 1 * KM, LNG, false);
        storeWithOwner("9800000003", "Sharma General Store", "Sector 93", LAT, LNG, true);
        storeWithOwner("9800000004", "हरि ओम किराना", "Sector 104", LAT + 8 * KM, LNG, true);
        String customer = register("9800000009", "CUSTOMER");

        // area match, case-insensitive, closed included and flagged, sorted by distance
        mvc.perform(get("/api/stores/search").param("q", "SECTOR 128")
                        .param("lat", "" + LAT).param("lng", "" + LNG)
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Bansal Grocery"))
                .andExpect(jsonPath("$[0].isOpen").value(false))
                .andExpect(jsonPath("$[1].name").value("Gupta Kirana"));

        // Hindi name match, and search is not limited to the 3 km radius
        mvc.perform(get("/api/stores/search").param("q", "किराना")
                        .param("lat", "" + LAT).param("lng", "" + LNG)
                        .header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].distanceKm").value(8.0));

        // no location: sorted by name, no distance
        mvc.perform(get("/api/stores/search").param("q", "kirana").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Gupta Kirana"))
                .andExpect(jsonPath("$[0].distanceKm").doesNotExist());

        // empty q behaves like nearby (closed and far stores dropped)
        mvc.perform(get("/api/stores/search").param("q", " ")
                        .param("lat", "" + LAT).param("lng", "" + LNG)
                        .header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Sharma General Store"));

        mvc.perform(get("/api/stores/search").header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.lat").exists());
    }

    @Test
    void customerResponsesNeverIncludePhone() throws Exception {
        String owner = register("9800000001", "STORE_OWNER");
        String body = createStore(owner, "Gupta Kirana", "Sector 128", LAT + 1 * KM, LNG)
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        String customer = register("9800000009", "CUSTOMER");

        mvc.perform(get("/api/stores/nearby").param("lat", "" + LAT).param("lng", "" + LNG)
                        .header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$[0].name").value("Gupta Kirana"))
                .andExpect(jsonPath("$[0].phone").doesNotExist());
        mvc.perform(get("/api/stores/search").param("q", "gupta").header("Authorization", bearer(customer)))
                .andExpect(jsonPath("$[0].phone").doesNotExist());
        mvc.perform(get("/api/stores/" + id).param("lat", "" + LAT).param("lng", "" + LNG)
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gupta Kirana"))
                .andExpect(jsonPath("$.distanceKm").value(lessThan(1.1)))
                .andExpect(jsonPath("$.phone").doesNotExist());

        mvc.perform(get("/api/stores/999999").header("Authorization", bearer(customer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    // ------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions createStore(
            String token, String name, String area, double lat, double lng) throws Exception {
        return mvc.perform(post("/api/stores").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "name", name, "address", "Main Market", "area", area, "lat", lat, "lng", lng))));
    }

    private String storeWithOwner(String phone, String name, String area, double lat, double lng, boolean open)
            throws Exception {
        String owner = register(phone, "STORE_OWNER");
        createStore(owner, name, area, lat, lng).andExpect(status().isCreated());
        if (!open) {
            mvc.perform(patch("/api/stores/mine").header("Authorization", bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"isOpen\":false}"))
                    .andExpect(status().isOk());
        }
        return name;
    }
}
