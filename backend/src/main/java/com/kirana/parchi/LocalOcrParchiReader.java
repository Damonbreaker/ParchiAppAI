package com.kirana.parchi;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kirana.common.Unit;
import com.kirana.parchi.dto.CatalogueOptionsDto;
import com.kirana.parchi.dto.ScanResultDto;
import com.kirana.parchi.dto.ScannedItemDto;

/**
 * Calls the local legacy dual-OCR + catalogue service.
 */
@Component
@ConditionalOnProperty(prefix = "app.parchi", name = "reader", havingValue = "local-ocr")
public class LocalOcrParchiReader implements ParchiReader {

    private final HttpClient http;
    private final String scanBase;
    private final Duration readTimeout;
    private final ObjectMapper mapper;
    private final ParchiLineParser parser;

    public LocalOcrParchiReader(ParchiProperties props, ObjectMapper mapper, ParchiLineParser parser) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = props.ocrBaseUrl().replaceAll("/$", "");
        this.scanBase = base + "/scan";
        this.readTimeout = Duration.ofMillis(Math.max(1_000L, props.ocrTimeoutMs()));
        this.mapper = mapper;
        this.parser = parser;
    }

    @Override
    public ScanResultDto read(byte[] image, String contentType, String role) {
        Map<String, Object> parsed = postScan(image, contentType, role);
        Object rowsObj = parsed.get("rows");
        if (rowsObj instanceof List<?> rows && !rows.isEmpty()) {
            List<ScannedItemDto> items = new ArrayList<>();
            for (Object entry : rows) {
                if (entry instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) raw;
                    ScannedItemDto item = fromRow(row);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            if (!items.isEmpty()) {
                return new ScanResultDto(items);
            }
        }
        // Fallback: free-text lines
        Object linesObj = parsed.get("lines");
        if (!(linesObj instanceof List<?> rawLines)) {
            return new ScanResultDto(List.of());
        }
        List<String> lines = new ArrayList<>();
        for (Object entry : rawLines) {
            String text = lineText(entry);
            if (text != null && !text.isBlank()) {
                lines.add(text);
            }
        }
        return new ScanResultDto(parser.parse(lines).stream().map(ScannedItemDto::from).toList());
    }

    private Map<String, Object> postScan(byte[] image, String contentType, String role) {
        String boundary = "parchi-" + UUID.randomUUID();
        String partType = contentType != null && !contentType.isBlank() ? contentType : "image/jpeg";
        byte[] body = multipart(boundary, partType, image);
        String roleQ = (role == null || role.isBlank()) ? "customer" : role.trim().toLowerCase(Locale.ROOT);
        URI uri = URI.create(scanBase + "?role=" + roleQ);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Local OCR HTTP " + response.statusCode());
            }
            return mapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Local OCR service unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Local OCR interrupted", e);
        }
    }

    private ScannedItemDto fromRow(Map<String, Object> row) {
        String name = str(row.get("name"));
        if (name.isBlank()) {
            return null;
        }
        String qtyText = str(row.get("quantity"));
        String line = qtyText.isBlank() ? name : (name + " " + qtyText).trim();
        var parsed = parser.parseLine(line).orElse(null);
        if (parsed == null) {
            return null;
        }
        boolean confident = parsed.confident() && num(row.get("confidence"), 0.8) >= 0.55;
        CatalogueOptionsDto options = optionsFrom(row.get("options"));
        @SuppressWarnings("unchecked")
        Map<String, Object> selection = row.get("selection") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : Map.of();
        Unit unit = parsed.unit();
        String unitHint = str(row.get("unit_hint"));
        if (!unitHint.isBlank()) {
            try {
                unit = Unit.valueOf(unitHint.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // keep parser unit
            }
        }
        BigDecimal qty = parsed.quantity();
        String baseName = str(row.get("base_name"));
        if (baseName.isBlank()) {
            baseName = parsed.name();
        }
        String mode = str(row.get("mode"));
        if (mode.isBlank()) {
            mode = "simple";
        }
        // Prefer base kirana name from OCR service when in simple mode.
        String displayName = "advanced".equalsIgnoreCase(mode) ? parsed.name() : baseName;
        @SuppressWarnings("unchecked")
        Map<String, Object> mrp = row.get("mrp") instanceof Map<?, ?> mm
                ? new LinkedHashMap<>((Map<String, Object>) mm)
                : null;
        BigDecimal linePrice = null;
        Object priceObj = row.get("price");
        if (priceObj instanceof Number n) {
            linePrice = BigDecimal.valueOf(n.doubleValue());
        } else if (priceObj != null && !priceObj.toString().isBlank()) {
            try {
                linePrice = new BigDecimal(priceObj.toString().trim());
            } catch (NumberFormatException ignored) {
                // leave null
            }
        }
        return new ScannedItemDto(
                displayName,
                qty,
                unit,
                confident,
                str(row.get("catalogue_id")),
                str(row.get("brand")),
                str(row.get("department")),
                str(row.get("category")),
                str(row.get("type")),
                str(row.get("pack")),
                baseName,
                mode,
                mrp,
                linePrice,
                options,
                selection.isEmpty() ? null : selection);
    }

    @SuppressWarnings("unchecked")
    private static CatalogueOptionsDto optionsFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> o = (Map<String, Object>) map;
        List<String> departments = stringList(o.get("departments"));
        List<String> categories = stringList(o.get("categories"));
        List<String> types = stringList(o.get("types"));
        List<String> brands = stringList(o.get("brands"));
        List<String> products = stringList(o.get("products"));
        List<String> units = stringList(o.get("units"));
        List<Map<String, Object>> packs = new ArrayList<>();
        if (o.get("packs") instanceof List<?> packList) {
            for (Object p : packList) {
                if (p instanceof Map<?, ?> pm) {
                    packs.add(new LinkedHashMap<>((Map<String, Object>) pm));
                }
            }
        }
        if (departments.isEmpty() && categories.isEmpty() && types.isEmpty() && brands.isEmpty()
                && products.isEmpty() && packs.isEmpty()) {
            return null;
        }
        return new CatalogueOptionsDto(departments, categories, types, brands, products, packs, units);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object v : list) {
            if (v != null && !v.toString().isBlank()) {
                out.add(v.toString());
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static double num(Object v, double fallback) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? fallback : Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] multipart(String boundary, String contentType, byte[] image) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"parchi.jpg\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] head = header.getBytes(StandardCharsets.UTF_8);
        byte[] foot = footer.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + image.length + foot.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(image, 0, all, head.length, image.length);
        System.arraycopy(foot, 0, all, head.length + image.length, foot.length);
        return all;
    }

    private static String lineText(Object entry) {
        if (entry instanceof String s) {
            return s.trim();
        }
        if (entry instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text == null ? null : text.toString().trim();
        }
        return null;
    }
}
