package com.stockflow.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorsTest {

    @Nested
    @DisplayName("Vietnamese phone numbers")
    class Phones {

        private final VietnamPhoneValidator validator = new VietnamPhoneValidator();

        @ParameterizedTest
        @ValueSource(strings = {
                "0912345678", "0812345678", "0387654321", "0777777777", "0512345678",
                "+84912345678", "84912345678",
                // Landlines are ELEVEN digits nationally, not ten. A single pattern written for
                // mobiles silently rejects every landline in the country.
                "02438251234", "024 3825 1234", "028-3822-9999", "(024) 3825.1234"})
        @DisplayName("accepts the forms people actually type")
        void accepts(String number) {
            assertThat(validator.isValid(number, null)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "01234567890",   // retired in the 2018 renumbering; no longer connects
                "091234567",     // too short
                "09123456789",   // too long for a mobile
                "0412345678",    // 04 was replaced by 024
                "1234567890", "+1234567890", "abcdefghij"})
        @DisplayName("rejects numbers that would not deliver")
        void rejects(String number) {
            assertThat(validator.isValid(number, null)).isFalse();
        }

        @Test
        @DisplayName("presence is @NotBlank's job, not this validator's")
        void nullAndBlankDefer() {
            assertThat(validator.isValid(null, null)).isTrue();
            assertThat(validator.isValid("   ", null)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0912345678", "+84912345678", "84912345678",
                "091 234 5678", "091-234-5678"})
        @DisplayName("every input shape normalises to one canonical form")
        void normalisesToOneForm(String input) {
            // Storing the typed form means the same subscriber appears twice and neither a lookup
            // nor a de-duplication finds both.
            assertThat(VietnamPhoneValidator.normalise(input)).isEqualTo("+84912345678");
        }

        @Test
        @DisplayName("normalise refuses an invalid number rather than mangling it")
        void normaliseRejectsInvalid() {
            assertThatThrownBy(() -> VietnamPhoneValidator.normalise("123"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("SKU")
    class Skus {

        private final SkuValidator validator = new SkuValidator();

        @Test
        @DisplayName("delegates to the value object, so there is one definition of valid")
        void matchesTheValueObject() {
            assertThat(validator.isValid("SOFA-3S-GREY", null)).isTrue();
            assertThat(validator.isValid("sofa-3s-grey", null)).isTrue();
            assertThat(validator.isValid("A B", null)).isFalse();
            assertThat(validator.isValid("AB", null)).isFalse();
            assertThat(validator.isValid(null, null)).isTrue();
        }
    }

    @Nested
    @DisplayName("trimmed text")
    class TrimmedText {

        private NotBlankTrimmedValidator validatorWithMax(int max) {
            NotBlankTrimmedValidator validator = new NotBlankTrimmedValidator();
            validator.initialize(new NotBlankTrimmed() {
                @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return NotBlankTrimmed.class;
                }
                @Override public String message() { return ""; }
                @Override public int max() { return max; }
                @Override public int min() { return 1; }
                @Override public Class<?>[] groups() { return new Class<?>[0]; }
                @Override public Class<? extends jakarta.validation.Payload>[] payload() { return null; }
            });
            return validator;
        }

        @Test
        @DisplayName("the limit applies to the trimmed value, matching the column width")
        void measuresTrimmedLength() {
            NotBlankTrimmedValidator validator = validatorWithMax(5);
            // @Size(max=5) would reject this; the value actually stored is three characters.
            assertThat(validator.isValid("   abc   ", null)).isTrue();
            assertThat(validator.isValid("abcdef", null)).isFalse();
        }

        @Test
        @DisplayName("blank and null are rejected - this constraint means required")
        void requiresContent() {
            NotBlankTrimmedValidator validator = validatorWithMax(50);
            assertThat(validator.isValid("   ", null)).isFalse();
            assertThat(validator.isValid(null, null)).isFalse();
        }

        @Test
        @DisplayName("control characters pasted from a spreadsheet are rejected")
        void rejectsControlCharacters() {
            NotBlankTrimmedValidator validator = validatorWithMax(50);
            // They survive every length check and then break CSV exports and label printing.
            assertThat(validator.isValid("line\nbreak", null)).isFalse();
            assertThat(validator.isValid("tab\tseparated", null)).isFalse();
        }
    }
}
