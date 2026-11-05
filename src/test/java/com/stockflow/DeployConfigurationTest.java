package com.stockflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed environment gets what the application needs to do its core flows. {@code deploy/docker-compose.yml}
 * lists the application's variables one by one, so a variable the application reads but the compose file does not pass
 * is silently empty on the server (audit of #109: B2B onboarding answered 503 on the VPS). The same goes for the
 * proxy in front of it: an nginx timeout shorter than an answer cuts it off with nothing in the application's log.
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
    @DisplayName("SCRUM-352: the server gets the Resend settings; the dev server stays on SMTP until switched to Resend")
    void theResendSettingsReachTheServer() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String dev = Files.readString(Path.of("src/main/resources/application-dev.yml"), StandardCharsets.UTF_8);

        for (String variable : java.util.List.of("EMAIL_PROVIDER", "RESEND_API_KEY", "RESEND_FROM", "RESEND_REPLY_TO",
                "RESEND_WEBHOOK_SECRET", "CUSTOMER_EMAIL_ENABLED")) {
            assertThat(compose).as("compose passes %s", variable).contains(variable + ": ${" + variable + ":");
            assertThat(application + dev).as("the application reads %s", variable).contains("${" + variable);
        }
        // Deployed on every push to develop: a server not yet given a Resend key must still start (on its Mailpit).
        assertThat(dev).as("dev stays on SMTP unless told to use Resend").contains("provider: ${EMAIL_PROVIDER:smtp}")
                .contains("from: ${RESEND_FROM:${MAIL_FROM:}}");
        assertThat(compose).as("so does the deployed server").contains("EMAIL_PROVIDER: ${EMAIL_PROVIDER:-smtp}");
    }

    @Test
    @DisplayName("nginx lets an assistant answer run for its whole lease, unbuffered; every other location keeps its "
            + "timeouts")
    void theProxyDoesNotCutAnAnswer() throws Exception {
        // nginx refuses a directive given twice in one context, and an include is the same context: a timeout in the
        // shared snippet would make `nginx -t` fail in every location that sets its own.
        assertThat(read("deploy/nginx/snippets/proxy-headers.conf"))
                .doesNotContain("proxy_read_timeout", "proxy_send_timeout", "proxy_connect_timeout");
        assertThat(read("deploy/nginx/snippets/proxy-timeouts.conf")).contains("proxy_read_timeout    60s;");
        for (String template : List.of("deploy/nginx/templates/stockflow.conf.template",
                "deploy/nginx/templates/salesradar.conf.template")) {
            Matcher location = Pattern.compile("location ([^{]+)\\{([^}]*)}").matcher(read(template));
            while (location.find()) {
                if (location.group(2).contains("proxy-headers.conf")) {
                    assertThat(location.group(2)).as("%s, location %s, sets its timeouts", template,
                            location.group(1).strip()).containsAnyOf("proxy-timeouts.conf", "proxy_read_timeout");
                }
            }
        }

        Matcher assistant = Pattern.compile("location /api/v1/assistant/ \\{([^}]*)}")
                .matcher(read("deploy/nginx/templates/stockflow.conf.template"));
        assertThat(assistant.find()).as("the assistant has a location of its own").isTrue();
        assertThat(assistant.group(1)).contains("proxy_read_timeout    900s;", "proxy_buffering off;",
                "proxy_cache off;");
        assertThat(read("src/main/resources/application.yml")).as("900 s is the assistant's answer lease")
                .contains("answer-lease: PT15M");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
