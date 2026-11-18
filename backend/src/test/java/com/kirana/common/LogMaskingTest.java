package com.kirana.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;

/** Renders log events through the real CONSOLE layout from logback-spring.xml. */
class LogMaskingTest {

    private static final String JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMiIsInJvbGUiOiJDVVNUT01FUiJ9.abcDEF123_-xyz";

    @Test
    void phonesJwtsAndPasswordsAreMasked() {
        String out = render("login 9876543210 token " + JWT + " body {\"phone\":\"9000000001\",\"password\":\"test123\"}",
                new IllegalStateException("Key (phone)=(9123456789) already exists; password=secret1"));

        assertThat(out).doesNotContain("9876543210", "9000000001", "9123456789", JWT, "test123", "secret1");
        assertThat(out).contains("98XXXXXX10", "90XXXXXX01", "91XXXXXX89", "[jwt]", "\"password\":\"***\"",
                "password=***");
        assertThat(out).contains("IllegalStateException"); // stack trace still there, once
        assertThat(out.split("IllegalStateException", -1)).hasSize(2);
    }

    @Test
    void ordinaryNumbersStay() {
        String out = render("request 12 total 1234.50 at 28.5050,77.3800 id 1234567890123", null);
        assertThat(out).contains("request 12 total 1234.50 at 28.5050,77.3800 id 1234567890123");
    }

    private static String render(String message, Throwable error) {
        LoggerContext ctx = new LoggerContext();
        JoranConfigurator config = new JoranConfigurator();
        config.setContext(ctx);
        try {
            config.doConfigure(LogMaskingTest.class.getResource("/logback-spring.xml"));
        } catch (JoranException e) {
            throw new IllegalStateException(e);
        }
        Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> console =
                (OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>) root.getAppender("CONSOLE");
        PatternLayoutEncoder encoder = (PatternLayoutEncoder) console.getEncoder();
        LoggingEvent event = new LoggingEvent(LogMaskingTest.class.getName(), root, Level.WARN, message, error, null);
        return encoder.getLayout().doLayout(event);
    }
}
