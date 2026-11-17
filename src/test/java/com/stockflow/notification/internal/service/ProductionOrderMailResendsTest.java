package com.stockflow.notification.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.notification.api.ProductionOrderResends;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.ProductionOrderMailResend;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staff's re-send of a production order's latest message (4A-3): the next generation of "sample approved" once there was
 * one, else of "sent"; refused while the latest is still on its way, and before anything was attempted.
 */
class ProductionOrderMailResendsTest {

    private static final UUID ID = UUID.fromString("0192f000-0000-7000-8000-0000000000aa");
    private static final String SENT = "production-order:" + ID + ":sent";
    private static final String SAMPLE = "production-order:" + ID + ":sample-approved";
    private static final Instant NOW = Instant.parse("2026-12-20T03:00:00Z");

    private final DeliveryLogJpaRepository logs = mock(DeliveryLogJpaRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ProductionOrderMailResends resends = new ProductionOrderMailResends(logs, events);

    @BeforeEach
    void setUp() {
        when(logs.tryLockDelivery(anyString())).thenReturn(true);
        // Never attempted, unless a test says otherwise (Mockito would answer 0, a generation that exists).
        when(logs.latestGeneration(anyString())).thenReturn(null);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("the 'sent' message, sent or given up on: generation 1, one PENDING row, the event after commit")
    void resendsTheSentMessage() {
        when(logs.latestGeneration(SENT)).thenReturn(0);
        when(logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(SENT, 0)).thenReturn(true);

        ProductionOrderResends.Resend resend = resends.resend(ID, NOW);

        assertThat(resend).isEqualTo(new ProductionOrderResends.Resend("SENT", 1));
        ArgumentCaptor<DeliveryLogJpaEntity> row = ArgumentCaptor.forClass(DeliveryLogJpaEntity.class);
        verify(logs).saveAndFlush(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(row.getValue().getError()).isEqualTo(ProductionOrderMailResends.RESEND_REQUESTED);
        assertThat(row.getValue().getOperationReference()).isEqualTo(SENT);
        assertThat(row.getValue().getDeliveryGeneration()).isEqualTo(1);
        assertThat(row.getValue().isTerminal()).isFalse();
        ArgumentCaptor<ProductionOrderMailResend> event = ArgumentCaptor.forClass(ProductionOrderMailResend.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).satisfies(e -> {
            assertThat(e.productionOrderId()).isEqualTo(ID);
            assertThat(e.kind()).isEqualTo("SENT");
            assertThat(e.generation()).isEqualTo(1);
            assertThat(e.requestedAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("once the approved sample was written about, that is the latest message, and it is what goes again")
    void resendsTheLatestMessage() {
        when(logs.latestGeneration(SENT)).thenReturn(0);
        when(logs.latestGeneration(SAMPLE)).thenReturn(1);
        when(logs.countByOperationReferenceAndDeliveryGenerationAndStatus(SAMPLE, 1, DeliveryStatus.SENT))
                .thenReturn(1L);

        assertThat(resends.resend(ID, NOW)).isEqualTo(new ProductionOrderResends.Resend("SAMPLE_APPROVED", 2));
    }

    @Test
    @DisplayName("the latest still being sent or retried, or a second click: refused, nothing written")
    void refusedWhileOnItsWay() {
        when(logs.latestGeneration(SENT)).thenReturn(1);

        assertThatThrownBy(() -> resends.resend(ID, NOW)).extracting(ProductionOrderMailResendsTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED);
        verify(logs, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("nothing attempted yet, or a message being sent right now: refused")
    void refusedBeforeTheFirstAttempt() {
        assertThatThrownBy(() -> resends.resend(ID, NOW)).extracting(ProductionOrderMailResendsTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED);
        when(logs.tryLockDelivery("production-order:" + ID)).thenReturn(false);
        assertThatThrownBy(() -> resends.resend(ID, NOW)).extracting(ProductionOrderMailResendsTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED);
        verify(events, never()).publishEvent(any());
    }
}
