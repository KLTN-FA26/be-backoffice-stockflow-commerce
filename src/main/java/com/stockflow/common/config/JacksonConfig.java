package com.stockflow.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A JSON mapper for contexts that boot <b>without</b> the web starter.
 *
 * <h2>Read this before adding an {@code ObjectMapper} bean anywhere</h2>
 *
 * <p>An earlier version of this class declared a plain {@code @Bean @ConditionalOnMissingBean
 * ObjectMapper} with a comment claiming Spring Boot's auto-configured mapper would win. <b>The
 * opposite happens.</b> User {@code @Configuration} classes are parsed before auto-configuration
 * (which arrives through a {@code DeferredImportSelector}), so at the moment the condition is
 * evaluated no mapper exists yet — this bean is registered, and
 * {@code JacksonAutoConfiguration}, itself {@code @ConditionalOnMissingBean}, backs off.</p>
 *
 * <p>The consequences were severe and would not have shown up in any test that does not go over
 * HTTP:</p>
 * <ul>
 *   <li>a bare {@code new ObjectMapper()} has no {@code JavaTimeModule}, and every
 *       {@code ApiResponse} carries an {@code Instant} — so <b>every endpoint</b> would fail with
 *       {@code InvalidDefinitionException: Java 8 date/time type not supported by default},
 *       including inside {@link com.stockflow.common.security.ApiAuthenticationEntryPoint} while it
 *       is already writing a 401;</li>
 *   <li>{@code spring.jackson.*} in {@code application.yml} — ISO dates, omit nulls — would be
 *       silently ignored, because those settings are applied by the auto-configured builder that
 *       had backed off.</li>
 * </ul>
 *
 * <p>So the rule is: <b>in a web application, do not declare an {@code ObjectMapper} bean at
 * all.</b> Customise Boot's through {@code spring.jackson.*} or a
 * {@code Jackson2ObjectMapperBuilderCustomizer}.</p>
 *
 * <p>{@code @ConditionalOnMissingClass} on the dispatcher is what keeps this correct: the bean below
 * exists only when there is no Spring MVC on the classpath — a batch entry point, or a slice test —
 * where Boot's web-flavoured mapper genuinely is absent. In this application it never registers.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingClass("org.springframework.web.servlet.DispatcherServlet")
public class JacksonConfig {

    /**
     * Registers {@link JavaTimeModule} explicitly, because nothing else would in a non-web context
     * and every response envelope carries an {@code Instant}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }
}
