package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Location codes (BR-10) and the parts they are built from. The database rebuilds a bin's code from
 * its shelf, level and prefix and refuses a mismatch ({@code tg_bin_location}), and checks the
 * shape with {@code ck_storage_location_code} - so the codes produced here must match those
 * character for character.
 */
class LocationCodeTest {

    /** Copied from {@code ck_storage_location_code} in {@code V20260928000100}. */
    private static final Pattern DB_BIN = Pattern.compile(
            "^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}-[1-9][0-9]?-[A-Z0-9]{1,20}$");
    private static final Pattern DB_AREA = Pattern.compile("^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}$");

    @Nested
    @DisplayName("a code part")
    class CodeParts {

        @Test
        @DisplayName("is upper-cased")
        void isUpperCased() {
            assertThat(CodePart.of("a01", CodePart.CODE_MAX_LENGTH).value()).isEqualTo("A01");
        }

        @Test
        @DisplayName("upper-cases without the default locale - 'i' stays 'I' on a Turkish machine")
        void upperCasesLocaleIndependently() {
            Locale previous = Locale.getDefault();
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            try {
                assertThat(CodePart.of("bin", CodePart.CODE_MAX_LENGTH).value()).isEqualTo("BIN");
            } finally {
                Locale.setDefault(previous);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"A-1", "A 1", "A_1", "Ă1", ""})
        @DisplayName("refuses anything but letters and digits - a '-' would make codes ambiguous")
        void refusesOtherCharacters(String raw) {
            assertThatThrownBy(() -> CodePart.of(raw, CodePart.CODE_MAX_LENGTH))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refuses null")
        void refusesNull() {
            assertThatThrownBy(() -> CodePart.of(null, CodePart.CODE_MAX_LENGTH))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("refuses more characters than its maximum")
        void refusesTooLong() {
            assertThat(CodePart.of("A".repeat(10), CodePart.PREFIX_MAX_LENGTH).value()).hasSize(10);
            assertThatThrownBy(() -> CodePart.of("A".repeat(11), CodePart.PREFIX_MAX_LENGTH))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("a bin code")
    class BinCodes {

        @Test
        @DisplayName("is prefix-shelf-level-bin")
        void hasFourParts() {
            LocationCode code = LocationCode.ofBin("HN", "A01", 2, "03");

            assertThat(code.value()).isEqualTo("HN-A01-2-03");
            assertThat(code.kind()).isEqualTo(StorageLocationKind.BIN);
        }

        @Test
        @DisplayName("writes the level without a leading zero, as the database assembles it")
        void levelHasNoLeadingZero() {
            assertThat(LocationCode.ofBin("HCM", "A01", 2, "B").value()).isEqualTo("HCM-A01-2-B");
            assertThat(LocationCode.ofBin("HCM", "A01", 12, "B").value()).isEqualTo("HCM-A01-12-B");
        }

        @Test
        @DisplayName("upper-cases every part")
        void upperCasesParts() {
            assertThat(LocationCode.ofBin("hn", "a01", 2, "b03").value()).isEqualTo("HN-A01-2-B03");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 100, -1})
        @DisplayName("refuses a level outside 1-99")
        void refusesLevelOutOfRange(int level) {
            assertThatThrownBy(() -> LocationCode.ofBin("HN", "A01", level, "03"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("refuses a part with a hyphen")
        void refusesHyphenatedPart() {
            assertThatThrownBy(() -> LocationCode.ofBin("HN", "A-01", 2, "03"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("matches the database CHECK even at the longest parts")
        void matchesDatabaseCheck() {
            String longest = LocationCode.ofBin("P".repeat(10), "S".repeat(20), 99, "B".repeat(20)).value();

            assertThat(longest).matches(DB_BIN).hasSizeLessThanOrEqualTo(64);
            assertThat(LocationCode.ofBin("H", "A", 1, "1").value()).matches(DB_BIN);
        }
    }

    @Nested
    @DisplayName("an area code")
    class AreaCodes {

        @Test
        @DisplayName("is prefix-area")
        void hasTwoParts() {
            LocationCode code = LocationCode.ofArea("hn", "rcv01");

            assertThat(code.value()).isEqualTo("HN-RCV01");
            assertThat(code.kind()).isEqualTo(StorageLocationKind.AREA);
        }

        @Test
        @DisplayName("matches the database CHECK even at the longest parts")
        void matchesDatabaseCheck() {
            assertThat(LocationCode.ofArea("P".repeat(10), "A".repeat(20)).value()).matches(DB_AREA);
        }
    }

    @Nested
    @DisplayName("an existing code")
    class ExistingCodes {

        @Test
        @DisplayName("is accepted in either shape and tells its kind by the number of parts")
        void acceptsBothShapes() {
            assertThat(new LocationCode("HCM-A01-2-B").kind()).isEqualTo(StorageLocationKind.BIN);
            assertThat(new LocationCode("HCM-QC01").kind()).isEqualTo(StorageLocationKind.AREA);
        }

        @ParameterizedTest
        @ValueSource(strings = {"hcm-a01-2-b", "HCM-A-01-02-B", "HCM-A01-02-B", "HCM", "HCM--QC01", ""})
        @DisplayName("is refused when it is not exactly a shape the database accepts")
        void refusesOtherShapes(String raw) {
            assertThatThrownBy(() -> new LocationCode(raw)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("is refused beyond 64 characters")
        void refusesTooLong() {
            String sixtyFive = "A".repeat(10) + "-" + "B".repeat(54);

            assertThat(sixtyFive).hasSize(65);
            assertThatThrownBy(() -> new LocationCode(sixtyFive)).isInstanceOf(BusinessException.class);
        }
    }
}
