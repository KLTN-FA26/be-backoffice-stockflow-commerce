package com.stockflow.common.persistence;

import com.stockflow.common.security.CurrentUserProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * Wires Spring Data auditing to our own {@link CurrentUserProvider}.
 *
 * <p>The fallback matters: event listeners, the reservation sweeper and the nightly purge job run on
 * threads with no security context. Writing "system" there is honest; leaving null and later
 * wondering who deleted a row is not.</p>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    static final String SYSTEM_AUDITOR = "system";

    @Bean
    public AuditorAware<String> auditorAware(CurrentUserProvider currentUserProvider) {
        return () -> Optional.of(currentUserProvider.current()
                .map(user -> user.username() != null ? user.username() : user.userId().toString())
                .orElse(SYSTEM_AUDITOR));
    }
}
