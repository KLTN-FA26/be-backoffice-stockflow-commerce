package com.stockflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StockFlowCommerce — modular monolith.
 *
 * <p>One deployable, one database, 14 application modules. Each direct sub-package of
 * {@code com.stockflow} is a module in the Spring Modulith sense: what sits in its base package is
 * public API, everything in a nested package is internal and unreachable from other modules.</p>
 *
 * <p>{@code common} and {@code contracts} are declared shared, so every module may use them
 * without listing them as a dependency. Everything else must be declared explicitly in the
 * module's {@code package-info.java} — and {@code ModularityTest} fails the build when the code
 * and the declaration disagree.</p>
 */
@SpringBootApplication
// Registers every @ConfigurationProperties type under com.stockflow. Deliberately here rather than
// @EnableConfigurationProperties on each config class: several of those are @ConditionalOnProperty,
// and registering properties from a conditional class leaves the other branch with nothing to
// inject - a context that fails to start under one profile and works under another.
@ConfigurationPropertiesScan
@Modulithic(
        systemName = "StockFlowCommerce",
        sharedModules = {"common", "contracts"})
@EnableAsync
@EnableScheduling
public class StockFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockFlowApplication.class, args);
    }
}
