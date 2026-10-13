package com.stockflow.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link IntegrationTest} with the real security filter chain switched on and a {@code MockMvc}.
 *
 * <p>Every test class that carries it (and declares {@code @MockitoBean JwtDecoder}) shares one cached
 * application context, so adding a security test does not start the application again. Do not add
 * properties here for one test's sake: that splits the cache for everyone.</p>
 *
 * <p>Pair it with {@link SecuredMvc} / {@link TestTokens}.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
public @interface SecuredIntegrationTest {
}
