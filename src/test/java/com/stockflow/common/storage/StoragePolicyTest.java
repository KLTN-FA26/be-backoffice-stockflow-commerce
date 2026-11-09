package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Upload validation and key construction - the parts a malicious upload would probe. */
class StoragePolicyTest {

    private static final byte[] PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] PDF =
            {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0, 0, 0, 0};

    @Test
    @DisplayName("a genuine PNG is accepted, charset parameter and all")
    void acceptsMatchingContent() {
        ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, "image/png; charset=binary", 1_000, PNG);
    }

    @Test
    @DisplayName("a file lying about its type is rejected")
    void rejectsMismatchedMagicNumber() {
        // Content-Type is chosen by the client, so a header check alone lets anything through
        // under a PNG label.
        assertThatThrownBy(() ->
                ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, "image/png", 1_000, PDF))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    @DisplayName("a type the category does not accept is refused with 415")
    void rejectsUnsupportedType() {
        assertThatThrownBy(() ->
                ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, "application/pdf", 1_000, PDF))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode().httpStatus())
                        .isEqualTo(415));
    }

    @Test
    @DisplayName("4A-4: print sample videos are checked by their signature too, and keep their extension")
    void printSampleVideos() {
        byte[] mp4 = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] webm = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0};
        ContentTypePolicy.check(FileCategory.PRINT_SAMPLE, "video/mp4", 80L * 1024 * 1024, mp4);
        ContentTypePolicy.check(FileCategory.PRINT_SAMPLE, "video/quicktime", 1_000, mp4);
        ContentTypePolicy.check(FileCategory.PRINT_SAMPLE, "video/webm", 1_000, webm);
        assertThatThrownBy(() -> ContentTypePolicy.check(FileCategory.PRINT_SAMPLE, "video/mp4", 1_000, PNG))
                .isInstanceOf(BusinessException.class).hasMessageContaining("do not match");
        assertThatThrownBy(() -> ContentTypePolicy.check(FileCategory.PRINT_SAMPLE, "video/webm", 1_000, mp4))
                .isInstanceOf(BusinessException.class).hasMessageContaining("do not match");
        assertThatThrownBy(() -> ContentTypePolicy.check(FileCategory.RETURN_EVIDENCE, "video/mp4", 1_000, mp4))
                .as("videos only where a category takes them").isInstanceOf(BusinessException.class);
        assertThat(StorageKeys.extensionFor("video/mp4")).isEqualTo(".mp4");
        assertThat(StorageKeys.extensionFor("video/quicktime")).isEqualTo(".mov");
        assertThat(StorageKeys.extensionFor("video/webm")).isEqualTo(".webm");
    }

    @Test
    @DisplayName("oversize is 413 and empty is 400")
    void enforcesSize() {
        assertThatThrownBy(() -> ContentTypePolicy.check(
                FileCategory.PRODUCT_IMAGE, "image/png", 999L * 1024 * 1024, PNG))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode().httpStatus())
                        .isEqualTo(413));

        assertThatThrownBy(() ->
                ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, "image/png", 0, PNG))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(FileCategory.class)
    @DisplayName("no category accepts SVG, which would be stored cross-site scripting")
    void noCategoryAcceptsSvg(FileCategory category) {
        assertThat(category.allowedContentTypes()).doesNotContain("image/svg+xml");
        assertThat(category.allowedContentTypes()).isNotEmpty();
        assertThat(category.maxBytes()).isPositive();
    }

    @Test
    @DisplayName("keys are category/date/id.ext and sort by time within a category")
    void keyLayout() {
        String earlier = StorageKeys.generate(
                FileCategory.PRODUCT_IMAGE, "image/png", Instant.parse("2026-09-07T10:00:00Z"));
        String later = StorageKeys.generate(
                FileCategory.PRODUCT_IMAGE, "image/png", Instant.parse("2026-09-07T10:00:01Z"));

        assertThat(earlier).startsWith("product-images/2026/09/07/").endsWith(".png");
        // Object stores list lexicographically; without the date prefix and time-ordered id a
        // bucket with a million files is unusable in any console.
        assertThat(earlier).isLessThan(later);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc/passwd", "/etc/passwd",
            "product-images/../../secret", "unknown-prefix/2026/09/07/x.png"})
    @DisplayName("a key that escapes its prefix is refused")
    void rejectsMalformedKeys(String key) {
        assertThatThrownBy(() -> StorageKeys.requireValid(key))
                .isInstanceOf(StorageException.class);
    }

    @ParameterizedTest
    @EnumSource(FileCategory.class)
    @DisplayName("a generated key reports the category it came from")
    void categoryRoundTrips(FileCategory category) {
        String key = StorageKeys.generate(category, "application/pdf", Instant.now());
        assertThat(StorageKeys.categoryOf(key)).isEqualTo(category);
    }
}
