package com.kirana.parchi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.kirana.IntegrationTestBase;

/** Phase 7: the scan is one-time; the photo is never stored. */
class ParchiFlowIntegrationTest extends IntegrationTestBase {

    /** Where Phase 6 used to save photos (dev and test). Nothing may be written there any more. */
    private static final List<Path> OLD_UPLOAD_DIRS = List.of(Path.of("uploads"), Path.of("target/test-uploads"));

    OwnerStore near;
    String customer;

    @BeforeEach
    void setUp() throws Exception {
        near = ownerWithStore("9800000101", "Near Store", LAT + 0.3 * KM, LNG, true);
        customer = register("9811111101", "CUSTOMER");
    }

    @Test
    void scanReturnsItemsAndWritesNothing() throws Exception {
        List<Path> before = filesIn(OLD_UPLOAD_DIRS);
        scan(customer, file(png(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").doesNotExist())
                .andExpect(jsonPath("$.items", not(emptyIterable())))
                .andExpect(jsonPath("$.items[0].name").isString())
                .andExpect(jsonPath("$.items[0].confident").isBoolean());
        assertThat(filesIn(OLD_UPLOAD_DIRS)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'parchi_images'",
                Long.class)).isZero();
    }

    @Test
    void textRenamedToJpgTooLargeMissingAndWrongRole() throws Exception {
        scan(customer, new MockMultipartFile("image", "parchi.jpg", "image/jpeg",
                "aata 5kg\nchini 2 kg\n".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));

        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(ImageTypeTest.JPEG, 0, big, 0, ImageTypeTest.JPEG.length);
        scan(customer, new MockMultipartFile("image", "big.jpg", "image/jpeg", big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));

        mvc.perform(multipart("/api/parchi/scan").header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.image").value("is required"));

        scan(near.token(), file(png(2))).andExpect(status().isForbidden());
    }

    @Test
    void fromPhotoSetsSource() throws Exception {
        createRequest(", \"fromPhoto\": true")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("PHOTO"))
                .andExpect(jsonPath("$.hasImage").doesNotExist());
        createRequest(", \"fromPhoto\": false").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"));
        createRequest("").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"));
    }

    // ------------------------------------------------------------ helpers

    private static byte[] png(int seed) {
        byte[] b = new byte[2048];
        new Random(seed).nextBytes(b);
        System.arraycopy(ImageTypeTest.PNG, 0, b, 0, ImageTypeTest.PNG.length);
        return b;
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("image", "parchi.png", "image/png", bytes);
    }

    private ResultActions scan(String token, MockMultipartFile f) throws Exception {
        return mvc.perform(multipart("/api/parchi/scan").file(f).header("Authorization", bearer(token)));
    }

    private ResultActions createRequest(String extra) throws Exception {
        return mvc.perform(post("/api/requests").header("Authorization", bearer(customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"NEAREST","lat":%s,"lng":%s,"items":[
                         {"name":"aata","quantity":5,"unit":"KG"}]%s}""".formatted(LAT, LNG, extra)));
    }

    private static List<Path> filesIn(List<Path> dirs) throws IOException {
        List<Path> all = new java.util.ArrayList<>();
        for (Path d : dirs) {
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.walk(d)) {
                    s.filter(Files::isRegularFile).sorted().forEach(all::add);
                }
            }
        }
        return all;
    }
}
