package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.DesignVerification;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.DesignVerificationJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.identity.api.IdentityService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentServiceImplTest {

    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final DesignVerificationJpaRepository evidence = mock(DesignVerificationJpaRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final DesignService designs = mock(DesignService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final Instant now = Instant.parse("2026-09-18T08:00:00Z");
    private final FulfillmentServiceImpl service = new FulfillmentServiceImpl(
            picks, packs, evidence, orders, designs, identities, Clock.fixed(now, ZoneOffset.UTC));
    private final List<DesignVerificationJpaEntity> recorded = new ArrayList<>();

    @BeforeEach
    void retainVerificationEvidence() {
        when(evidence.save(any())).thenAnswer(call -> {
            DesignVerificationJpaEntity value = call.getArgument(0);
            recorded.add(value);
            return value;
        });
        when(evidence.findByPackIdOrderByVerifiedAtDesc(any())).thenAnswer(call -> List.copyOf(recorded));
    }

    @Test
    void checksumMismatchPutsPackageAndOrderOnHoldWithoutLosingEvidence() {
        UUID assigned = UUID.randomUUID();
        var pick = pickedTask(assigned);
        var pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.PENDING, null);
        String expected = "a".repeat(64);
        String actual = "b".repeat(64);
        var order = order(pick.getOrderId(), expected);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(order));
        when(designs.verifyForFulfillment(order.lines().getFirst().designSnapshotId(), expected))
                .thenReturn(new DesignVerification(order.lines().getFirst().designSnapshotId(), expected, actual, false));

        var result = service.completePacking(pick.getId(), user(assigned, Role.WAREHOUSE_STAFF));

        assertThat(result.packStatus()).isEqualTo("ON_HOLD");
        assertThat(result.integrityEvidence()).singleElement().satisfies(item -> {
            assertThat(item.matching()).isFalse();
            assertThat(item.actualChecksum()).isEqualTo(actual);
        });
        verify(orders).putOnDesignHold(pick.getOrderId(), "DESIGN_MISMATCH");
        verify(orders, never()).resolveDesignHold(any(), any(), any());
    }

    @Test
    void onlySuccessfulQcReverificationCompletesHeldPackageAndResolvesOrder() {
        UUID assigned = UUID.randomUUID();
        UUID qc = UUID.randomUUID();
        var pick = pickedTask(assigned);
        var pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.ON_HOLD, null);
        String expected = "c".repeat(64);
        var order = order(pick.getOrderId(), expected);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(order));
        when(designs.verifyForFulfillment(order.lines().getFirst().designSnapshotId(), expected))
                .thenReturn(new DesignVerification(order.lines().getFirst().designSnapshotId(), expected, expected, true));

        var result = service.reverifyDesignIntegrity(
                pick.getId(), "Artifact restored from the confirmed immutable version", user(qc, Role.QC_STAFF));

        assertThat(result.packStatus()).isEqualTo("PACKED");
        verify(orders).resolveDesignHold(pick.getOrderId(), qc,
                "Artifact restored from the confirmed immutable version");
    }

    @Test
    void unrelatedWarehouseUserCannotSeeTaskOrDownloadArtifact() {
        var pick = pickedTask(UUID.randomUUID());
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));

        var unrelated = user(UUID.randomUUID(), Role.WAREHOUSE_STAFF);
        assertThatThrownBy(() -> service.findTask(pick.getId(), unrelated))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.artifactDownload(
                pick.getId(), UUID.randomUUID(), "PRINT_READY", unrelated))
                .isInstanceOf(BusinessException.class);
        verify(designs, never()).fulfillmentDownload(any(), any(), any());
    }

    @Test
    void aCancelledOrderCannotBePickedPackedReverifiedOrDownloaded() {
        UUID assigned = UUID.randomUUID();
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), PickStatus.PENDING);
        pick.assign(assigned, now.minusSeconds(120));
        var live = order(pick.getOrderId(), "d".repeat(64));
        var cancelled = new OrderSummary(live.orderId(), live.orderNumber(), live.customerId(), OrderStatus.CANCELLED,
                live.total(), live.lines(), live.placedAt(), null, null, null, null, null, null, null, null);
        var pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.ON_HOLD, null);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(orders.findById(pick.getOrderId())).thenReturn(Optional.of(cancelled));
        var worker = user(assigned, Role.WAREHOUSE_STAFF);
        var qc = user(UUID.randomUUID(), Role.QC_STAFF);

        assertThatThrownBy(() -> service.startPicking(pick.getId(), worker)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.completePicking(pick.getId(), worker)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.completePacking(pick.getId(), worker)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reverifyDesignIntegrity(pick.getId(), "note", qc))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.artifactDownload(pick.getId(), live.lines().getFirst().lineId(), "PRINT_READY", qc))
                .isInstanceOf(BusinessException.class);

        assertThat(pick.getStatus()).isEqualTo(PickStatus.PENDING);
        verify(designs, never()).fulfillmentDownload(any(), any(), any());
        verify(orders, never()).putOnDesignHold(any(), any());
        verify(orders, never()).resolveDesignHold(any(), any(), any());
    }

    private PickJpaEntity pickedTask(UUID assigned) {
        var pick = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), PickStatus.PENDING);
        pick.assign(assigned, now.minusSeconds(120));
        pick.start(assigned, now.minusSeconds(60));
        pick.complete(assigned, now.minusSeconds(30));
        return pick;
    }

    private OrderSummary order(UUID orderId, String checksum) {
        UUID snapshot = UUID.randomUUID();
        var line = new OrderSummary.LineSummary(UUID.randomUUID(), "CUSTOM-SKU", 1,
                Money.vnd(100_000), Money.vnd(100_000), List.of(), snapshot, checksum);
        return new OrderSummary(orderId, "SO-TEST", UUID.randomUUID(), OrderStatus.IN_FULFILMENT,
                Money.vnd(100_000), List.of(line), now.minusSeconds(600), null, null, null, null, null, null, null, null);
    }

    private CurrentUser user(UUID id, Role role) {
        return new CurrentUser(id, role.name().toLowerCase(), Set.of(role), Set.of(), DataScope.ALL, Set.of());
    }
}
