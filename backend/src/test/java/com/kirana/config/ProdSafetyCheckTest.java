package com.kirana.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProdSafetyCheckTest {

    private static final String GOOD_SECRET = "a-real-production-secret-of-40-characters!!";

    @Test
    void devDefaultsAreFineOutsideProd() {
        MockEnvironment env = env(ProdSafetyCheck.DEV_JWT_SECRET, ProdSafetyCheck.DEV_DB_PASSWORD);
        env.setActiveProfiles("dev");
        assertThat(ProdSafetyCheck.problems(env)).isEmpty();
    }

    @Test
    void prodRefusesDefaultSecretAndPassword() {
        MockEnvironment env = env(ProdSafetyCheck.DEV_JWT_SECRET, ProdSafetyCheck.DEV_DB_PASSWORD);
        env.setActiveProfiles("prod");
        assertThat(ProdSafetyCheck.problems(env)).hasSize(2);
        assertThatThrownBy(() -> new ProdSafetyCheck().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET").hasMessageContaining("DB_PASSWORD")
                .hasMessageNotContaining(ProdSafetyCheck.DEV_JWT_SECRET); // never prints the value

        MockEnvironment shortSecret = env("too-short", "S3cure-db-pass");
        shortSecret.setActiveProfiles("prod");
        assertThat(ProdSafetyCheck.problems(shortSecret)).containsExactly("JWT_SECRET must be at least 32 characters");
    }

    @Test
    void prodStartsWithRealValues() {
        MockEnvironment env = env(GOOD_SECRET, "S3cure-db-pass");
        env.setActiveProfiles("prod");
        assertThat(ProdSafetyCheck.problems(env)).isEmpty();
    }

    private static MockEnvironment env(String secret, String dbPassword) {
        return new MockEnvironment()
                .withProperty("app.jwt.secret", secret)
                .withProperty("spring.datasource.password", dbPassword);
    }
}
