package com.stockflow.common.i18n;

import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the translations in step with the code.
 *
 * <p>A missing key does not fail at startup and does not fail at runtime — {@link Messages} falls
 * back to the English default, deliberately, because the exception handler must never be the thing
 * that throws. The consequence is that a forgotten translation is <b>invisible</b> until a
 * Vietnamese user reads an English error message. This test is the only thing that catches it.</p>
 */
class MessageBundleTest {

    @ParameterizedTest
    @ValueSource(strings = {"i18n/messages.properties", "i18n/messages_vi.properties"})
    @DisplayName("every ErrorCode has a message in every bundle")
    void everyErrorCodeIsTranslated(String bundle) throws IOException {
        Properties properties = load(bundle);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(properties.getProperty("error." + code.name()))
                    .as("%s has no entry in %s - Vietnamese users would see the English default",
                            code, bundle)
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("the bundles have exactly the same keys, in both directions")
    void bundlesAgree() throws IOException {
        Properties english = load("i18n/messages.properties");
        Properties vietnamese = load("i18n/messages_vi.properties");

        // Both directions: a key only in Vietnamese is dead weight, and one only in English is an
        // untranslated message. Neither shows up any other way.
        assertThat(vietnamese.stringPropertyNames())
                .as("keys present in English but not Vietnamese")
                .containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
    }

    @Test
    @DisplayName("the Vietnamese bundle really is UTF-8")
    void vietnameseIsUtf8() throws IOException {
        Properties vietnamese = load("i18n/messages_vi.properties");

        String message = vietnamese.getProperty("error.NOT_FOUND");
        // Properties files default to ISO-8859-1. If the encoding is wrong the diacritics arrive as
        // mojibake - and it looks correct in the IDE, so nothing else catches it.
        assertThat(message).contains("ô");
        assertThat(message).doesNotContain("?");
    }

    @Test
    @DisplayName("validation bundles agree too")
    void validationBundlesAgree() throws IOException {
        Properties english = load("i18n/validation.properties");
        Properties vietnamese = load("i18n/validation_vi.properties");

        assertThat(vietnamese.stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessageBundleTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("%s is missing from the classpath", resource).isNotNull();
            // Explicit UTF-8: Properties.load(InputStream) assumes ISO-8859-1, which is the exact
            // bug this test is meant to detect - reading it the wrong way would hide it.
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
