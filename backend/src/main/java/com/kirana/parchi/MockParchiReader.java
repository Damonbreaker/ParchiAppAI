package com.kirana.parchi;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kirana.parchi.dto.ScanResultDto;
import com.kirana.parchi.dto.ScannedItemDto;

/**
 * Stand-in for real OCR (app.parchi.reader=mock). Used by tests.
 */
@Component
@ConditionalOnProperty(prefix = "app.parchi", name = "reader", havingValue = "mock")
public class MockParchiReader implements ParchiReader {

    static final List<List<String>> SAMPLES = List.of(
            List.of(
                    "1. aata 5kg",
                    "2. चीनी 2 किलो",
                    "3. doodh 2 ltr",
                    "4. anda 1 darjan",
                    "5. tata namak 1",
                    "6. सरसों तेल 1 ली",
                    "7. maggi 4 pkt",
                    "8. dhaniya 50gm",
                    "9. ½ kg pyaz",
                    "10. bread"),
            List.of(
                    "chawal 5 kilo",
                    "arhar dal - 1 kg",
                    "आलू २ किलो",
                    "tamatar 1/2 kg",
                    "haldi 100 g",
                    "jeera 50 gram",
                    "surf excel 1 packet",
                    "दही 500 ग्राम",
                    "kele 1 dozen",
                    "~~~",
                    "agarbatti"),
            List.of(
                    "Fortune oil 1L",
                    "- paneer 250gm",
                    "मैदा २ किग्रा",
                    "- sooji ½kg",
                    "chai patti 250 g",
                    "parle-g biscuit 3 pkt",
                    "nimbu 6 pc",
                    "हरी मिर्च 100 ग्राम",
                    "amul butter 100gm",
                    "dettol sabun 2",
                    "",
                    "माचिस"));

    private final long delayMs;
    private final ParchiLineParser parser;

    public MockParchiReader(ParchiProperties props, ParchiLineParser parser) {
        this.delayMs = props.mockDelayMs();
        this.parser = parser;
    }

    @Override
    public ScanResultDto read(byte[] image, String contentType, String role) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<ScannedItemDto> items = parser.parse(SAMPLES.get(pick(image))).stream()
                .map(ScannedItemDto::from)
                .toList();
        return new ScanResultDto(items);
    }

    static int pick(byte[] image) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(image);
            int n = ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            return Math.floorMod(n, SAMPLES.size());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
