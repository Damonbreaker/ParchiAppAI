package com.kirana.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }

    private final TestClock clock = new TestClock();
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void checkAndRecordBlockAfterTheLimitUntilTheOldestLeaves() {
        for (int i = 0; i < 5; i++) {
            limiter.check("login:1", 5, WINDOW, "slow down");
            limiter.record("login:1", WINDOW);
            clock.plus(Duration.ofMinutes(1));
        }
        // 5 failures at 10:00..10:04, now 10:05: blocked until 10:15
        assertThatThrownBy(() -> limiter.check("login:1", 5, WINDOW, "slow down"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(600);
                });
        assertThatCode(() -> limiter.check("login:2", 5, WINDOW, "x")).doesNotThrowAnyException(); // other key

        clock.plus(Duration.ofMinutes(10)); // 10:15: the 10:00 failure has left the window
        assertThatCode(() -> limiter.check("login:1", 5, WINDOW, "x")).doesNotThrowAnyException();
    }

    @Test
    void hitCountsEveryCallAndResetForgets() {
        for (int i = 0; i < 3; i++) {
            limiter.hit("scan:1", 3, Duration.ofHours(1), "x");
        }
        assertThatThrownBy(() -> limiter.hit("scan:1", 3, Duration.ofHours(1), "x"))
                .isInstanceOf(ApiException.class);
        limiter.reset("scan:1");
        assertThatCode(() -> limiter.hit("scan:1", 3, Duration.ofHours(1), "x")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterIsNeverZero() {
        limiter.hit("k", 1, Duration.ofMillis(300), "x");
        assertThatThrownBy(() -> limiter.hit("k", 1, Duration.ofMillis(300), "x"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void expiredKeysAreSweptAway() {
        for (int i = 0; i < 500; i++) {
            limiter.record("user:" + i, WINDOW);
        }
        clock.plus(Duration.ofHours(1));
        for (int i = 0; i < 600; i++) { // crosses the 1,000-call sweep
            limiter.check("other", 5, WINDOW, "x");
        }
        assertThat(limiter.size()).isZero();
    }
}
