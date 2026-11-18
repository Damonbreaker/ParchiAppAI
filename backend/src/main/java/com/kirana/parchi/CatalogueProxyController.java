package com.kirana.parchi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kirana.common.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Proxies catalogue poll resolve/suggest to the local OCR service so the
 * customer UI can change brand / type / pack after a scan.
 */
@RestController
@RequestMapping("/api/parchi/catalogue")
@RequiredArgsConstructor
public class CatalogueProxyController {

    private final ParchiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @PostMapping("/suggest")
    public Map<String, Object> suggest(@RequestBody Map<String, Object> body) {
        return post("/catalogue/suggest", body);
    }

    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestBody Map<String, Object> body) {
        return post("/catalogue/resolve", body);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            String base = props.ocrBaseUrl().replaceAll("/$", "");
            byte[] json = mapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "OCR_CATALOGUE_ERROR",
                        "Catalogue service failed");
            }
            return mapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "OCR_UNAVAILABLE",
                    "Catalogue service unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "OCR_UNAVAILABLE",
                    "Catalogue service interrupted");
        }
    }
}
