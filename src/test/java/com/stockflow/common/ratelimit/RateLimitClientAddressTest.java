package com.stockflow.common.ratelimit;

import com.stockflow.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-483: the rate limit keys on the address the container reports, and the container believes
 * {@code X-Forwarded-For} only from a trusted proxy. A client that forges the header must not get a
 * bucket of its own per request; the proxy in front of the application must still be able to pass the
 * real client through.
 *
 * <p>The embedded Tomcat here is started with the {@code server.*} values of {@code application-prod.yml}
 * itself (read from the file, placeholders resolved), so the deployed setting is what is tested.</p>
 */
class RateLimitClientAddressTest {

    private static final String DEFAULT_TRUSTED_PEER = "127.0.0.1";   // the test client; inside the default set

    // ---- the interceptor ----------------------------------------------------------------------------

    @Test
    @DisplayName("the interceptor keys on the remote address and ignores a forwarded header")
    void forwardedHeaderDoesNotChangeTheKey() throws Exception {
        var limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allowed(4));
        var interceptor = new RateLimitInterceptor(limiter, mock(CurrentUserProvider.class));
        var handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("login"));

        for (String forged : new String[] {"1.1.1.1", "2.2.2.2, 3.3.3.3", "not-an-ip"}) {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("198.51.100.9");
            request.addHeader("X-Forwarded-For", forged);
            interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        }

        var keys = ArgumentCaptor.forClass(String.class);
        verify(limiter, org.mockito.Mockito.times(3)).tryAcquire(keys.capture(), anyInt(), any());
        assertThat(keys.getAllValues()).containsOnly("Endpoint.login:ip:198.51.100.9");
    }

    @Test
    @DisplayName("an unknown remote address is one shared bucket, not a bypass")
    void unknownRemoteAddress() throws Exception {
        var limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allowed(4));
        var interceptor = new RateLimitInterceptor(limiter, mock(CurrentUserProvider.class));
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9");

        interceptor.preHandle(request, new MockHttpServletResponse(),
                new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("login")));

        verify(limiter).tryAcquire(org.mockito.ArgumentMatchers.eq("Endpoint.login:ip:unknown"), anyInt(), any());
    }

    static class Endpoint {
        @RateLimit(limit = 5, perSeconds = 60, key = RateLimit.Key.IP)
        public void login() { }
    }

    // ---- the container, with the deployed configuration -----------------------------------------------

    @Test
    @DisplayName("a trusted proxy's X-Forwarded-For becomes the client address")
    void trustedProxyIsHonoured() throws Exception {
        try (var server = Server.start(serverProperties("application-prod.yml", Map.of()))) {
            assertThat(server.get("203.0.113.7", "https")).isEqualTo("203.0.113.7|https");
            // nginx sends one entry; a chain is walked from the right, past trusted hops.
            assertThat(server.get("198.51.100.4, 10.0.0.3", "https")).isEqualTo("198.51.100.4|https");
        }
    }

    @Test
    @DisplayName("X-Forwarded-For from a caller that is not a trusted proxy is ignored")
    void spoofedHeaderFromAnUntrustedPeerIsIgnored() throws Exception {
        // Same deployed config, but the trusted set is somewhere else: this test client is now "any caller".
        var overrides = Map.<String, Object>of("TRUSTED_PROXIES", "203\\.0\\.113\\.1");
        try (var server = Server.start(serverProperties("application-prod.yml", overrides))) {
            assertThat(server.get("203.0.113.7", "https")).isEqualTo(DEFAULT_TRUSTED_PEER + "|http");
            assertThat(server.get("203.0.113.8", "https")).isEqualTo(DEFAULT_TRUSTED_PEER + "|http");
        }
    }

    @Test
    @DisplayName("dev and prod use the native strategy; the default trusted set is the private ranges only")
    void profilesAndDefaultTrustedSet() throws Exception {
        for (String file : new String[] {"application-prod.yml", "application-dev.yml"}) {
            var props = serverProperties(file, Map.of());
            assertThat(props.get("server.forward-headers-strategy")).as(file).isEqualTo("native");
            var trusted = Pattern.compile((String) props.get("server.tomcat.remoteip.internal-proxies"));
            for (String proxy : new String[] {"172.18.0.5", "172.16.0.2", "172.31.255.2", "10.0.4.9", "192.168.1.2",
                    "127.0.0.1", "::1"}) {
                assertThat(trusted.matcher(proxy).matches()).as(file + " trusts " + proxy).isTrue();
            }
            for (String client : new String[] {"8.8.8.8", "172.32.0.1", "172.15.0.1", "203.0.113.7", "11.0.0.1",
                    "2001:db8::1"}) {
                assertThat(trusted.matcher(client).matches()).as(file + " trusts " + client).isFalse();
            }
        }
    }

    /** The {@code server.*} entries of a profile file, placeholders resolved the way Spring would. */
    private static Map<String, Object> serverProperties(String file, Map<String, Object> overrides) throws IOException {
        var loaded = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        var environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource("overrides", overrides));
        loaded.forEach(sources::addLast);
        Map<String, Object> out = new HashMap<>();
        for (String key : new String[] {"server.forward-headers-strategy", "server.tomcat.remoteip.internal-proxies"}) {
            out.put(key, environment.getProperty(key));
        }
        return out;
    }

    /** An embedded Tomcat that answers {@code remoteAddr|scheme}. */
    private record Server(ConfigurableApplicationContext context, int port) implements AutoCloseable {

        static Server start(Map<String, Object> serverProperties) {
            // Command-line arguments, not default properties: the classpath application.yml would win over those.
            var arguments = new java.util.ArrayList<String>();
            arguments.add("--server.port=0");
            serverProperties.forEach((key, value) -> arguments.add("--" + key + "=" + value));
            var context = new SpringApplicationBuilder(Echo.class)
                    .web(WebApplicationType.SERVLET).run(arguments.toArray(String[]::new));
            int port = ((TomcatWebServer) ((ServletWebServerApplicationContext) context).getWebServer()).getPort();
            return new Server(context, port);
        }

        String get(String forwardedFor, String forwardedProto) throws Exception {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
                            .header("X-Forwarded-For", forwardedFor)
                            .header("X-Forwarded-Proto", forwardedProto)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        }

        @Override
        public void close() {
            context.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ServletWebServerFactoryAutoConfiguration.class, EmbeddedWebServerFactoryCustomizerAutoConfiguration.class})
    static class Echo {
        @Bean
        ServletRegistrationBean<HttpServlet> echo() {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    response.getWriter().write(request.getRemoteAddr() + "|" + request.getScheme());
                }
            }, "/echo");
        }
    }
}
