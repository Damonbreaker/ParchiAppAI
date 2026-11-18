package com.kirana.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * With the "prod" profile active, refuses to start while the JWT secret or the DB password is still the
 * dev default (or the secret is too short). Runs as an EnvironmentPostProcessor (registered in
 * META-INF/spring.factories), i.e. before any bean is created, so nothing connects with default credentials.
 * Values are never printed, only the names of the settings to change.
 */
public class ProdSafetyCheck implements EnvironmentPostProcessor, Ordered {

    static final String DEV_JWT_SECRET = "dev-only-kirana-jwt-secret-change-me-0123456789";
    static final String DEV_DB_PASSWORD = "kirana";
    static final int MIN_SECRET_LENGTH = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with profile 'prod': " + String.join("; ", problems));
        }
    }

    /** Empty unless "prod" is active and something is unsafe. */
    static List<String> problems(Environment env) {
        if (!Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        String secret = env.getProperty("app.jwt.secret");
        if (secret == null || secret.isBlank() || DEV_JWT_SECRET.equals(secret)) {
            problems.add("set JWT_SECRET (app.jwt.secret) to your own secret");
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            problems.add("JWT_SECRET must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        String dbPassword = env.getProperty("spring.datasource.password");
        if (dbPassword == null || dbPassword.isBlank() || DEV_DB_PASSWORD.equals(dbPassword)) {
            problems.add("set DB_PASSWORD (spring.datasource.password) to a non-default password");
        }
        return problems;
    }

    /** After the config files (and so the active profiles) are loaded. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
