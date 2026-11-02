package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.ReturnPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The thresholds of the return rules (SCRUM-243), from configuration; see {@link ReturnPolicy} for what each one is and
 * why it has its default. The return window is the order's own {@code stockflow.order.return-window-days}, the figure
 * after which a delivered order completes (BR-ORD-005): a claim is possible exactly while the order could still
 * complete, never after it closed its books.
 */
@Configuration(proxyBeanMethods = false)
class ReturnPolicyConfig {

    @Bean
    ReturnPolicy returnPolicy(@Value("${stockflow.order.return-window-days:7}") int windowDays,
                              @Value("${stockflow.order.returns.deadline-days:14}") int deadlineDays,
                              @Value("${stockflow.order.returns.no-return-max-value:2000000}") BigDecimal noReturnMaxValue,
                              @Value("${stockflow.order.returns.evidence-required:true}") boolean evidenceRequired,
                              @Value("${stockflow.order.returns.max-evidence:10}") int maxEvidence,
                              @Value("${stockflow.order.returns.change-of-mind-for-stock-items:false}")
                              boolean changeOfMind) {
        return new ReturnPolicy(Duration.ofDays(windowDays), Duration.ofDays(deadlineDays), noReturnMaxValue,
                evidenceRequired, maxEvidence, changeOfMind);
    }
}
