package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferDispatchGuard;
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.domain.TransferLine;
import com.stockflow.inventory.internal.domain.TransferOrder;
import com.stockflow.inventory.internal.domain.TransferOrderRepository;
import com.stockflow.inventory.internal.domain.TransferReason;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.TransferOrderSearch;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.TODAY;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 10 (inter-warehouse transfer): the orchestration of a transfer order - creation checks at the source, the
 * approval threshold, picking, the manual and the load-planned dispatch, and the holds that keep a transfer's units
 * from being sold (BR-02, BR-03, BR-04). The stock rows are real aggregates handed out by mocked repositories.
 */
class TransferOrderServiceImplTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID PLANNER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final Sku CUP = new Sku("CUP-12OZ");
    private static final int THRESHOLD = 100;

    private final TransferOrderRepository transfers = mock(TransferOrderRepository.class);
    private final TransferOrderSearch search = mock(TransferOrderSearch.class);
    private final WarehouseDirectory warehouses = mock(WarehouseDirectory.class);
    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final StockMovementLog ledger = mock(StockMovementLog.class);
    private final InventoryControlService controls = mock(InventoryControlService.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final UomConversionRepository units = mock(UomConversionRepository.class);
    private final TransferDispatchGuard guard = mock(TransferDispatchGuard.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TransferDispatchGuard> guards = mock(ObjectProvider.class);

    private final Map<UUID, TransferOrder> stored = new HashMap<>();
    private final Map<StockItemId, StockItem> rows = new HashMap<>();
    private final Map<String, List<StockAllocator.Candidate>> candidatesBySku = new HashMap<>();
    private final AtomicInteger numbers = new AtomicInteger();

    private TransferOrderServiceImpl service = serviceAt(NOW);

    private TransferOrderServiceImpl serviceAt(Instant now) {
        return new TransferOrderServiceImpl(transfers, search, warehouses, stockItems, ledger,
                Clock.fixed(now, ZoneOffset.UTC), controls, policies, skuReservations,
                new StockPackageContents(stockPackages, stockItems), stockPackages, units, guards, THRESHOLD);
    }

    @BeforeEach
    void wire() {
        when(warehouses.prefixOf(HCM)).thenReturn(Optional.of("HCM"));
        when(warehouses.prefixOf(HN)).thenReturn(Optional.of("HN"));
        when(warehouses.isActive(any())).thenReturn(true);
        when(guards.getIfAvailable()).thenReturn(guard);
        when(controls.policy(any())).thenReturn(new InventoryPolicy(null, null, null, RemovalStrategy.FEFO,
                TrackingMode.LOT, false, null));
        when(transfers.nextNumber(any())).thenAnswer(call -> "TO-%s-%04d".formatted(call.getArgument(0),
                numbers.incrementAndGet()));
        when(transfers.save(any())).thenAnswer(call -> {
            TransferOrder order = call.getArgument(0);
            stored.put(order.id(), order);
            return order;
        });
        when(transfers.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<UUID>getArgument(0))));
        when(stockItems.findAvailabilityBySku(any())).thenAnswer(call ->
                candidatesBySku.getOrDefault(call.<Sku>getArgument(0).code(), List.of()));
        when(stockItems.findByIdForUpdate(any())).thenAnswer(call ->
                Optional.ofNullable(rows.get(call.<StockItemId>getArgument(0))));
        when(stockItems.findById(any())).thenAnswer(call ->
                Optional.ofNullable(rows.get(call.<StockItemId>getArgument(0))));
        when(stockItems.sumAvailableBySkus(anyCollection(), any())).thenAnswer(call -> {
            Map<Sku, Integer> sums = new HashMap<>();
            for (Sku sku : call.<java.util.Collection<Sku>>getArgument(0)) {
                int sum = rows.values().stream().filter(r -> r.sku().equals(sku) && r.status().isReservable())
                        .mapToInt(r -> r.available().value()).sum();
                if (sum > 0) {
                    sums.put(sku, sum);
                }
            }
            return sums;
        });
    }

    // ------------------------------------------------------------------ fixtures

    private StockItem stock(String sku, String location, String lot, LocalDate expiry, int onHand) {
        StockItem item = InventoryFixtures.available(sku, location, lot, expiry, onHand);
        rows.put(item.id(), item);
        candidatesBySku.computeIfAbsent(sku, k -> new ArrayList<>()).add(candidate(item));
        return item;
    }

    private StockItem stock(String location, int onHand) {
        return stock(CUP.code(), location, null, null, onHand);
    }

    private static StockAllocator.Candidate candidate(StockItem item) {
        return new StockAllocator.Candidate(item.id(), item.location(), item.lotNumber(), item.expiryDate(),
                item.status(), item.available(), item.receivedAt());
    }

    private static TransferOrders.NewLine line(String sku, int quantity) {
        return new TransferOrders.NewLine(sku, null, quantity);
    }

    private TransferOrders.Create create(TransferOrders.NewLine... lines) {
        return new TransferOrders.Create(HCM, HN, TransferReason.REBALANCING, LocalDate.of(2026, 11, 2),
                List.of(lines), null);
    }

    private TransferOrders.Create parcel(TransferOrders.NewLine... lines) {
        return new TransferOrders.Create(HCM, HN, TransferReason.REBALANCING, null, List.of(lines),
                TransferDeliveryMode.PARCEL);
    }

    /** A DRAFT held in the fake repository. */
    private TransferOrder draft(int requested) {
        TransferOrder order = TransferOrder.draft(UUID.randomUUID(), "TO-1", HCM, HN, TransferReason.REBALANCING,
                null, null, List.of(new TransferLine(UUID.randomUUID(), 1, CUP, null, requested, 0)), NOW);
        stored.put(order.id(), order);
        return order;
    }

    private TransferOrder approved(TransferDeliveryMode mode, int requested) {
        TransferOrder order = draft(requested);
        order.travelAs(mode);
        order.submit(PLANNER, THRESHOLD, NOW);
        assertThat(order.status()).isEqualTo(TransferStatus.APPROVED);
        return order;
    }

    private TransferOrder picking(TransferDeliveryMode mode, int requested) {
        TransferOrder order = approved(mode, requested);
        order.startPicking();
        return order;
    }

    private static Map<UUID, Integer> ships(TransferOrder order, int quantity) {
        return Map.of(order.lines().get(0).id(), quantity);
    }

    private static CurrentUser clerkOf(UUID warehouseId, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(prefix), Set.of(warehouseId));
    }

    private List<StockMovement> ledgerLines() {
        ArgumentCaptor<StockMovement> lines = ArgumentCaptor.forClass(StockMovement.class);
        verify(ledger, org.mockito.Mockito.atLeast(0)).append(lines.capture());
        return lines.getAllValues();
    }

    // ------------------------------------------------------------------ creating

    @Nested
    @DisplayName("creating a transfer (steps 1-2, BR-01)")
    class Creating {

        @Test
        @DisplayName("is a DRAFT with the requested lines numbered from 1 and nothing shipped yet")
        void draftWithNumberedLines() {
            stock("HCM-A01-1-A", 500);

            TransferOrders.Transfer created = service.create(create(line("CUP-12OZ", 40)));

            assertThat(created.status()).isEqualTo(TransferStatus.DRAFT);
            assertThat(created.lines()).singleElement().satisfies(l -> {
                assertThat(l.lineNo()).isEqualTo(1);
                assertThat(l.requested()).isEqualTo(40);
                assertThat(l.shipped()).isZero();
            });
            assertThat(created.fromWarehouseId()).isEqualTo(HCM);
            assertThat(created.toWarehouseId()).isEqualTo(HN);
        }

        @Test
        @DisplayName("travels as a full truckload unless the planner says parcel (LP2-8)")
        void defaultDeliveryMode() {
            stock("HCM-A01-1-A", 500);

            assertThat(service.create(create(line("CUP-12OZ", 1))).deliveryMode())
                    .isEqualTo(TransferDeliveryMode.FULL_TRUCKLOAD);
            assertThat(service.create(parcel(line("CUP-12OZ", 1))).deliveryMode())
                    .isEqualTo(TransferDeliveryMode.PARCEL);
        }

        @Test
        @DisplayName("BR-01: source and destination must differ")
        void sameWarehouse() {
            stock("HCM-A01-1-A", 500);

            assertThat(codeOf(() -> service.create(new TransferOrders.Create(HCM, HCM, TransferReason.REBALANCING,
                    null, List.of(line("CUP-12OZ", 1)), null)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a warehouse that does not exist is WAREHOUSE_NOT_FOUND")
        void unknownWarehouse() {
            UUID ghost = UUID.randomUUID();
            when(warehouses.prefixOf(ghost)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.create(new TransferOrders.Create(HCM, ghost, TransferReason.REBALANCING,
                    null, List.of(line("CUP-12OZ", 1)), null)))).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        }

        @Test
        @DisplayName("SCRUM-462: nothing is transferred from or to a closed warehouse")
        void inactiveWarehouse() {
            stock("HCM-A01-1-A", 500);
            when(warehouses.isActive(HN)).thenReturn(false);

            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 1))))).isEqualTo(ErrorCode.WAREHOUSE_INACTIVE);

            when(warehouses.isActive(HN)).thenReturn(true);
            when(warehouses.isActive(HCM)).thenReturn(false);
            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 1))))).isEqualTo(ErrorCode.WAREHOUSE_INACTIVE);
        }

        @Test
        @DisplayName("step 2: more than the source has available is INSUFFICIENT_STOCK")
        void notCoverable() {
            stock("HCM-A01-1-A", 30);

            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 31))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            verify(transfers, never()).save(any());
        }

        @Test
        @DisplayName("issue 207, step 2: units promised to checkout reservations are not available to transfer")
        void skuReservationsAreSubtracted() {
            stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 45L));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.create(create(line("CUP-12OZ", 10))));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("requested 10").contains("available 5");
            verify(transfers, never()).save(any());
        }

        @Test
        @DisplayName("issue 207: exactly what is left after the reservations may be transferred")
        void reservationsLeaveExactlyEnough() {
            stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 40L));

            assertThat(service.create(create(line("CUP-12OZ", 10))).status()).isEqualTo(TransferStatus.DRAFT);
        }

        @Test
        @DisplayName("issue 207: a reservation on another SKU does not reduce this one")
        void otherSkuReservationIgnored() {
            stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-16OZ", 50L));

            assertThat(service.create(create(line("CUP-12OZ", 50))).status()).isEqualTo(TransferStatus.DRAFT);
        }

        @Test
        @DisplayName("issue 207: lines of one SKU in different lots are checked together against the reservations")
        void lotLinesOfOneSkuAreSummed() {
            stock("CUP-12OZ", "HCM-A01-1-A", "L1", null, 30);
            stock("CUP-12OZ", "HCM-A02-1-A", "L2", null, 30);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 30L));

            // each line alone fits in the 30 left after the reservation; together (40) they do not.
            assertThat(codeOf(() -> service.create(create(new TransferOrders.NewLine("CUP-12OZ", "L1", 20),
                    new TransferOrders.NewLine("CUP-12OZ", "L2", 20))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("step 2: exactly what the source has is allowed")
        void exactlyCoverable() {
            stock("HCM-A01-1-A", 20);
            stock("HCM-A02-1-A", 10);

            assertThat(service.create(create(line("CUP-12OZ", 30))).status()).isEqualTo(TransferStatus.DRAFT);
        }

        @Test
        @DisplayName("step 2: stock in the destination, or in another warehouse, does not count")
        void onlyTheSourceCounts() {
            stock("HN-A01-1-A", 500);
            stock("DN-A01-1-A", 500);
            stock("HCM-A01-1-A", 5);

            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 6))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("BR-02: quarantined and blocked stock cannot be transferred")
        void onlySellableStock() {
            StockItem quarantined = new StockItem(StockItemId.newId(), CUP, new LocationId("HCM-A01-1-A"), null, null,
                    com.stockflow.inventory.internal.domain.Quantity.of(50), StockStatus.QUARANTINE, List.of(), 0L);
            candidatesBySku.put("CUP-12OZ", List.of(candidate(quarantined)));

            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 1))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("a line naming a lot is covered by that lot only")
        void lotLineIsCoveredByItsLot() {
            stock("CUP-12OZ", "HCM-A01-1-A", "L1", null, 10);
            stock("CUP-12OZ", "HCM-A02-1-A", "L2", null, 100);

            assertThat(codeOf(() -> service.create(create(new TransferOrders.NewLine("CUP-12OZ", "L1", 11)))))
                    .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(service.create(create(new TransferOrders.NewLine("CUP-12OZ", "L2", 11))).status())
                    .isEqualTo(TransferStatus.DRAFT);
        }

        @Test
        @DisplayName("expired stock is not available to transfer")
        void expiredStockDoesNotCount() {
            stock("CUP-12OZ", "HCM-A01-1-A", "L1", TODAY.minusDays(1), 100);

            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 1))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("the same SKU twice is refused; the whole quantity goes on one line")
        void duplicateLine() {
            stock("HCM-A01-1-A", 500);

            assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 1), line("CUP-12OZ", 2)))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("the number is drawn for the business day in Ho Chi Minh City, not the UTC day")
        void numberedByBusinessDay() {
            stock("HCM-A01-1-A", 500);
            // 18:30 UTC on 22 Oct is 01:30 on 23 Oct in Vietnam.
            TransferOrderServiceImpl lateNight = serviceAt(Instant.parse("2026-10-22T18:30:00Z"));

            lateNight.create(create(line("CUP-12OZ", 1)));

            verify(transfers).nextNumber(LocalDate.of(2026, 10, 23));
        }

        @Test
        @DisplayName("BR-SEC-002: a clerk of neither warehouse cannot raise it")
        void outOfScope() {
            stock("HCM-A01-1-A", 500);

            WithCurrentUser.run(clerkOf(UUID.randomUUID(), "DN"), () ->
                    assertThat(codeOf(() -> service.create(create(line("CUP-12OZ", 1)))))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("BR-SEC-002: a clerk of the destination alone may raise it")
        void destinationClerkMayRaise() {
            stock("HCM-A01-1-A", 500);

            WithCurrentUser.run(clerkOf(HN, "HN"), () ->
                    assertThat(service.create(create(line("CUP-12OZ", 1))).status()).isEqualTo(TransferStatus.DRAFT));
        }
    }

    // ------------------------------------------------------------------ revising

    @Nested
    @DisplayName("revising a draft (SCRUM-462)")
    class Revising {

        @Test
        @DisplayName("a draft is replaced whole: lines, warehouses and mode")
        void replacedWhole() {
            stock("HN-A01-1-A", 500);
            TransferOrder order = draft(10);
            TransferOrders.Revise revise = new TransferOrders.Revise(0, HN, HCM, TransferReason.DEMAND, null,
                    List.of(line("CUP-12OZ", 25)), TransferDeliveryMode.PARCEL);

            TransferOrders.Transfer revised = service.reviseDraft(order.id(), revise);

            assertThat(revised.fromWarehouseId()).isEqualTo(HN);
            assertThat(revised.lines()).singleElement().satisfies(l -> assertThat(l.requested()).isEqualTo(25));
            assertThat(revised.deliveryMode()).isEqualTo(TransferDeliveryMode.PARCEL);
            assertThat(revised.reason()).isEqualTo(TransferReason.DEMAND);
        }

        @Test
        @DisplayName("anything but a DRAFT is TRANSFER_ORDER_NOT_EDITABLE")
        void notEditable() {
            TransferOrder order = approved(TransferDeliveryMode.PARCEL, 10);

            assertThat(codeOf(() -> service.reviseDraft(order.id(), new TransferOrders.Revise(0, HCM, HN,
                    TransferReason.DEMAND, null, List.of(line("CUP-12OZ", 1)))))).isEqualTo(
                    ErrorCode.TRANSFER_ORDER_NOT_EDITABLE);
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK")
        void staleVersion() {
            TransferOrder order = draft(10);

            assertThat(codeOf(() -> service.reviseDraft(order.id(), new TransferOrders.Revise(7, HCM, HN,
                    TransferReason.DEMAND, null, List.of(line("CUP-12OZ", 1)))))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("issue 207: the new lines are held to the SKU reservations too, as at creation")
        void mustLeaveReservationsCovered() {
            stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 45L));
            TransferOrder order = draft(1);

            assertThat(codeOf(() -> service.reviseDraft(order.id(), new TransferOrders.Revise(0, HCM, HN,
                    TransferReason.DEMAND, null, List.of(line("CUP-12OZ", 6)))))).isEqualTo(
                    ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("the new lines must be coverable at the new source, as at creation")
        void mustBeCoverable() {
            stock("HCM-A01-1-A", 5);
            TransferOrder order = draft(1);

            assertThat(codeOf(() -> service.reviseDraft(order.id(), new TransferOrders.Revise(0, HCM, HN,
                    TransferReason.DEMAND, null, List.of(line("CUP-12OZ", 6)))))).isEqualTo(
                    ErrorCode.INSUFFICIENT_STOCK);
        }
    }

    // ------------------------------------------------------------------ approval

    @Nested
    @DisplayName("submitting and approving (step 3, BR-SEC-002)")
    class Approving {

        @Test
        @DisplayName("at or under the threshold the submitter approves in the same step")
        void underThresholdSelfApproves() {
            TransferOrder order = draft(THRESHOLD);

            TransferOrders.Transfer submitted = service.submit(order.id(), PLANNER);

            assertThat(submitted.status()).isEqualTo(TransferStatus.APPROVED);
            assertThat(submitted.approvedBy()).isEqualTo(PLANNER);
            assertThat(submitted.approvedAt()).isEqualTo(NOW);
            assertThat(submitted.submittedBy()).isNull();
        }

        @Test
        @DisplayName("one unit over the threshold waits for someone else")
        void overThresholdWaits() {
            TransferOrder order = draft(THRESHOLD + 1);

            TransferOrders.Transfer submitted = service.submit(order.id(), PLANNER);

            assertThat(submitted.status()).isEqualTo(TransferStatus.PENDING_APPROVAL);
            assertThat(submitted.submittedBy()).isEqualTo(PLANNER);
            assertThat(submitted.approvedBy()).isNull();
        }

        @Test
        @DisplayName("the threshold counts all lines together")
        void thresholdSumsLines() {
            TransferOrder order = TransferOrder.draft(UUID.randomUUID(), "TO-2", HCM, HN, TransferReason.DEMAND, null,
                    null, List.of(new TransferLine(UUID.randomUUID(), 1, CUP, null, 60, 0),
                            new TransferLine(UUID.randomUUID(), 2, new Sku("LID-12OZ"), null, 41, 0)), NOW);
            stored.put(order.id(), order);

            assertThat(service.submit(order.id(), PLANNER).status()).isEqualTo(TransferStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("four eyes: the submitter cannot approve their own transfer")
        void selfApproval() {
            TransferOrder order = draft(THRESHOLD + 1);
            service.submit(order.id(), PLANNER);

            assertThat(codeOf(() -> service.approve(order.id(), PLANNER))).isEqualTo(ErrorCode.TRANSFER_SELF_APPROVAL);
        }

        @Test
        @DisplayName("another approver moves it to APPROVED and is recorded with the time")
        void approvedByAnother() {
            TransferOrder order = draft(THRESHOLD + 1);
            service.submit(order.id(), PLANNER);

            TransferOrders.Transfer approved = service.approve(order.id(), APPROVER);

            assertThat(approved.status()).isEqualTo(TransferStatus.APPROVED);
            assertThat(approved.approvedBy()).isEqualTo(APPROVER);
            assertThat(approved.approvedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a transfer not awaiting approval cannot be approved")
        void approveWrongState() {
            TransferOrder order = draft(10);

            assertThat(codeOf(() -> service.approve(order.id(), APPROVER)))
                    .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        }

        @Test
        @DisplayName("a rejection must say why")
        void rejectNeedsReason() {
            TransferOrder order = draft(THRESHOLD + 1);
            service.submit(order.id(), PLANNER);

            assertThat(codeOf(() -> service.reject(order.id(), APPROVER, "  "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.reject(order.id(), APPROVER, null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a rejected transfer is back in DRAFT, with no submitter, for the planner to change")
        void rejectedBackToDraft() {
            TransferOrder order = draft(THRESHOLD + 1);
            service.submit(order.id(), PLANNER);

            TransferOrders.Transfer rejected = service.reject(order.id(), APPROVER, "Too much for Hanoi");

            assertThat(rejected.status()).isEqualTo(TransferStatus.DRAFT);
            assertThat(rejected.submittedBy()).isNull();
        }

        @Test
        @DisplayName("the submitter cannot reject their own transfer either")
        void selfRejection() {
            TransferOrder order = draft(THRESHOLD + 1);
            service.submit(order.id(), PLANNER);

            assertThat(codeOf(() -> service.reject(order.id(), PLANNER, "changed my mind")))
                    .isEqualTo(ErrorCode.TRANSFER_SELF_APPROVAL);
        }
    }

    // ------------------------------------------------------------------ reading and scope

    @Nested
    @DisplayName("reading and who may act where (BR-SEC-002)")
    class Scope {

        @Test
        @DisplayName("an unknown transfer is TRANSFER_ORDER_NOT_FOUND")
        void unknown() {
            assertThat(codeOf(() -> service.get(UUID.randomUUID()))).isEqualTo(ErrorCode.TRANSFER_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("a clerk of a third warehouse cannot read it")
        void thirdWarehouseCannotRead() {
            TransferOrder order = draft(10);

            WithCurrentUser.run(clerkOf(UUID.randomUUID(), "DN"), () ->
                    assertThat(codeOf(() -> service.get(order.id()))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("the destination's clerk reads it but does not pick or dispatch: that is the source floor's work")
        void destinationClerkCannotPick() {
            TransferOrder order = approved(TransferDeliveryMode.PARCEL, 10);

            WithCurrentUser.run(clerkOf(HN, "HN"), () -> {
                assertThat(service.get(order.id()).status()).isEqualTo(TransferStatus.APPROVED);
                assertThat(codeOf(() -> service.startPicking(order.id()))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
                assertThat(codeOf(() -> service.beginPacking(order.id()))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
            });
        }

        @Test
        @DisplayName("the list passes the filters through, newest first, and maps each row")
        void list() {
            UUID id = UUID.randomUUID();
            when(search.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                    new TransferOrderSearch.Row(id, "TO-9", HCM, HN, TransferStatus.PICKING, null, NOW, null,
                            TransferDeliveryMode.PARCEL, null))));

            PageResponse<TransferOrders.Row> page = service.list(List.of(TransferStatus.PICKING), HCM, HN, "TO-9", 0, 20);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            ArgumentCaptor<TransferOrderSearch.Criteria> criteria =
                    ArgumentCaptor.forClass(TransferOrderSearch.Criteria.class);
            verify(search).search(criteria.capture(), pageable.capture());
            assertThat(criteria.getValue()).isEqualTo(new TransferOrderSearch.Criteria(
                    List.of(TransferStatus.PICKING), HCM, HN, "TO-9"));
            assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection().isDescending()).isTrue();
            assertThat(page.items()).singleElement().satisfies(r -> {
                assertThat(r.transferId()).isEqualTo(id);
                assertThat(r.deliveryMode()).isEqualTo(TransferDeliveryMode.PARCEL);
            });
        }
    }

    // ------------------------------------------------------------------ picking and manual dispatch

    @Nested
    @DisplayName("picking and manual dispatch (steps 4-5, BR-03, BR-04)")
    class ManualDispatch {

        @Test
        @DisplayName("only an APPROVED transfer starts picking")
        void startPicking() {
            TransferOrder order = approved(TransferDeliveryMode.PARCEL, 10);

            assertThat(service.startPicking(order.id()).status()).isEqualTo(TransferStatus.PICKING);
            assertThat(codeOf(() -> service.startPicking(order.id()))).isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        }

        @Test
        @DisplayName("LP2-8: a full truckload is never dispatched by hand, even before its packing started")
        void fullTruckloadIsNotDispatchedByHand() {
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 10);

            assertThat(codeOf(() -> service.dispatch(order.id(), PLANNER, ships(order, 10))))
                    .isEqualTo(ErrorCode.TRANSFER_DISPATCH_BY_LOAD_PLAN);
            verify(ledger, never()).append(any());
        }

        @Test
        @DisplayName("a dispatch takes the units from the source's stock, FEFO: the earlier expiry first, with one ledger line each")
        void takesFefo() {
            StockItem later = stock("CUP-12OZ", "HCM-A01-1-A", "L2", TODAY.plusDays(90), 30);
            StockItem sooner = stock("CUP-12OZ", "HCM-A02-1-A", "L1", TODAY.plusDays(10), 30);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 40);

            TransferOrders.Transfer sent = service.dispatch(order.id(), PLANNER, ships(order, 40));

            assertThat(sent.status()).isEqualTo(TransferStatus.IN_TRANSIT);
            assertThat(sent.dispatchedBy()).isEqualTo(PLANNER);
            assertThat(sooner.onHand().value()).isZero();
            assertThat(later.onHand().value()).isEqualTo(20);
            List<StockMovement> lines = ledgerLines();
            assertThat(lines).extracting(StockMovement::quantity).containsExactly(30, 10);
            assertThat(lines).allSatisfy(l -> {
                assertThat(l.type()).isEqualTo(StockMovement.MovementType.TRANSFER_OUT);
                assertThat(l.referenceType()).isEqualTo(StockMovement.ReferenceType.TRANSFER_ORDER_LINE);
                assertThat(l.referenceId()).isEqualTo(order.lines().get(0).id());
                assertThat(l.to()).isNull();
                assertThat(l.actorId()).isEqualTo(PLANNER);
            });
        }

        @Test
        @DisplayName("a partial shipment takes only what shipped; the rest stays on the shelf")
        void partialShipment() {
            StockItem bin = stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 60);

            TransferOrders.Transfer sent = service.dispatch(order.id(), PLANNER, ships(order, 45));

            assertThat(bin.onHand().value()).isEqualTo(55);
            assertThat(sent.lines().get(0).shipped()).isEqualTo(45);
            assertThat(sent.lines().get(0).requested()).isEqualTo(60);
            verify(stockItems).save(bin);
        }

        @Test
        @DisplayName("an empty truck is not a dispatch")
        void atLeastOneUnit() {
            stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);

            assertThat(codeOf(() -> service.dispatch(order.id(), PLANNER, ships(order, 0))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a line may not ship more than was requested")
        void cannotOverShip() {
            stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);

            assertThat(codeOf(() -> service.dispatch(order.id(), PLANNER, ships(order, 11))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a transfer that is not PICKING cannot be dispatched")
        void notPicking() {
            stock("HCM-A01-1-A", 100);
            TransferOrder order = approved(TransferDeliveryMode.PARCEL, 10);

            assertThat(codeOf(() -> service.dispatch(order.id(), PLANNER, ships(order, 10))))
                    .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        }

        @Test
        @DisplayName("SCRUM-152: units promised to checkout reservations are not shipped out from under them")
        void skuReservationsAreProtected() {
            stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 45L));
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.dispatch(order.id(), PLANNER, ships(order, 10)));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("requested 10").contains("available 5");
            verify(ledger, never()).append(any());
        }

        @Test
        @DisplayName("SCRUM-152: exactly what is left after the reservations may ship")
        void reservationsLeaveExactlyEnough() {
            StockItem bin = stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 40L));
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);

            service.dispatch(order.id(), PLANNER, ships(order, 10));

            assertThat(bin.onHand().value()).isEqualTo(40);
        }

        @Test
        @DisplayName("the SKUs' reservation locks are taken before any row is planned or locked")
        void locksSkusFirst() {
            stock("HCM-A01-1-A", 50);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);

            service.dispatch(order.id(), PLANNER, ships(order, 10));

            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(policies, stockItems);
            inOrder.verify(policies).lockReservationStock(Set.of("CUP-12OZ"));
            inOrder.verify(stockItems).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("stock dispatched by hand comes only from the source warehouse")
        void takesFromSourceOnly() {
            StockItem source = stock("HCM-A01-1-A", 20);
            StockItem destination = stock("HN-A01-1-A", 500);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 20);

            service.dispatch(order.id(), PLANNER, ships(order, 20));

            assertThat(source.onHand().value()).isZero();
            assertThat(destination.onHand().value()).isEqualTo(500);
        }

        @Test
        @DisplayName("BR-04: picking is cancelled back to APPROVED, asking fulfillment first")
        void cancelPicking() {
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 10);

            TransferOrders.Transfer back = service.cancelPicking(order.id());

            assertThat(back.status()).isEqualTo(TransferStatus.APPROVED);
            verify(guard).beforePickingCancelled(order.id(), NOW);
        }

        @Test
        @DisplayName("without fulfillment loaded, cancelling picking just works")
        void cancelPickingWithoutGuard() {
            when(guards.getIfAvailable()).thenReturn(null);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);

            assertThat(service.cancelPicking(order.id()).status()).isEqualTo(TransferStatus.APPROVED);
        }

        @Test
        @DisplayName("BR-04: once goods left on a vehicle, picking is no longer cancelled and nothing is released")
        void cancelPickingRefusedAfterGoodsLeft() {
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 10);
            order.recordGoodsLeft(NOW);

            assertThat(codeOf(() -> service.cancelPicking(order.id()))).isEqualTo(ErrorCode.TRANSFER_GOODS_LEFT);
            verify(guard, never()).beforePickingCancelled(any(), any());
        }

        @Test
        @DisplayName("BR-03: cancelling picking lets go of the units held for the transfer, on every row")
        void cancelPickingReleasesHolds() {
            StockItem bin = stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);
            service.holdForPacking(order.id(), ships(order, 40), Map.of(), NOW);
            assertThat(bin.reserved().value()).isEqualTo(40);
            when(stockItems.findWithReservationsForTransferOrder(order.id())).thenReturn(List.of(bin));

            service.cancelPicking(order.id());

            assertThat(bin.reserved().value()).isZero();
            assertThat(bin.available().value()).isEqualTo(100);
        }

        @Test
        @DisplayName("BR-04: a draft or approved transfer is cancelled with a reason; the reason is kept")
        void cancel() {
            TransferOrder order = approved(TransferDeliveryMode.PARCEL, 10);

            TransferOrders.Transfer cancelled = service.cancel(order.id(), "  Customer withdrew  ");

            assertThat(cancelled.status()).isEqualTo(TransferStatus.CANCELLED);
            assertThat(cancelled.cancelReason()).isEqualTo("Customer withdrew");
        }

        @Test
        @DisplayName("a cancellation must say why")
        void cancelNeedsReason() {
            TransferOrder order = draft(10);

            assertThat(codeOf(() -> service.cancel(order.id(), " "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("BR-04: a transfer already in transit cannot be cancelled")
        void cancelAfterDispatch() {
            stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.PARCEL, 10);
            service.dispatch(order.id(), PLANNER, ships(order, 10));

            assertThat(codeOf(() -> service.cancel(order.id(), "oops"))).isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        }
    }

    // ------------------------------------------------------------------ fulfillment: packing, holds, load-planned dispatch

    @Nested
    @DisplayName("packing for a load plan (SCRUM-287 PR T, step 5)")
    class Packing {

        @Test
        @DisplayName("an APPROVED full truckload starts picking when its packing begins")
        void beginPackingStartsPicking() {
            TransferOrder order = approved(TransferDeliveryMode.FULL_TRUCKLOAD, 10);

            TransferFulfillment.TransferView view = service.beginPacking(order.id());

            assertThat(view.picking()).isTrue();
            verify(transfers).save(order);
        }

        @Test
        @DisplayName("beginning again is a no-op: nothing is saved twice")
        void beginPackingIsIdempotent() {
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 10);

            service.beginPacking(order.id());

            verify(transfers, never()).save(any());
        }

        @Test
        @DisplayName("a parcel is dispatched by hand, never packed for a load plan")
        void parcelIsNotPacked() {
            TransferOrder order = approved(TransferDeliveryMode.PARCEL, 10);

            assertThat(codeOf(() -> service.beginPacking(order.id()))).isEqualTo(ErrorCode.TRANSFER_NOT_FULL_TRUCKLOAD);
        }

        @Test
        @DisplayName("10 step 4: packed units are held at the source, so no order can reserve them")
        void holdsPackedUnits() {
            StockItem bin = stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            service.holdForPacking(order.id(), ships(order, 30), Map.of(), NOW);

            assertThat(bin.reserved().value()).isEqualTo(30);
            assertThat(bin.available().value()).isEqualTo(70);
            assertThat(bin.onHand().value()).isEqualTo(100);
            assertThat(bin.reservations()).singleElement().satisfies(hold -> {
                assertThat(hold.transferOrderId()).isEqualTo(order.id());
                assertThat(hold.rootRequestId()).isEqualTo(order.lines().get(0).id());
                assertThat(hold.quantity().value()).isEqualTo(30);
            });
            verify(stockItems).save(bin);
        }

        @Test
        @DisplayName("holding again replaces the holds, it does not stack them")
        void rePackingReplacesHolds() {
            StockItem bin = stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);
            service.holdForPacking(order.id(), ships(order, 30), Map.of(), NOW);
            when(stockItems.findWithReservationsForTransferOrder(order.id())).thenReturn(List.of(bin));

            service.holdForPacking(order.id(), ships(order, 35), Map.of(), NOW);

            assertThat(bin.reserved().value()).isEqualTo(35);
            assertThat(bin.reservations()).filteredOn(r -> r.status().isActive()).hasSize(1);
        }

        @Test
        @DisplayName("holds are only taken while the transfer is PICKING")
        void holdsOnlyWhilePicking() {
            stock("HCM-A01-1-A", 100);
            TransferOrder order = approved(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            assertThat(codeOf(() -> service.holdForPacking(order.id(), ships(order, 30), Map.of(), NOW)))
                    .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        }

        @Test
        @DisplayName("a shortage names the SKU and by how much the source is short, in a field error")
        void shortageIsNamed() {
            stock("HCM-A01-1-A", 25);
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.holdForPacking(order.id(), ships(order, 40), Map.of(), NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("CUP-12OZ").contains("needs 40").contains("only 25")
                    .contains("short by 15");
            assertThat(refused.fieldErrors()).extracting(f -> f.field() + "=" + f.message())
                    .contains("sku=CUP-12OZ", "shortfall=15");
            assertThat(refused.getCause()).isInstanceOf(com.stockflow.inventory.internal.domain.InsufficientStockException.class);
        }

        @Test
        @DisplayName("#147: units promised to checkout reservations are never held for a transfer")
        void holdsRespectSkuReservations() {
            StockItem bin = stock("HCM-A01-1-A", 50);
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 30L));
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.holdForPacking(order.id(), ships(order, 40), Map.of(), NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("only 20");
            assertThat(bin.reserved().value()).isZero();
        }

        @Test
        @DisplayName("an unopened carton shipped as it is holds its own rows whole, and the loose rest comes from the policy")
        void cartonHeldWhole() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockItem inCarton = stockInCarton("HCM-A01-1-A", 24, cartonId);
            StockItem loose = stock("HCM-A02-1-A", 100);
            StockPackage carton = carton(cartonId, "HCM-A01-1-A", true, StockPackageStatus.IN_STOCK);
            when(stockPackages.findById(cartonId)).thenReturn(Optional.of(carton));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of(inCarton.id()));
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            service.holdForPacking(order.id(), ships(order, 40), Map.of(order.lines().get(0).id(),
                    List.of(cartonId.value())), NOW);

            assertThat(inCarton.reserved().value()).isEqualTo(24);
            assertThat(loose.reserved().value()).isEqualTo(16);
        }

        @Test
        @DisplayName("a carton that was opened since is STOCK_CARTON_NOT_SHIPPABLE, naming its LPN")
        void openedCartonRefused() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockItem inCarton = stockInCarton("HCM-A01-1-A", 24, cartonId);
            when(stockPackages.findById(cartonId)).thenReturn(Optional.of(
                    carton(cartonId, "HCM-A01-1-A", false, StockPackageStatus.IN_STOCK)));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of(inCarton.id()));
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 24);

            BusinessException refused = catchThrowableOfType(BusinessException.class, () ->
                    service.holdForPacking(order.id(), ships(order, 24), Map.of(order.lines().get(0).id(),
                            List.of(cartonId.value())), NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
            assertThat(refused.getMessage()).contains("LPN-" + cartonId.value().toString().substring(0, 8).toUpperCase());
        }

        @Test
        @DisplayName("a carton holding more than the line still needs is STOCK_CARTON_NOT_SHIPPABLE, never a split carton")
        void cartonBiggerThanLineRefused() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockItem inCarton = stockInCarton("HCM-A01-1-A", 24, cartonId);
            when(stockPackages.findById(cartonId)).thenReturn(Optional.of(
                    carton(cartonId, "HCM-A01-1-A", true, StockPackageStatus.IN_STOCK)));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of(inCarton.id()));
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 24);

            assertThat(codeOf(() -> service.holdForPacking(order.id(), ships(order, 20), Map.of(
                    order.lines().get(0).id(), List.of(cartonId.value())), NOW)))
                    .isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
        }

        @Test
        @DisplayName("a carton of another SKU's rows, or already reserved, is not shippable as asked")
        void foreignOrReservedCartonRefused() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockItem other = stockInCarton("LID-12OZ", "HCM-A01-1-A", 24, cartonId);
            when(stockPackages.findById(cartonId)).thenReturn(Optional.of(
                    carton(cartonId, "HCM-A01-1-A", true, StockPackageStatus.IN_STOCK)));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of(other.id()));
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 24);

            assertThat(codeOf(() -> service.holdForPacking(order.id(), ships(order, 24), Map.of(
                    order.lines().get(0).id(), List.of(cartonId.value())), NOW)))
                    .isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
        }

        @Test
        @DisplayName("a carton id that does not exist is STOCK_CARTON_NOT_SHIPPABLE, shown as '?'")
        void missingCartonRefused() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of());
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 24);

            BusinessException refused = catchThrowableOfType(BusinessException.class, () ->
                    service.holdForPacking(order.id(), ships(order, 24), Map.of(order.lines().get(0).id(),
                            List.of(cartonId.value())), NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
            assertThat(refused.getMessage()).startsWith("Carton ? ");
        }

        @Test
        @DisplayName("packing completed: marked packed, what was packed stays held")
        void markPacked() {
            StockItem bin = stock("HCM-A01-1-A", 100);
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);
            Instant packedAt = NOW.plusSeconds(600);

            TransferFulfillment.TransferView view = service.markPacked(order.id(), ships(order, 40), Map.of(),
                    packedAt);

            assertThat(view.packedAt()).isEqualTo(packedAt);
            assertThat(bin.reserved().value()).isEqualTo(40);
        }

        @Test
        @DisplayName("only a PICKING transfer is marked packed")
        void markPackedWrongState() {
            TransferOrder order = approved(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            assertThat(codeOf(() -> service.markPacked(order.id(), ships(order, 40), Map.of(), NOW)))
                    .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        }

        @Test
        @DisplayName("the first goods to leave on a vehicle are recorded; later ones keep that time")
        void recordGoodsLeft() {
            TransferOrder order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);

            TransferFulfillment.TransferView first = service.recordGoodsLeft(order.id(), NOW);
            TransferFulfillment.TransferView second = service.recordGoodsLeft(order.id(), NOW.plusSeconds(60));

            assertThat(first.goodsLeftAt()).isEqualTo(NOW);
            assertThat(second.goodsLeftAt()).isEqualTo(NOW);
            verify(transfers, times(1)).save(order);
        }
    }

    @Nested
    @DisplayName("dispatch by the load plan's last vehicle (step 5)")
    class LoadedDispatch {

        private StockItem bin;
        private TransferOrder order;

        @BeforeEach
        void packed() {
            bin = stock("HCM-A01-1-A", 100);
            order = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 40);
            service.markPacked(order.id(), ships(order, 40), Map.of(), NOW);
            when(stockItems.findWithReservationsForTransferOrder(order.id())).thenReturn(List.of(bin));
        }

        @Test
        @DisplayName("the transfer leaves with exactly what was held: the stock leaves its row, one TRANSFER_OUT line per hold")
        void consumesTheHolds() {
            TransferFulfillment.TransferView view = service.dispatchLoaded(order.id(), PLANNER, ships(order, 40));

            assertThat(view.status()).isEqualTo("IN_TRANSIT");
            assertThat(bin.onHand().value()).isEqualTo(60);
            assertThat(bin.reserved().value()).isZero();
            assertThat(ledgerLines()).singleElement().satisfies(line -> {
                assertThat(line.type()).isEqualTo(StockMovement.MovementType.TRANSFER_OUT);
                assertThat(line.quantity()).isEqualTo(40);
                assertThat(line.referenceId()).isEqualTo(order.lines().get(0).id());
                assertThat(line.actorId()).isEqualTo(PLANNER);
            });
            verify(stockItems, org.mockito.Mockito.atLeastOnce()).save(bin);
        }

        @Test
        @DisplayName("what is held must be what ships: otherwise TRANSFER_NOT_PACKED, and nothing leaves")
        void heldMustEqualShipped() {
            assertThat(codeOf(() -> service.dispatchLoaded(order.id(), PLANNER, ships(order, 35))))
                    .isEqualTo(ErrorCode.TRANSFER_NOT_PACKED);
            assertThat(bin.onHand().value()).isEqualTo(100);
            verify(ledger, never()).append(any());
        }

        @Test
        @DisplayName("a transfer still picking with no packing finished is TRANSFER_NOT_PACKED")
        void notPackedYet() {
            TransferOrder unpacked = picking(TransferDeliveryMode.FULL_TRUCKLOAD, 10);

            assertThat(codeOf(() -> service.dispatchLoaded(unpacked.id(), PLANNER, ships(unpacked, 10))))
                    .isEqualTo(ErrorCode.TRANSFER_NOT_PACKED);
        }
    }

    // ------------------------------------------------------------------ carton lookup

    @Nested
    @DisplayName("looking up a carton by its label")
    class CartonLookup {

        @Test
        @DisplayName("a blank label finds nothing")
        void blankLabel() {
            assertThat(service.carton(null, HCM)).isEmpty();
            assertThat(service.carton("   ", HCM)).isEmpty();
            verify(stockPackages, never()).findByLpn(any());
        }

        @Test
        @DisplayName("an unknown label finds nothing")
        void unknownLabel() {
            when(stockPackages.findByLpn("NOPE-0001")).thenReturn(Optional.empty());

            assertThat(service.carton("NOPE-0001", HCM)).isEmpty();
        }

        @Test
        @DisplayName("the label is trimmed; quantity sums its rows; sellable when every row is sellable and unreserved")
        void describesCarton() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(cartonId, "HCM-A01-1-A", true, StockPackageStatus.IN_STOCK);
            StockItem a = stockInCarton("CUP-12OZ", "HCM-A01-1-A", "L7", null, 10, cartonId);
            StockItem b = stockInCarton("CUP-12OZ", "HCM-A01-1-A", "L7", null, 14, cartonId);
            when(stockPackages.findByLpn(carton.lpn())).thenReturn(Optional.of(carton));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of(a.id(), b.id()));
            when(policies.findSku(carton.inventoryItemId())).thenReturn(Optional.of("CUP-12OZ"));
            when(units.find("CUP-12OZ", carton.uomConversionId())).thenReturn(Optional.of(
                    new com.stockflow.inventory.api.UomConversion(carton.uomConversionId(), "CUP-12OZ", "CARTON", 24,
                            false, null, null, null, null, false, true, 0)));

            TransferFulfillment.Carton found = service.carton("  " + carton.lpn() + " ", HCM).orElseThrow();

            assertThat(found.quantity()).isEqualTo(24);
            assertThat(found.lotNumber()).isEqualTo("L7");
            assertThat(found.sku()).isEqualTo("CUP-12OZ");
            assertThat(found.unitOfMeasure()).isEqualTo("CARTON");
            assertThat(found.atWarehouse()).isTrue();
            assertThat(found.unopened()).isTrue();
            assertThat(found.inStock()).isTrue();
            assertThat(found.sellable()).isTrue();
        }

        @Test
        @DisplayName("a carton at another warehouse, or with a held row, is not at the warehouse / not sellable")
        void otherWarehouseOrHeld() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(cartonId, "HN-A01-1-A", true, StockPackageStatus.IN_STOCK);
            StockItem row = stockInCarton("CUP-12OZ", "HN-A01-1-A", null, null, 10, cartonId);
            row.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    com.stockflow.inventory.internal.domain.Quantity.of(1), NOW);
            when(stockPackages.findByLpn(carton.lpn())).thenReturn(Optional.of(carton));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of(row.id()));
            when(policies.findSku(any())).thenReturn(Optional.empty());

            TransferFulfillment.Carton found = service.carton(carton.lpn(), HCM).orElseThrow();

            assertThat(found.atWarehouse()).isFalse();
            assertThat(found.sellable()).isFalse();
            assertThat(found.sku()).isNull();
            assertThat(found.unitOfMeasure()).isNull();
        }

        @Test
        @DisplayName("an empty carton is not sellable")
        void emptyCartonNotSellable() {
            StockPackageId cartonId = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(cartonId, "HCM-A01-1-A", false, StockPackageStatus.CONSUMED);
            when(stockPackages.findByLpn(carton.lpn())).thenReturn(Optional.of(carton));
            when(stockItems.findIdsInStockPackage(cartonId)).thenReturn(List.of());
            when(policies.findSku(any())).thenReturn(Optional.empty());

            TransferFulfillment.Carton found = service.carton(carton.lpn(), null).orElseThrow();

            assertThat(found.sellable()).isFalse();
            assertThat(found.inStock()).isFalse();
            assertThat(found.atWarehouse()).isFalse();
            assertThat(found.quantity()).isZero();
        }
    }

    // ------------------------------------------------------------------ fulfillment reads

    @Test
    @DisplayName("fulfillment reads: find, findAll and awaitingDispatch present a null delivery mode as a full truckload")
    void fulfillmentReads() {
        TransferOrder order = approved(TransferDeliveryMode.FULL_TRUCKLOAD, 10);
        when(transfers.findAllById(any())).thenReturn(List.of(order));
        when(transfers.findAwaitingDispatch(HCM)).thenReturn(List.of(order));

        assertThat(service.find(order.id())).get().satisfies(v -> assertThat(v.status()).isEqualTo("APPROVED"));
        assertThat(service.find(UUID.randomUUID())).isEmpty();
        assertThat(service.findAll(List.of(order.id()))).hasSize(1);
        assertThat(service.awaitingDispatch(HCM)).extracting(TransferFulfillment.TransferView::transferId)
                .containsExactly(order.id());
    }

    // ------------------------------------------------------------------ helpers for cartons

    private StockItem stockInCarton(String location, int onHand, StockPackageId cartonId) {
        return stockInCarton(CUP.code(), location, null, null, onHand, cartonId);
    }

    private StockItem stockInCarton(String sku, String location, int onHand, StockPackageId cartonId) {
        return stockInCarton(sku, location, null, null, onHand, cartonId);
    }

    private StockItem stockInCarton(String sku, String location, String lot, LocalDate expiry, int onHand,
                                    StockPackageId cartonId) {
        StockItem item = new StockItem(StockItemId.newId(), new Sku(sku), new LocationId(location), lot, expiry,
                com.stockflow.inventory.internal.domain.Quantity.of(onHand), StockStatus.AVAILABLE, List.of(), 0L,
                NOW, null, null, cartonId);
        rows.put(item.id(), item);
        candidatesBySku.computeIfAbsent(sku, k -> new ArrayList<>()).add(candidate(item));
        return item;
    }

    private static StockPackage carton(StockPackageId id, String location, boolean unopened,
                                       StockPackageStatus status) {
        String lpn = "LPN-" + id.value().toString().substring(0, 8).toUpperCase();
        Instant closedAt = status.isClosed() ? NOW : null;
        return new StockPackage(id, lpn, UUID.randomUUID(), UUID.randomUUID(), 24, 24, new LocationId(location),
                status, unopened, UUID.randomUUID(), closedAt, 0L);
    }
}
