package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.domain.CartonCheck;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CartonCheckStore;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link CartonCheckService} decides around the pure {@link CartonCheck} (team plan 4C-6): when the first label
 * locks the order, what the payment warning says, how a completion is replayed and who may print a label again. The
 * rules themselves are CartonCheckTest's, the flow over HTTP and the database the station integration test's.
 */
class CartonCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    private static final UUID WAREHOUSE = UUID.randomUUID();

    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final CartonCheckStore store = mock(CartonCheckStore.class);
    private final OrderService orders = mock(OrderService.class);
    private final InventoryControlService itemControls = mock(InventoryControlService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final CartonCheckService service = new CartonCheckService(picks, packs, mock(PackageJpaRepository.class),
            shipments, store, orders, itemControls, payments, identities, mock(WarehouseService.class), timeline,
            Clock.fixed(NOW, ZoneOffset.UTC), 6000);

    private final UUID worker = UUID.randomUUID();
    private final UUID otherWorker = UUID.randomUUID();
    private final CurrentUser actor = user(worker, Set.of());
    private final CurrentUser colleague = user(otherWorker, Set.of());
    private final CurrentUser manager = user(UUID.randomUUID(),
            Set.of(PermissionCode.of(FulfillmentPermissions.PACKING, Action.APPROVE)));

    private final UUID orderId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private PickJpaEntity pick;
    private PackJpaEntity pack;
    private CartonCheck check;

    private static CurrentUser user(UUID id, Set<PermissionCode> permissions) {
        return new CurrentUser(id, "u-" + id, Set.of(Role.WAREHOUSE_STAFF), permissions, DataScope.ALL, Set.of(),
                Set.of());
    }

    @BeforeEach
    void aPickedTaskWithTwoCartonsOfOneLine() {
        pick = new PickJpaEntity(UUID.randomUUID(), null, orderId, "SO-0001", WAREHOUSE, PickStatus.PENDING);
        pick.assign(worker, NOW.minusSeconds(300));
        pick.start(worker, NOW.minusSeconds(240));
        pick.complete(worker, NOW.minusSeconds(120));
        pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.PENDING, null);
        check = new CartonCheck(pack.getId(), "SO-0001", PackStatus.PENDING, null, null, null, null, List.of(), 0);
        when(picks.lockById(pick.getId())).thenReturn(Optional.of(pick));
        when(picks.findById(pick.getId())).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));
        when(store.load(any(), any(), any())).thenReturn(check);
        when(store.pickedCartons(pick.getId())).thenReturn(List.of(
                new CartonCheck.PickedCarton(UUID.randomUUID(), "LPN-1", lineId, "SKU-A", null, 10),
                new CartonCheck.PickedCarton(UUID.randomUUID(), "LPN-2", lineId, "SKU-A", null, 10)));
        orderIs(order(OrderStatus.PICKING, BigDecimal.ZERO));
        when(itemControls.cartonSpec(new Sku("SKU-A"))).thenReturn(Optional.of(new CartonSpec("SKU-A", "CARTON", 10,
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("40"), new BigDecimal("12"), true)));
        when(payments.installmentStanding(any())).thenReturn(InstallmentStanding.none(orderId));
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(shipments.findByPackIdOrderByReadyAtAscIdAsc(any())).thenReturn(List.of());
        when(identities.displayNames(any())).thenReturn(java.util.Map.of());
    }

    private OrderSummary order(OrderStatus status, BigDecimal dueBeforeHandover) {
        var line = new OrderSummary.LineSummary(lineId, "SKU-A", 20, Money.vnd(10_000), Money.vnd(200_000), List.of());
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO, new BigDecimal("200000"), new BigDecimal("200000"), dueBeforeHandover, List.of(),
                List.of(), true);
        return new OrderSummary(orderId, "SO-0001", UUID.randomUUID(), status, Money.vnd(200_000), List.of(line), NOW,
                null, null, null, "Ms Lan", null, "0900000001", null, null, null, WAREHOUSE, NOW, null, null,
                lifecycle);
    }

    private void orderIs(OrderSummary order) {
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(orders.markPacked(any(), any())).thenReturn(order);
    }

    private void started() {
        service.start(pick.getId(), actor);
    }

    @Nested
    @DisplayName("the first label locks the order (08 BR-04, 17 BR-12)")
    class FirstLabel {

        @Test
        @DisplayName("labelling anything calls recordFirstLabel once; scanning alone does not")
        void onlyWhenSomethingIsLabelled() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            verify(orders, never()).recordFirstLabel(any());

            service.label(pick.getId(), null, actor);

            verify(orders).recordFirstLabel(orderId);
            assertThat(check.carton("LPN-1").orElseThrow().status()).isEqualTo(PackageStatus.LABELED);
        }

        @Test
        @DisplayName("a label that fails (no carton spec size) locks nothing")
        void refusedLabelDoesNotLock() {
            when(itemControls.cartonSpec(any())).thenReturn(Optional.empty());
            started();
            service.check(pick.getId(), "LPN-1", actor);

            assertThat(refusal(() -> service.label(pick.getId(), null, actor)).errorCode())
                    .isEqualTo(ErrorCode.CARTON_SPEC_UNMEASURED);

            verify(orders, never()).recordFirstLabel(any());
        }

        @Test
        @DisplayName("naming only labelled cartons again labels nothing, so locks nothing a second time")
        void repeatedLabelDoesNotLockAgain() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            service.label(pick.getId(), List.of("LPN-1"), actor);
            service.label(pick.getId(), List.of("LPN-1"), actor);

            verify(orders, org.mockito.Mockito.times(1)).recordFirstLabel(orderId);
        }

        @Test
        @DisplayName("the order is no longer workable (cancelled while the picker waited): PACK_STATE_CONFLICT")
        void orderNotWorkable() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            orderIs(order(OrderStatus.ON_HOLD, BigDecimal.ZERO));

            assertThat(refusal(() -> service.label(pick.getId(), null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            verify(orders, never()).recordFirstLabel(any());
        }
    }

    @Nested
    @DisplayName("the payment warning of a completion (08 §4.4 step 9, BR-11)")
    class PaymentWarning {

        private void everythingLabelled() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            service.check(pick.getId(), "LPN-2", actor);
            service.label(pick.getId(), null, actor);
        }

        @Test
        @DisplayName("an order without a contract warns with what its terms want before the goods leave")
        void nonContractOrder() {
            everythingLabelled();
            orderIs(order(OrderStatus.PICKING, new BigDecimal("150000")));

            var done = service.complete(pick.getId(), PackingCompletion.whole(), actor);

            assertThat(done.paymentDue()).isNotNull();
            assertThat(done.paymentDue().amount()).isEqualByComparingTo("150000");
            assertThat(done.paymentDue().currency()).isEqualTo("VND");
            assertThat(done.shipmentId()).isNotNull();
        }

        @Test
        @DisplayName("a contract order shows its BEFORE_DELIVERY installments still unpaid, not the order's own amount")
        void contractOrder() {
            everythingLabelled();
            orderIs(order(OrderStatus.PICKING, new BigDecimal("1")));
            when(payments.installmentStanding(orderId)).thenReturn(new InstallmentStanding(orderId, true, true,
                    new BigDecimal("70000"), BigDecimal.ZERO, new BigDecimal("200000"), BigDecimal.ZERO, null));

            var done = service.complete(pick.getId(), PackingCompletion.whole(), actor);

            assertThat(done.paymentDue().amount()).isEqualByComparingTo("70000");
        }

        @Test
        @DisplayName("nothing owed (zero) is no warning at all; and the completion never blocks on money")
        void nothingOwed() {
            everythingLabelled();
            when(payments.installmentStanding(orderId)).thenReturn(new InstallmentStanding(orderId, true, true,
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("200000"), new BigDecimal("200000"), null));

            var done = service.complete(pick.getId(), PackingCompletion.whole(), actor);

            assertThat(done.paymentDue()).isNull();
            assertThat(done.shipmentId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("completing: replay by request id, a COMPLETED check, and who may complete")
    class Completing {

        private void everythingLabelled() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            service.check(pick.getId(), "LPN-2", actor);
            service.label(pick.getId(), null, actor);
        }

        @Test
        @DisplayName("a completion makes one shipment of every labelled carton and marks the order packed")
        void makesOneShipment() {
            everythingLabelled();

            var done = service.complete(pick.getId(), PackingCompletion.whole(), actor);

            assertThat(check.status()).isEqualTo(PackStatus.COMPLETED);
            verify(orders).markPacked(orderId, worker);
            verify(shipments).saveAndFlush(any());
            assertThat(check.cartons()).extracting(CartonCheck.Carton::shipmentId).containsOnly(done.shipmentId());
        }

        @Test
        @DisplayName("a request id that already made a shipment for this task answers with it, nothing new")
        void replayByRequestId() {
            everythingLabelled();
            UUID request = UUID.randomUUID();
            ShipmentJpaEntity made = mock(ShipmentJpaEntity.class);
            UUID shipmentId = UUID.randomUUID();
            when(made.getId()).thenReturn(shipmentId);
            when(made.getPackId()).thenReturn(pack.getId());
            when(shipments.findByRequestId(request)).thenReturn(Optional.of(made));

            var done = service.complete(pick.getId(), new PackingCompletion(request, false, null), actor);

            assertThat(done.shipmentId()).isEqualTo(shipmentId);
            verify(shipments, never()).saveAndFlush(any());
            verify(orders, never()).markPacked(any(), any());
        }

        @Test
        @DisplayName("a request id of another task's completion is CONFLICT")
        void requestIdOfAnotherTask() {
            everythingLabelled();
            UUID request = UUID.randomUUID();
            ShipmentJpaEntity elsewhere = mock(ShipmentJpaEntity.class);
            when(elsewhere.getPackId()).thenReturn(UUID.randomUUID());
            when(shipments.findByRequestId(request)).thenReturn(Optional.of(elsewhere));

            assertThat(refusal(() -> service.complete(pick.getId(), new PackingCompletion(request, false, null),
                    actor)).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("a COMPLETED check answers with the last shipment it made, even for a worker who did not start it")
        void completedCheckAnswersAsItIs() {
            everythingLabelled();
            var first = service.complete(pick.getId(), PackingCompletion.whole(), actor);
            ShipmentJpaEntity made = mock(ShipmentJpaEntity.class);
            when(made.getId()).thenReturn(first.shipmentId());
            when(shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())).thenReturn(List.of(made));

            var again = service.complete(pick.getId(), PackingCompletion.whole(), colleague);

            assertThat(again.shipmentId()).isEqualTo(first.shipmentId());
            verify(shipments, org.mockito.Mockito.times(1)).saveAndFlush(any());
        }

        @Test
        @DisplayName("another worker cannot complete an unfinished session: PACKING_TAKEN; a manager can")
        void ownerOrManager() {
            everythingLabelled();

            assertThat(refusal(() -> service.complete(pick.getId(), PackingCompletion.whole(), colleague)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
            assertThat(service.complete(pick.getId(), PackingCompletion.whole(), manager).shipmentId()).isNotNull();
        }

        @Test
        @DisplayName("a partial completion records the order as packed in part and leaves the session IN_PROGRESS")
        void partialCompletion() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            service.label(pick.getId(), null, actor);
            service.remove(pick.getId(), "LPN-2", "wet", actor);

            service.complete(pick.getId(), new PackingCompletion(null, true, "one carton wet"), actor);

            verify(orders).recordPacked(orderId);
            verify(orders, never()).markPacked(any(), any());
            assertThat(check.status()).isEqualTo(PackStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("reprinting a label (08 BR-08)")
    class Reprint {

        private void labelled() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            service.label(pick.getId(), null, actor);
        }

        @Test
        @DisplayName("while IN_PROGRESS only the worker who started it, or a manager, may")
        void whileWorking() {
            labelled();

            assertThat(refusal(() -> service.reprint(pick.getId(), "LPN-1", colleague)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
            assertThat(service.reprint(pick.getId(), "LPN-1", manager).cartons().stream()
                    .filter(c -> c.lpn().equals("LPN-1")).findFirst().orElseThrow().labelVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("on a COMPLETED check any packer may: nobody works the session any more")
        void afterCompletion() {
            labelled();
            service.check(pick.getId(), "LPN-2", actor);
            service.label(pick.getId(), null, actor);
            service.complete(pick.getId(), PackingCompletion.whole(), actor);

            var session = service.reprint(pick.getId(), "LPN-1", colleague);

            assertThat(session.cartons().stream().filter(c -> c.lpn().equals("LPN-1")).findFirst().orElseThrow()
                    .labelVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("a check not started has no label; a cancelled one is PACK_STATE_CONFLICT")
        void notStartedOrCancelled() {
            assertThat(refusal(() -> service.reprint(pick.getId(), "LPN-1", actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            check.cancel(NOW, java.util.Set.of());
            assertThat(refusal(() -> service.reprint(pick.getId(), "LPN-1", actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("starting and scanning")
    class Starting {

        @Test
        @DisplayName("a task still being picked or staged is PACKING_NOT_READY")
        void notReady() {
            PickJpaEntity staged = new PickJpaEntity(pick.getId(), null, orderId, "SO-0001", WAREHOUSE,
                    PickStatus.STAGED);
            when(picks.lockById(pick.getId())).thenReturn(Optional.of(staged));

            assertThat(refusal(() -> service.start(pick.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_NOT_READY);
        }

        @Test
        @DisplayName("starting receives the picked cartons as TO_CHECK; a second worker is PACKING_TAKEN")
        void startReceivesCartons() {
            var session = service.start(pick.getId(), actor);

            assertThat(session.status()).isEqualTo("IN_PROGRESS");
            assertThat(session.cartons()).extracting(CartonChecks.Carton::status).containsOnly("TO_CHECK");
            assertThat(refusal(() -> service.start(pick.getId(), colleague)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
        }

        @Test
        @DisplayName("scanning a carton of another order's task is CARTON_NOT_IN_TASK and saves nothing new")
        void foreignCarton() {
            started();

            assertThat(refusal(() -> service.check(pick.getId(), "LPN-OF-ANOTHER-ORDER", actor)).errorCode())
                    .isEqualTo(ErrorCode.CARTON_NOT_IN_TASK);
        }

        @Test
        @DisplayName("the session lists the SKUs whose carton spec has no size, so the screen can say why labelling stops")
        void sessionNamesUnmeasuredSkus() {
            when(itemControls.cartonSpec(any())).thenReturn(Optional.of(new CartonSpec("SKU-A", "CARTON", 10, null,
                    null, null, null, true)));

            var session = service.start(pick.getId(), actor);

            assertThat(session.skusWithoutCartonSpec()).containsExactly("SKU-A");
        }
    }

    @Nested
    @DisplayName("the order was cancelled (08 BR-04): cancelForOrder")
    class Cancelling {

        private ShipmentJpaEntity shipment(UUID id, com.stockflow.fulfillment.internal.domain.ShipmentStatus status) {
            ShipmentJpaEntity row = mock(ShipmentJpaEntity.class);
            when(row.getId()).thenReturn(id);
            when(row.getStatus()).thenReturn(status);
            return row;
        }

        @Test
        @DisplayName("it reads the session's CANCELLED shipments and removes their cartons with the unshipped ones; a carton of "
                + "a shipment that did not cancel stays")
        void removesCartonsOfCancelledShipments() {
            started();
            service.check(pick.getId(), "LPN-1", actor);
            service.check(pick.getId(), "LPN-2", actor);
            service.label(pick.getId(), null, actor);
            var done = service.complete(pick.getId(), PackingCompletion.whole(), actor);
            UUID kept = UUID.randomUUID();
            // LPN-2 sits in another shipment, which did not cancel (it left on a vehicle)
            var keptCarton = check.carton("LPN-2").orElseThrow();
            check.ship(new CartonCheck.Completion(List.of(keptCarton), true, List.of()), kept, worker, NOW);
            when(shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())).thenReturn(List.of(
                    shipment(done.shipmentId(), com.stockflow.fulfillment.internal.domain.ShipmentStatus.CANCELLED),
                    shipment(kept, com.stockflow.fulfillment.internal.domain.ShipmentStatus.HANDED_OVER)));

            service.cancelForOrder(pick, pack);

            var one = check.carton("LPN-1").orElseThrow();
            assertThat(one.status()).isEqualTo(com.stockflow.fulfillment.internal.domain.PackageStatus.REMOVED);
            assertThat(one.shipmentId()).isNull();
            assertThat(keptCarton.status()).isEqualTo(com.stockflow.fulfillment.internal.domain.PackageStatus.LABELED);
            assertThat(keptCarton.shipmentId()).isEqualTo(kept);
            assertThat(check.status()).isEqualTo(PackStatus.CANCELLED);
            verify(store, org.mockito.Mockito.atLeastOnce()).save(check, pack);
        }

        @Test
        @DisplayName("with no shipment at all, every carton is removed and the session cancelled")
        void noShipments() {
            started();
            service.check(pick.getId(), "LPN-1", actor);

            service.cancelForOrder(pick, pack);

            assertThat(check.cartons()).allSatisfy(c -> assertThat(c.status())
                    .isEqualTo(com.stockflow.fulfillment.internal.domain.PackageStatus.REMOVED));
            assertThat(check.status()).isEqualTo(PackStatus.CANCELLED);
        }
    }
}
