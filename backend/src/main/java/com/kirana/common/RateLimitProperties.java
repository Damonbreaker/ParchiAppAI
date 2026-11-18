package com.kirana.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.rate-limits.*: limits enforced by RateLimiter. */
@ConfigurationProperties("app.rate-limits")
public record RateLimitProperties(
        @DefaultValue("5") int loginFailures,
        @DefaultValue("15m") Duration loginWindow,
        @DefaultValue("20") int scans,
        @DefaultValue("1h") Duration scanWindow,
        @DefaultValue("10") int requests,
        @DefaultValue("1h") Duration requestWindow) {

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(5, Duration.ofMinutes(15), 20, Duration.ofHours(1), 10, Duration.ofHours(1));
    }
}
