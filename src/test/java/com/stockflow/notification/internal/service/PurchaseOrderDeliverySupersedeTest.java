package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.repository.PoDeliveryControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-117: once an amendment notice is published, the order's own delivery that was still being retried must not
 * reach the supplier after it - and must stay recoverable. The control row and the log are stood in for by state kept
 * here, so the service and the listener see each other's writes as they would in the database.
 */
class PurchaseOrderDeliverySupersedeTest {

    private static final UUID PO = UUID.randomUUID();
    private static final String REFERENCE = "purchase-order:" + PO;

    private final AtomicInteger generation = new AtomicInteger();
    private final List<DeliveryLogJpaEntity> saved = new ArrayList<>();
    private final PoDeliveryControlRepository controls = mock(PoDeliveryControlRepository.class);
    private final DeliveryLogJpaRepository logs = mock(DeliveryLogJpaRepository.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final NotificationServiceImpl service = new NotificationServiceImpl(logs, controls, sender, "");
    private final PurchaseOrderNotificationListener listener = new PurchaseOrderNotificationListener(sender, logs,
            Clock.systemUTC(), mock(DeliveryAttemptRecorder.class), controls);

    @BeforeEach
    void stateBehindTheMocks() {
        when(controls.lock(PO)).thenAnswer(call -> new PoDeliveryControlRepository.Control(generation.get(), false));
        doAnswer(call -> {
            generation.set(call.getArgument(1));
            return null;
        }).when(controls).advance(eq(PO), anyInt());
        when(logs.saveAndFlush(any())).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(eq(REFERENCE), anyInt()))
                .thenAnswer(call -> {
                    int asked = call.getArgument(1);
                    return saved.stream().anyMatch(row -> row.getDeliveryGeneration() == asked
                            && (boolean) ReflectionTestUtils.getField(row, "terminal"));
                });
        when(logs.tryLockDelivery(anyString())).thenReturn(true);
    }

    private static PurchaseOrderSent ownDelivery(int deliveryGeneration) {
        return new PurchaseOrderSent(PO, "PO-1", UUID.randomUUID(), "EMAIL", "supplier@example.com",
                BigDecimal.valueOf(240), "VND", LocalDate.of(2026, 11, 20), 30,
                List.of(new PurchaseOrderSent.Line("CHAIR-1", "Chair", 3, BigDecimal.valueOf(80))), deliveryGeneration,
                null, false, null);
    }

    @Test
    void ownDeliveryOfTheSupersededGenerationIsNotSentAndStaysRecoverable() {
        // Generation 0 failed once and waits for its retry when the amendment is approved.
        service.supersedeSupplierDelivery(PO, "EMAIL", "supplier@example.com");

        assertThat(generation.get()).isEqualTo(1);
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getDeliveryGeneration()).isEqualTo(1);
            assertThat(row.getStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(row.getError()).isEqualTo(NotificationServiceImpl.SUPERSEDED_BY_AMENDMENT);
            assertThat(row.getExternalReference()).as("not a success").isNull();
            assertThat((boolean) ReflectionTestUtils.getField(row, "terminal")).isTrue();
        });

        listener.on(ownDelivery(0));   // the retry of the pre-amendment order arrives after the notice

        verify(sender, never()).sendPurchaseOrder(any());
        assertThat(service.prepareSupplierDelivery(PO, true)).as("a recovery still resends the order").isEqualTo(2);
    }

    @Test
    void nothingIsSupersededOnceTheOrderWasDelivered() {
        when(logs.existsByExternalReference(REFERENCE)).thenReturn(true);

        service.supersedeSupplierDelivery(PO, "EMAIL", "supplier@example.com");

        assertThat(generation.get()).isZero();
        assertThat(saved).isEmpty();
        verify(controls, never()).advance(any(), anyInt());
    }
}
