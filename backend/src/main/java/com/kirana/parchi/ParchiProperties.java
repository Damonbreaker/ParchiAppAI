package com.kirana.parchi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * app.parchi.* settings.
 *
 * @param reader       which ParchiReader bean is active ("local-ocr" or "mock")
 * @param mockDelayMs  MockParchiReader's fake processing time
 * @param maxBytes     largest accepted photo (keep in step with spring.servlet.multipart.max-file-size)
 * @param ocrBaseUrl   base URL of the local legacy OCR service (scan_server.py)
 * @param ocrTimeoutMs read timeout for a single OCR call
 */
@ConfigurationProperties("app.parchi")
public record ParchiProperties(
        @DefaultValue("local-ocr") String reader,
        @DefaultValue("800") long mockDelayMs,
        @DefaultValue("5242880") long maxBytes,
        @DefaultValue("http://127.0.0.1:7870") String ocrBaseUrl,
        @DefaultValue("180000") long ocrTimeoutMs) {
}
