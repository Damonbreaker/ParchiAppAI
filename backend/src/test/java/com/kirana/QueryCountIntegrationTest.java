package com.kirana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.EntityManagerFactory;

/**
 * The read endpoints run a fixed number of SQL statements, however many items, quotes and requests there
 * are (no N+1); and the inbox / nearby queries use indexes on a few thousand rows (EXPLAIN).
 */
class QueryCountIntegrationTest extends IntegrationTestBase {

    @Autowired EntityManagerFactory emf;

    final List<OwnerStore> stores = new ArrayList<>();
    String customer;
    Statistics stats;

    @BeforeEach
    void setUp() throws Exception {
        for (int i = 0; i < 5; i++) {
            stores.add(ownerWithStore("980000030" + i, "Store " + i, LAT + (0.2 + 0.3 * i) * KM, LNG, true));
        }
        customer = register("9811111301", "CUSTOMER");
        stats = emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void readEndpointsDoNotGrowWithData() throws Exception {
        long small = request(1, 1);
        long smallDetail = count("/api/requests/" + small, customer);
        long smallMine = count("/api/requests/mine", customer);
        long smallInbox = count("/api/store/requests", stores.get(0).token());
        long smallStoreDetail = count("/api/store/requests/" + small, stores.get(0).token());

        long big = request(20, 5);
        for (int i = 0; i < 4; i++) {
            request(3 + i, 2 + i % 3);
        }
        assertThat(count("/api/requests/" + big, customer)).as("customer detail").isEqualTo(smallDetail);
        assertThat(count("/api/requests/mine", customer)).as("mine").isEqualTo(smallMine);
        assertThat(count("/api/store/requests", stores.get(0).token())).as("store inbox").isEqualTo(smallInbox);
        assertThat(count("/api/store/requests/" + big, stores.get(0).token())).as("store detail")
                .isEqualTo(smallStoreDetail);

        // and the fixed numbers stay small
        assertThat(smallDetail).isLessThanOrEqualTo(8);
        assertThat(smallMine).isLessThanOrEqualTo(8);
        assertThat(smallInbox).isLessThanOrEqualTo(8);
    }

    @Test
    void inboxAndNearbyUseIndexes() {
        // 3,000 owners + stores in a 60 x 50 grid ~1 km apart, 6,000 requests, 2 targets each.
        jdbc.update("""
                INSERT INTO users (name, phone, password_hash, role)
                SELECT 'Owner ' || g, '7' || lpad(g::text, 9, '0'), 'x', 'STORE_OWNER' FROM generate_series(1, 3000) g""");
        jdbc.update("""
                INSERT INTO stores (owner_id, name, address, area, phone, lat, lng, is_open)
                SELECT u.id, 'Store ' || u.id, 'Road', 'Area', u.phone,
                       28.0 + (u.id % 60) * 0.009, 77.0 + (u.id / 60) * 0.01, u.id % 4 <> 0
                  FROM users u WHERE u.role = 'STORE_OWNER' AND u.phone LIKE '7%'""");
        jdbc.update("""
                INSERT INTO users (name, phone, password_hash, role) VALUES ('Bulk customer', '6999999999', 'x', 'CUSTOMER')""");
        jdbc.update("""
                INSERT INTO order_requests (customer_id, lat, lng, source, mode, status)
                SELECT (SELECT id FROM users WHERE phone = '6999999999'), 28.3, 77.2, 'MANUAL', 'NEAREST', 'OPEN'
                  FROM generate_series(1, 6000)""");
        jdbc.update("""
                INSERT INTO request_target (request_id, store_id, distance_km)
                SELECT r.id, s.id, 1.0
                  FROM order_requests r
                  JOIN LATERAL (SELECT id FROM stores ORDER BY id OFFSET (r.id % 1500) * 2 LIMIT 2) s ON TRUE""");
        jdbc.execute("ANALYZE");
        long storeId = jdbc.queryForObject("SELECT min(store_id) FROM request_target", Long.class);

        String inbox = explain("""
                SELECT t.*, r.* FROM request_target t JOIN order_requests r ON r.id = t.request_id
                 WHERE t.store_id = %d ORDER BY r.created_at DESC, r.id DESC LIMIT 21""".formatted(storeId));
        assertThat(inbox).as(inbox).doesNotContain("Seq Scan on request_target")
                .doesNotContain("Seq Scan on order_requests");

        String nearby = explain("""
                SELECT s.id FROM stores s
                 WHERE s.is_open = TRUE
                   AND s.lat BETWEEN 28.3 - 3 / 111.32 AND 28.3 + 3 / 111.32
                   AND s.lng BETWEEN 77.2 - 3 / (111.32 * cos(radians(28.3))) AND 77.2 + 3 / (111.32 * cos(radians(28.3)))""");
        assertThat(nearby).as(nearby).doesNotContain("Seq Scan on stores").contains("ix_stores_open_lat_lng");
    }

    // ------------------------------------------------------------ helpers

    /** Statements prepared while serving one GET. */
    private long count(String url, String token) throws Exception {
        stats.clear();
        mvc.perform(get(url).header("Authorization", bearer(token))).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    /** A NEAREST request with n items, quoted by the first q stores. */
    private long request(int items, int quotes) throws Exception {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < items; i++) {
            list.append(i == 0 ? "" : ",").append("{\"name\":\"item ").append(i).append("\",\"quantity\":1,\"unit\":\"KG\"}");
        }
        JsonNode r = json.readTree(mvc.perform(post("/api/requests").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"NEAREST\",\"lat\":%s,\"lng\":%s,\"items\":[%s]}".formatted(LAT, LNG, list)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = r.get("id").asLong();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < items; i++) {
            lines.append(i == 0 ? "" : ",").append("""
                    {"requestItemId":%d,"offeredQty":1,"unit":"KG","linePrice":10,"status":"AVAILABLE"}"""
                    .formatted(r.get("items").get(i).get("id").asLong()));
        }
        for (int s = 0; s < quotes; s++) {
            mvc.perform(put("/api/store/requests/" + id + "/quote").header("Authorization", bearer(stores.get(s).token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"packingTimeMins\":15,\"items\":[" + lines + "]}"))
                    .andExpect(status().isOk());
        }
        return id;
    }

    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }
}
