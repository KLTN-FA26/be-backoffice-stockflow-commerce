package com.stockflow.common.config;

import com.stockflow.common.ratelimit.RateLimitInterceptor;
import com.stockflow.common.security.AuthenticatedUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers the application's MVC interceptors and argument resolvers.
 *
 * <p>Only {@code /api/**} is rate-limited. Actuator is excluded deliberately: throttling the health
 * probe would let a burst of traffic make Kubernetes believe the application is unhealthy and
 * restart it — the protection causing the outage it was meant to prevent.</p>
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor,
                        AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.authenticatedUserArgumentResolver = authenticatedUserArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**")
                .order(0);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authenticatedUserArgumentResolver);
    }
}
