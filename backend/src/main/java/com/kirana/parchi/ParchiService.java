package com.kirana.parchi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.kirana.common.ApiException;
import com.kirana.common.RateLimitProperties;
import com.kirana.common.RateLimiter;
import com.kirana.parchi.dto.ScanResultDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * One-time scan. Privacy: the photo is held in memory only for this call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParchiService {

    private final ParchiReader reader;
    private final ParchiProperties props;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public ScanResultDto scan(Long userId, MultipartFile file) {
        return scan(userId, file, "customer");
    }

    public ScanResultDto scan(Long userId, MultipartFile file, String role) {
        String roleNorm = normalizeRole(role);
        rateLimiter.hit("scan:" + userId, limits.scans(), limits.scanWindow(),
                "Too many scans. Please try again later or type the list.");
        if (file == null || file.isEmpty()) {
            throw ApiException.validation(Map.of("image", "is required"));
        }
        if (file.getSize() > props.maxBytes()) {
            throw tooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ImageType type = ImageType.detect(bytes).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_IMAGE", "Only JPEG, PNG or WebP photos are allowed"));

        try {
            ScanResultDto result = reader.read(bytes, type.mimeType(), roleNorm);
            int n = result.items() == null ? 0 : result.items().size();
            log.info("Parchi scan via {} role={}: {} items", reader.getClass().getSimpleName(), roleNorm, n);
            return result == null ? new ScanResultDto(List.of()) : result;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Parchi reader failed: {} ({}) via {}", e.getClass().getSimpleName(),
                    cause.getClass().getSimpleName(), reader.getClass().getSimpleName());
            return new ScanResultDto(List.of());
        }
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "customer";
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        if (r.equals("shop") || r.equals("shopkeeper") || r.equals("store")) {
            return "shop";
        }
        return "customer";
    }

    public static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "Photo must be 5 MB or smaller");
    }
}
