package com.stockflow.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Timeouts for outbound HTTP calls, bound from {@code stockflow.http.*}.
 *
 * <h2>Why these are not optional</h2>
 *
 * <p>Java's {@code HttpClient} has <b>no default read timeout</b>. A call to a dependency that
 * accepts the connection and then stops responding will wait for ever, holding the calling thread
 * and, behind it, a database connection and a servlet thread. Enough of those and the application
 * stops serving anything at all — brought down by a service it merely calls. Setting a timeout is
 * the single most valuable line in this class.</p>
 *
 * @param connectTimeout how long to wait for the TCP connection. Short: a healthy service connects
 *                       in milliseconds, and a slow connect means it is unreachable, not busy.
 * @param readTimeout    how long to wait for the response once connected. Must be shorter than the
 *                       caller's own budget, otherwise our client gives up after our user already has.
 */
@ConfigurationProperties(prefix = "stockflow.http")
public record HttpClientProperties(
        @DefaultValue("PT3S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout
) {

    public HttpClientProperties {
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            // Zero means "infinite" in most HTTP clients, which is precisely the state this class
            // exists to prevent. Refusing at startup beats discovering it during an incident.
            throw new IllegalArgumentException(
                    "HTTP timeouts must be positive; zero or negative means no timeout at all");
        }
    }
}
