package com.stockflow.common.config;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Message translation, driven by the {@code Accept-Language} header.
 *
 * <h2>Vietnamese is the default, not English</h2>
 *
 * <p>The users are a Vietnamese furniture business. A client that sends no {@code Accept-Language}
 * — most mobile apps, most server-to-server calls — gets Vietnamese. English is available for the
 * developer-facing surface and for anybody who asks for it.</p>
 *
 * <p>{@code setDefaultLocale} matters as much as the supported list: without it, Spring falls back
 * to the <b>JVM's</b> locale, which is whatever the container image happens to have. The same build
 * would then answer in English on one host and Vietnamese on another, which is close to
 * impossible to reproduce.</p>
 *
 * <h2>UTF-8 is not optional here</h2>
 *
 * <p>Java properties files are ISO-8859-1 by default. Without
 * {@code setDefaultEncoding(UTF_8)}, every Vietnamese diacritic in {@code messages_vi.properties}
 * is served as mojibake — and it renders correctly in the IDE, so it is invisible until somebody
 * opens the app.</p>
 */
@Configuration(proxyBeanMethods = false)
public class I18nConfig {

    /** {@code Locale.of} rather than the deprecated {@code new Locale(...)}; Java 19+. */
    private static final Locale VIETNAMESE = Locale.of("vi");

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(
                "classpath:i18n/messages",          // our own error messages
                "classpath:i18n/validation");       // Bean Validation overrides
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Fall back to the code's own English default rather than rendering "???error.foo???",
        // which tells the user nothing and looks like a crash.
        source.setUseCodeAsDefaultMessage(false);
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /**
     * Points Bean Validation at the {@link MessageSource} above.
     *
     * <h2>Without this bean the validation bundles are dead files</h2>
     *
     * <p>Spring Boot's default validator uses a plain {@code ResourceBundleMessageInterpolator},
     * which resolves {@code {jakarta.validation.constraints.NotBlank.message}} against a bundle
     * called {@code ValidationMessages} at the <b>classpath root</b>, then falls back to Hibernate
     * Validator's own built-in bundle. It never consults Spring's {@code MessageSource}. So
     * {@code i18n/validation.properties} and {@code i18n/validation_vi.properties} would be read by
     * nothing at all, and a Vietnamese user would get Hibernate's English "must not be blank" —
     * with no error anywhere to say why.</p>
     *
     * <p>{@code setValidationMessageSource} replaces the interpolator with one backed by the
     * message source, which is why the {@code jakarta.validation.constraints.*} keys have to stay in
     * the bundle: once this is wired, they are the only source.</p>
     *
     * <p>The bean must be named {@code validator} — that is the name
     * {@code ValidationAutoConfiguration} backs off from, and the name Spring MVC looks up.</p>
     */
    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(VIETNAMESE);
        resolver.setSupportedLocales(List.of(VIETNAMESE, Locale.ENGLISH));
        return resolver;
    }

    /**
     * Makes Vietnamese the default <b>outside</b> a request too.
     *
     * <p>{@link AcceptHeaderLocaleResolver} only covers requests. A scheduled job, an event
     * listener or a startup message reads {@code LocaleContextHolder.getLocale()}, which falls back
     * to {@code Locale.getDefault()} — whatever locale the container image happens to carry. The
     * same build would then produce Vietnamese on one host and English on another, which is close
     * to impossible to reproduce.</p>
     *
     * <p>An {@code @PostConstruct} rather than a JVM flag so the guarantee lives with the code
     * rather than in a Dockerfile that a different deployment might not use.</p>
     */
    @jakarta.annotation.PostConstruct
    void applyDefaultLocale() {
        LocaleContextHolder.setDefaultLocale(VIETNAMESE);
    }
}
