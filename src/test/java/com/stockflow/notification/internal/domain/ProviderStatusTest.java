package com.stockflow.notification.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Webhook reports arrive late, twice and out of order; the stored one only ever says more. */
class ProviderStatusTest {

    private static final Instant T1 = Instant.parse("2026-10-24T08:00:00Z");
    private static final Instant T2 = T1.plusSeconds(60);

    @Test
    @DisplayName("the first report is always kept")
    void first() {
        assertThat(ProviderStatus.DELIVERY_DELAYED.replaces(null, null, T1)).isTrue();
    }

    @Test
    @DisplayName("a late 'delayed' never overwrites 'delivered', and 'delivered' never overwrites a bounce")
    void neverBackwards() {
        assertThat(ProviderStatus.DELIVERY_DELAYED.replaces(ProviderStatus.DELIVERED, T1, T2)).isFalse();
        assertThat(ProviderStatus.DELIVERED.replaces(ProviderStatus.BOUNCED, T1, T2)).isFalse();
        assertThat(ProviderStatus.DELIVERED.replaces(ProviderStatus.DELIVERY_DELAYED, T2, T1)).isTrue();
    }

    @Test
    @DisplayName("a complaint after the delivery it complains about replaces it")
    void complaintAfterDelivery() {
        assertThat(ProviderStatus.COMPLAINED.replaces(ProviderStatus.DELIVERED, T1, T2)).isTrue();
    }

    @Test
    @DisplayName("between two final reports the later one wins, and a repeated report changes nothing")
    void betweenFinalReports() {
        assertThat(ProviderStatus.COMPLAINED.replaces(ProviderStatus.BOUNCED, T1, T2)).isTrue();
        assertThat(ProviderStatus.COMPLAINED.replaces(ProviderStatus.BOUNCED, T2, T1)).isFalse();
        assertThat(ProviderStatus.BOUNCED.replaces(ProviderStatus.BOUNCED, T1, T1)).isFalse();
    }
}
