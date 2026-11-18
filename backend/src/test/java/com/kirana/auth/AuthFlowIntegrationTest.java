package com.kirana.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.kirana.IntegrationTestBase;

class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void registerSuccessReturnsTokenAndUser() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"सीता देवी","phone":"9876543210","password":"secret1","role":"CUSTOMER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("सीता देवी"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.preferredLang").value("en"))
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void duplicatePhoneIs409() throws Exception {
        register("9876543210", "CUSTOMER");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Other","phone":"9876543210","password":"secret2","role":"STORE_OWNER"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_TAKEN"));
    }

    @Test
    void invalidRegisterReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","phone":"12345","password":"abc","role":"CUSTOMER"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.phone").exists())
                .andExpect(jsonPath("$.fields.password").exists());

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"A","phone":"9876543210","password":"secret1","role":"ADMIN"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.role").value(containsString("CUSTOMER")));
    }

    @Test
    void loginSuccess() throws Exception {
        register("9876543210", "STORE_OWNER");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"phone":"9876543210","password":"secret1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("STORE_OWNER"));
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        register("9876543210", "CUSTOMER");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"phone":"9876543210","password":"wrong-pass"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void meWithoutTokenIs401Json() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(get("/api/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid token"));
    }

    @Test
    void patchMeChangesLanguage() throws Exception {
        String token = register("9876543210", "CUSTOMER");

        mvc.perform(patch("/api/me").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"preferredLang":"hi"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLang").value("hi"));

        mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLang").value("hi"))
                .andExpect(jsonPath("$.phone").value("9876543210"));
    }

    @Test
    void wrongRoleIs403Json() throws Exception {
        String customer = register("9876543210", "CUSTOMER");
        mvc.perform(get("/api/store/requests").header("Authorization", bearer(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String owner = register("8765432109", "STORE_OWNER");
        mvc.perform(get("/api/requests/mine").header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublicWithCors() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
        mvc.perform(options("/api/health")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
