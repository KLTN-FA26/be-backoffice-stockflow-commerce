package com.stockflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed environment gets what the application needs to do its core flows. {@code deploy/docker-compose.yml}
 * lists the application's variables one by one, so a variable the application reads but the compose file does not pass
 * is silently empty on the server (audit of #109: B2B onboarding answered 503 on the VPS).
 */
class DeployConfigurationTest {

    @Test
    @DisplayName("the deploy compose file requires PORTAL_ACTIVATION_URL: without it no customer can be created")
    void theActivationAddressIsRequiredOnTheServer() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(application).as("the application reads it").contains("${PORTAL_ACTIVATION_URL:");
        assertThat(compose).as("compose passes it, refusing a missing or empty value")
                .contains("PORTAL_ACTIVATION_URL: ${PORTAL_ACTIVATION_URL:?");
    }
}
