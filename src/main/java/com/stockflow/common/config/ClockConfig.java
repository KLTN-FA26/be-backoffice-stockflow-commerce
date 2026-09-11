package com.stockflow.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's single {@link Clock} bean.
 *
 * <p><b>Why a Clock bean at all.</b> Reservation expiry, order timestamps and the sweeper are all
 * time-dependent. With {@code Instant.now()} hard-coded in each of them, testing "what happens 31
 * minutes later" means either sleeping for 31 minutes or not testing it. With an injected clock a
 * test registers {@code Clock.fixed(...)}, moves it, and asserts.</p>
 *
 * <p><b>Why here and not one per module.</b> A {@code @Bean} in each module plus
 * {@code @ConditionalOnMissingBean} would work only by luck: in application code — as opposed to
 * an auto-configuration — the condition is evaluated in configuration-processing order, which
 * Spring does not guarantee. One bean in {@code common}, which every module may depend on, has no
 * ordering question to get wrong.</p>
 *
 * <p>{@code proxyBeanMethods = false} because nothing here calls another {@code @Bean} method, so
 * the CGLIB subclass Spring would otherwise generate is pure startup cost.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * UTC, not the system default zone. Servers move between regions and laptops are set to local
     * time; storing instants in UTC and converting at the edge is the only version of this that
     * survives a deployment to a second data centre.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
