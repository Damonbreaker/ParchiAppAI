package com.kirana.parchi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ImageTypeTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @Test
    void detectsByMagicBytes() {
        assertThat(ImageType.detect(JPEG)).contains(ImageType.JPEG);
        assertThat(ImageType.detect(PNG)).contains(ImageType.PNG);
        assertThat(ImageType.detect(WEBP)).contains(ImageType.WEBP);
    }

    @Test
    void rejectsTextRenamedToJpgAndOtherFormats() {
        assertThat(ImageType.detect("aata 5kg\nchini 2kg".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageType.detect("GIF89a....".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageType.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageType.detect("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ImageType.detect(new byte[0])).isEmpty();
        assertThat(ImageType.detect(null)).isEmpty();
    }

    @Test
    void mockReaderIsStablePerPhotoAndVariesAcrossPhotos() {
        MockParchiReader reader = new MockParchiReader(
                new ParchiProperties("mock", 0, 5_242_880, "http://127.0.0.1:7870", 180_000),
                new ParchiLineParser());
        var first = reader.read(PNG, "image/png");
        var again = reader.read(PNG.clone(), "image/png");
        assertThat(first.items()).isEqualTo(again.items());

        Random random = new Random(42);
        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            byte[] photo = new byte[64];
            random.nextBytes(photo);
            picked.add(MockParchiReader.pick(photo));
        }
        assertThat(picked).containsExactlyInAnyOrder(0, 1, 2);
    }
}
