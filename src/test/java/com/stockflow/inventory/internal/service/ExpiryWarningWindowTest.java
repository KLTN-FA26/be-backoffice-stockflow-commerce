package com.stockflow.inventory.internal.service;

import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** {@code stockflow.inventory.expiry-warning-days}: handed on as configured, and a nonsense value stops startup. */
class ExpiryWarningWindowTest {

    private static InventoryControlServiceImpl service(int days) {
        return new InventoryControlServiceImpl(mock(InventoryPolicyRepository.class), Clock.systemUTC(),
                mock(CurrentUserProvider.class), mock(ApplicationEventPublisher.class), days);
    }

    @Test
    void handsOnTheConfiguredWindow() {
        assertThat(service(30).expiryWarningDays()).isEqualTo(30);
        assertThat(service(7).expiryWarningDays()).isEqualTo(7);
    }

    @Test
    void aWindowBelowOneDayIsAConfigurationError() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry-warning-days");
        assertThatThrownBy(() -> service(-5)).isInstanceOf(IllegalArgumentException.class);
    }
}
