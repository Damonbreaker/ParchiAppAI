package com.kirana.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Small in-memory sliding-window rate limiter: at most {@code limit} events per key within {@code window}.
 * One instance only (state is not shared between servers or kept across restarts), which is fine for now.
 *
 * Keys look like "login:9876543210", "scan:12". Old keys are swept lazily every SWEEP_EVERY calls,
 * so memory stays bounded without a scheduler.
 */
@Component
public class RateLimiter {

    private static final int SWEEP_EVERY = 1_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Autowired
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 TOO_MANY_ATTEMPTS if the key already has {@code limit} events in the window; records nothing. */
    public void check(String key, int limit, Duration window, String message) {
        maybeSweep();
        Window w = windows.get(key);
        if (w == null) {
            return;
        }
        long retry;
        synchronized (w) {
            retry = w.retryAfterSeconds(now(), limit);
        }
        if (retry > 0) {
            throw ApiException.tooManyAttempts(message, retry);
        }
    }

    /** Records one event for the key. */
    public void record(String key, Duration window) {
        maybeSweep();
        Window w = windows.computeIfAbsent(key, k -> new Window(window));
        synchronized (w) {
            w.add(now());
        }
    }

    /** check + record in one step (every call counts, e.g. scans). */
    public void hit(String key, int limit, Duration window, String message) {
        maybeSweep();
        Window w = windows.computeIfAbsent(key, k -> new Window(window));
        long retry;
        synchronized (w) {
            Instant now = now();
            retry = w.retryAfterSeconds(now, limit);
            if (retry == 0) {
                w.add(now);
            }
        }
        if (retry > 0) {
            throw ApiException.tooManyAttempts(message, retry);
        }
    }

    /** Forgets the key (e.g. after a successful login). */
    public void reset(String key) {
        windows.remove(key);
    }

    /** Forgets everything (tests). */
    public void clear() {
        windows.clear();
    }

    int size() {
        return windows.size();
    }

    private Instant now() {
        return clock.instant();
    }

    private void maybeSweep() {
        if (calls.incrementAndGet() % SWEEP_EVERY != 0) {
            return;
        }
        Instant now = now();
        windows.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                e.getValue().prune(now);
                return e.getValue().events.isEmpty();
            }
        });
    }

    /** Event times of one key, oldest first. Callers synchronize on the instance. */
    private static final class Window {
        private final Duration length;
        private final Deque<Instant> events = new ArrayDeque<>();

        Window(Duration length) {
            this.length = length;
        }

        void prune(Instant now) {
            Instant cutoff = now.minus(length);
            while (!events.isEmpty() && !events.peekFirst().isAfter(cutoff)) {
                events.pollFirst();
            }
        }

        /** 0 if another event is allowed now, else seconds until the oldest counted event leaves the window. */
        long retryAfterSeconds(Instant now, int limit) {
            prune(now);
            if (events.size() < limit) {
                return 0;
            }
            long ms = Duration.between(now, events.peekFirst().plus(length)).toMillis();
            return Math.max(1, (ms + 999) / 1000);
        }

        void add(Instant now) {
            events.addLast(now);
        }
    }
}
