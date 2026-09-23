package com.stockflow.identity.internal.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void acceptsTenCharactersWithUpperLowerAndDigit() {
        assertThat(PasswordPolicy.violation("Abcdefgh12")).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Abcdefg12", "abcdefghi12", "ABCDEFGHI12", "Abcdefghijk"})
    void refusesTooShortMissingAClassOrNull(String password) {
        assertThat(PasswordPolicy.violation(password)).isPresent();
    }

    @Test
    void refusesWhatBcryptWouldSilentlyTruncate() {
        assertThat(PasswordPolicy.violation("Aa1" + "x".repeat(69))).as("72 chars").isEmpty();
        assertThat(PasswordPolicy.violation("Aa1" + "x".repeat(70))).as("73 chars").isPresent();
    }

    @Test
    void thePatternConstantAgreesWithTheRule() {
        assertThat("Abcdefgh12".matches(PasswordPolicy.PATTERN)).isTrue();
        assertThat("abcdefgh12".matches(PasswordPolicy.PATTERN)).isFalse();
    }
}
