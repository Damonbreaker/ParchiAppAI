package com.kirana;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kirana.common.RateLimiter;

/**
 * One Postgres container (Docker) shared by every integration test class; Flyway
 * migrates it on startup. "test" profile keeps the dev seeder off. All tables are emptied before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    protected static final double LAT = 28.5050;
    protected static final double LNG = 77.3800;
    /** Degrees of latitude per km. */
    protected static final double KM = 1 / 111.32;

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected RateLimiter rateLimiter;

    @BeforeEach
    void cleanDatabase() {
        rateLimiter.clear(); // ids restart with the tables, so old counts would hit new users
        jdbc.execute("""
                TRUNCATE quote_items, quotes, request_target, request_items, order_requests, stores, users
                RESTART IDENTITY CASCADE""");
    }

    /** Registers a user and returns its JWT. Password is always "secret1". */
    protected String register(String phone, String role) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"phone\":\"" + phone
                                + "\",\"password\":\"secret1\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    protected record OwnerStore(String token, long storeId) {
    }

    /** Registers a store owner and creates their store; optionally closes it. */
    protected OwnerStore ownerWithStore(String phone, String name, double lat, double lng, boolean open)
            throws Exception {
        String token = register(phone, "STORE_OWNER");
        String body = mvc.perform(post("/api/stores").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", name, "address", "Main Market", "area", "Sector 93", "lat", lat, "lng", lng))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        if (!open) {
            mvc.perform(patch("/api/stores/mine").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"isOpen\":false}"))
                    .andExpect(status().isOk());
        }
        return new OwnerStore(token, json.readTree(body).get("id").asLong());
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
