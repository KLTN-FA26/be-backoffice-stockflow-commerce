package com.stockflow.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing for identity-service.
 *
 * <p>{@code createDelegatingPasswordEncoder()} stores the algorithm as a prefix
 * ({@code {bcrypt}$2a$10$...}), so the hash format can be upgraded later without invalidating
 * every existing password. Hard-coding {@code new BCryptPasswordEncoder()} throws that away.</p>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
