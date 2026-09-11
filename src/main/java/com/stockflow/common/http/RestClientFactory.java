package com.stockflow.common.http;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Builds outbound HTTP clients that are configured correctly by default.
 *
 * <h2>Why a factory instead of letting each module build its own</h2>
 *
 * <p>Because the defaults are what matter, and a client built ad hoc gets none of them. A plain
 * {@code RestClient.create()} has no read timeout, no correlation id propagation and no logging —
 * and the absence of the first one is the difference between a slow dependency and an outage. One
 * factory means a module integrating with a payment gateway or a shipping API starts from the
 * right place rather than from the shortest snippet on the internet.</p>
 *
 * <pre>
 * &#64;Configuration
 * class ShippingClientConfig {
 *     &#64;Bean
 *     RestClient ghnClient(RestClientFactory factory) {
 *         return factory.forService("ghn", "https://api.ghn.vn").build();
 *     }
 * }
 * </pre>
 *
 * <h2>What this does not do, and what to add when you need it</h2>
 *
 * <p>There is no circuit breaker here. Retries are available through Spring Retry —
 * {@code @Retryable(retryFor = ExternalServiceException.class)} with a backoff, guarded by
 * {@link ExternalServiceException#isRetryable()} so 4xx responses are not retried. That covers a
 * blip. It does not cover a dependency that is <b>down</b>: retries then multiply load on a service
 * that is already failing, and every call still pays the full timeout. A circuit breaker
 * (Resilience4j) is the right answer at that point, and belongs here, wrapping the client this
 * factory returns. Recorded rather than pre-built because it needs real failure characteristics to
 * tune, and an untuned breaker is worse than none.</p>
 */
@Configuration(proxyBeanMethods = false)
public class RestClientFactory {

    private final HttpClientProperties properties;

    public RestClientFactory(HttpClientProperties properties) {
        this.properties = properties;
    }

    /**
     * A builder pre-configured with timeouts, correlation propagation and call logging.
     *
     * @param serviceName appears in every log line and in {@link ExternalServiceException}. Use the
     *                    dependency's name, not the URL - the URL changes per environment and makes
     *                    logs from different environments impossible to compare.
     */
    public RestClient.Builder forService(String serviceName, String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .build());
        // Set separately: the JDK client applies its own timeout per request, not per connection,
        // and Spring's factory is what carries the read timeout through.
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(OutboundInterceptors.propagateCorrelationId())
                .requestInterceptor(OutboundInterceptors.logCalls(serviceName));
    }
}
