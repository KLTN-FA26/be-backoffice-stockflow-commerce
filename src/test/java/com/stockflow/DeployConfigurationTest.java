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

    @Test
    @DisplayName("SCRUM-352: the server gets the Resend settings and sends through Resend; a local dev run stays on SMTP")
    void theResendSettingsReachTheServer() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String dev = Files.readString(Path.of("src/main/resources/application-dev.yml"), StandardCharsets.UTF_8);

        for (String variable : java.util.List.of("RESEND_API_KEY", "RESEND_FROM", "RESEND_REPLY_TO",
                "RESEND_WEBHOOK_SECRET", "CUSTOMER_EMAIL_ENABLED")) {
            assertThat(compose).as("compose passes %s", variable).contains(variable + ": ${" + variable + ":");
            assertThat(application + dev).as("the application reads %s", variable).contains("${" + variable);
        }
        assertThat(application + dev).as("the application reads EMAIL_PROVIDER").contains("${EMAIL_PROVIDER");
        // A local dev run keeps its SMTP default (Mailpit in the root compose file).
        assertThat(dev).as("dev stays on SMTP unless told to use Resend").contains("provider: ${EMAIL_PROVIDER:smtp}")
                .contains("from: ${RESEND_FROM:${MAIL_FROM:}}");
        // The dev server's stack has no SMTP relay any more (Mailpit removed, PR #363): it sends through Resend only,
        // and the mail health check, which would probe the missing relay, is off.
        assertThat(compose).as("the deployed server sends through Resend").contains("EMAIL_PROVIDER: resend")
                .contains("MAIL_HEALTH_ENABLED: \"false\"");
    }

    @Test
    @DisplayName("WF-05 E1: the five SELLER_* variables reach the server, with a fallback that keeps a dev server working")
    void theSellerReachesTheServer() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
        String example = Files.readString(Path.of("deploy/.env.example"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        for (String variable : java.util.List.of("SELLER_NAME", "SELLER_TAX_CODE", "SELLER_ADDRESS",
                "SELLER_REPRESENTATIVE", "SELLER_REPRESENTATIVE_TITLE")) {
            assertThat(application).as("the application reads %s, blank by default", variable)
                    .contains("${" + variable + ":}");
            // ":-" and a value: a blank in the server's .env must not reach the application as an empty seller.
            assertThat(compose).as("compose passes %s", variable).containsPattern(variable + ": \\$\\{" + variable
                    + ":-[^}]+}");
            assertThat(example).as(".env.example lists %s", variable).contains("\n" + variable + "=");
        }
    }

    @Test
    @DisplayName("BE-3: approved print files get the FOGRA39 intent out of the box, on a laptop and on the server alike")
    void theBundledPrintProfileIsTheDefaultEverywhere() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
        String example = Files.readString(Path.of("deploy/.env.example"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String bundled = "classpath:print/icc/ISOcoated_v2_eci.icc";

        assertThat(application).as("a run with no variable set stamps the bundled profile")
                .contains("profile: ${DESIGN_PRINT_ICC_PROFILE:" + bundled + "}");
        // ":-": a blank left in the server's .env must not reach the application, where it means the interim sRGB.
        assertThat(compose).as("compose falls back to the bundled profile")
                .contains("DESIGN_PRINT_ICC_PROFILE: ${DESIGN_PRINT_ICC_PROFILE:-" + bundled + "}");
        for (String variable : java.util.List.of("DESIGN_PRINT_OUTPUT_CONDITION", "DESIGN_PRINT_OUTPUT_INFO")) {
            assertThat(application).as("the application reads %s", variable).contains("${" + variable + ":}");
            assertThat(compose).as("compose passes %s", variable).contains(variable + ": ${" + variable + ":-}");
        }
        for (String variable : java.util.List.of("DESIGN_PRINT_ICC_PROFILE", "DESIGN_PRINT_OUTPUT_CONDITION",
                "DESIGN_PRINT_OUTPUT_INFO")) {
            assertThat(example).as(".env.example lists %s as optional", variable).contains("\n# " + variable + "=");
        }
        assertThat(Files.size(Path.of("src/main/resources/print/icc/ISOcoated_v2_eci.icc")))
                .as("the profile is in the jar, as ECI published it").isEqualTo(1_829_077L);
    }
}
