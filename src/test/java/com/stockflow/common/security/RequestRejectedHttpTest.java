package com.stockflow.common.security;

import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real embedded Tomcat, because the defect lives in the container: the firewall's default handler
 * calls {@code sendError}, the container dispatches to {@code /error}, and that second dispatch is
 * refused as unauthenticated. MockMvc performs no error dispatch and would pass either way.
 */
@SpringBootTest(classes = RequestRejectedHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestRejectedHttpTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import({ApiRequestRejectedHandler.class, ApiAuthenticationEntryPoint.class, Messages.class,
            CorrelationIdFilter.class})
    static class Config {
        @Bean StaticMessageSource messageSource() { return new StaticMessageSource(); }

        /** The shape of the real chain: nothing public, and our entry point for the 401. */
        @Bean
        SecurityFilterChain chain(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint) throws Exception {
            return http
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                    .build();
        }
    }

    @LocalServerPort int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header(CorrelationIdFilter.HEADER, "rejected-url-test").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aDoubleSlashIsA400InTheEnvelopeNotARequestToSignIn() throws Exception {
        HttpResponse<String> response = get("/api/v1/purchase-orders//cancellation");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("\"errorCode\":\"REQUEST_REJECTED\"")
                .contains("\"correlationId\":\"rejected-url-test\"");
    }

    @Test
    void anEncodedDotDotIsRejectedTheSameWay() throws Exception {
        HttpResponse<String> response = get("/api/v1/products/%2e%2e/secret");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errorCode\":\"REQUEST_REJECTED\"");
    }

    /** Control: the chain does refuse anonymous callers, so the 400 above is not an open door. */
    @Test
    void aWellFormedUrlWithoutATokenIsStillA401() throws Exception {
        assertThat(get("/api/v1/purchase-orders/1/cancellation").statusCode()).isEqualTo(401);
    }
}
