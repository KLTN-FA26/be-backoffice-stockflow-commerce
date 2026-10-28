package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.entity.LoadEstimateJpaEntity;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.LoadEstimateJpaRepository;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.inventory.api.RuleLevel;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Load estimates (kltn-docs 09 §4.2 and the design's LP2 rules): an estimate is read, planned outside any transaction
 * and stored under the order's lock, refused when the order changed meanwhile; a line without a size or weight blocks
 * only itself, and all lines blocked is a 422 naming the items to complete.
 */
class LoadEstimateServiceTest {

    private final LoadEstimateJpaRepository estimates = mock(LoadEstimateJpaRepository.class);
    private final VehicleTypeJpaRepository vehicleTypes = mock(VehicleTypeJpaRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final PackagingSpecResolver packaging = mock(PackagingSpecResolver.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransferFulfillment transfers = mock(TransferFulfillment.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LoadEstimateService service = new LoadEstimateService(estimates, vehicleTypes, orders, warehouses,
            packaging, json, transactions, Duration.ofSeconds(2), transfers);

    private final UUID orderId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private final UUID truckId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private VehicleTypeJpaEntity truck;
    private final List<LoadEstimateJpaEntity> stored = new ArrayList<>();

    @BeforeEach
    void anOrderToEstimate() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        truck = new VehicleTypeJpaEntity(truckId, "TRUCK-5T");
        truck.apply("Truck 5t", "TRUCK", 6000, 2400, 2400, 0, null, null, new BigDecimal("5000"), 3, null, "ACTIVE");
        when(vehicleTypes.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(truck));
        when(vehicleTypes.findById(truckId)).thenReturn(Optional.of(truck));
        when(orders.findById(orderId)).thenReturn(Optional.of(order(OrderStatus.READY_TO_FULFILL, WAREHOUSE, 10, 0, 0)));
        when(orders.lockedStatus(orderId)).thenReturn(Optional.of(OrderStatus.READY_TO_FULFILL));
        when(packaging.resolveAll(any())).thenAnswer(call -> {
            List<PackagingRequest> asked = call.getArgument(0);
            return asked.stream().map(r -> resolved(r.sku().code(), r.quantity(), 600, 400, 300, "2.5")).toList();
        });
        when(estimates.saveAndFlush(any())).thenAnswer(call -> {
            LoadEstimateJpaEntity row = call.getArgument(0);
            stored.add(row);
            when(estimates.findById(row.getId())).thenReturn(Optional.of(row));
            return row;
        });
        when(estimates.findAllBySourceTypeAndSourceIdAndStatus(any(), any(), any())).thenReturn(List.of());
    }

    private OrderSummary order(OrderStatus status, UUID warehouse, int quantity, int shipped, int closed) {
        var line = new OrderSummary.LineSummary(lineId, "CUP-12OZ", quantity, Money.vnd(1000), Money.vnd(1000L * quantity),
                List.of());
        var progress = new OrderSummary.LineProgress(lineId, shipped, 0, closed);
        var lifecycle = new OrderSummary.Lifecycle(null, null, null, null, null, null, null, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(progress), List.of());
        return new OrderSummary(orderId, "SO-0001", UUID.randomUUID(), status, Money.vnd(1000L * quantity), List.of(line),
                NOW, null, null, null, null, null, null, null, null, null, warehouse, warehouse == null ? null : NOW, null,
                null, lifecycle);
    }

    private PackagingSpec resolved(String sku, int quantity, int length, int width, int height, String kg) {
        return new PackagingSpec(sku, itemId, quantity, PackagingSpec.Status.RESOLVED, RuleLevel.ITEM_UNIT, "EA", 1,
                quantity, null, new PackagingSpec.CartonSpec(length, width, height, new BigDecimal(kg), true), 1,
                List.of(), null);
    }

    private PackagingSpec unresolved(String sku, int quantity, List<String> fields) {
        return new PackagingSpec(sku, itemId, quantity, PackagingSpec.Status.MISSING, null, null, 0, 0, null, null, 0,
                List.of(), new PackagingSpec.SpecMissing("PACKAGING_MISSING", fields, "missing"));
    }

    private LoadEstimates.CreateEstimate create() {
        return new LoadEstimates.CreateEstimate(List.of(new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, orderId)),
                null, List.of(truckId), null);
    }

    @Test
    @DisplayName("a solver budget that is not positive is refused at start-up")
    void budgetMustBePositive() {
        assertThatThrownBy(() -> new LoadEstimateService(estimates, vehicleTypes, orders, warehouses, packaging, json,
                transactions, Duration.ZERO, transfers)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoadEstimateService(estimates, vehicleTypes, orders, warehouses, packaging, json,
                transactions, null, transfers)).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("one source at a time")
    class Sources {

        @Test
        @DisplayName("no source, two sources, or a source without its type or id is VALIDATION_FAILED on 'sources'")
        void exactlyOne() {
            var one = new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, orderId);
            List<List<LoadEstimates.Source>> bads = new ArrayList<>();
            bads.add(null);
            bads.add(List.of());
            bads.add(List.of(one, one));
            bads.add(java.util.Collections.singletonList(null));
            bads.add(List.of(new LoadEstimates.Source(null, orderId)));
            bads.add(List.of(new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, null)));
            for (List<LoadEstimates.Source> bad : bads) {
                var thrown = refusal(() -> service.create(new LoadEstimates.CreateEstimate(bad, null, null, null)));
                assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("sources");
            }
        }
    }

    @Nested
    @DisplayName("what an order may be estimated for")
    class Eligibility {

        @Test
        @DisplayName("an order that does not exist is not found")
        void unknownOrder() {
            when(orders.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(create())).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a draft, held or already shipped order is LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE")
        void statusMustAllowAnEstimate() {
            for (OrderStatus bad : List.of(OrderStatus.DRAFT, OrderStatus.PENDING_PAYMENT, OrderStatus.ON_HOLD,
                    OrderStatus.SHIPPED, OrderStatus.CANCELLED)) {
                when(orders.findById(orderId)).thenReturn(Optional.of(order(bad, WAREHOUSE, 10, 0, 0)));

                assertThat(refusal(() -> service.create(create())).errorCode()).as("%s", bad)
                        .isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
            }
        }

        @Test
        @DisplayName("an order whose units are all shipped or closed has nothing left to ship")
        void nothingLeft() {
            when(orders.findById(orderId)).thenReturn(Optional.of(order(OrderStatus.PARTIALLY_FULFILLED, WAREHOUSE, 10, 6, 4)));

            var thrown = refusal(() -> service.create(create()));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
            assertThat(thrown.getMessage()).contains("nothing left to ship");
        }

        @Test
        @DisplayName("only what is left is estimated: ordered less shipped less closed")
        void remainingUnits() {
            when(orders.findById(orderId)).thenReturn(Optional.of(order(OrderStatus.PARTIALLY_FULFILLED, WAREHOUSE, 10, 3, 2)));
            when(orders.lockedStatus(orderId)).thenReturn(Optional.of(OrderStatus.PARTIALLY_FULFILLED));

            var view = service.create(create());

            assertThat(view.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(5));
        }

        @Test
        @DisplayName("a warehouse the caller is not assigned to is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.create(create())).errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a released order is estimated at its own warehouse; naming another is VALIDATION_FAILED")
        void releasedOrderHasItsWarehouse() {
            var thrown = refusal(() -> service.create(new LoadEstimates.CreateEstimate(
                    List.of(new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, orderId)), OTHER_WAREHOUSE,
                    List.of(truckId), null)));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("warehouseId");
        }

        @Test
        @DisplayName("a confirmed order not released yet needs the warehouse it will ship from, an existing and active one")
        void confirmedOrderNeedsAWarehouse() {
            when(orders.findById(orderId)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED, null, 10, 0, 0)));
            when(orders.lockedStatus(orderId)).thenReturn(Optional.of(OrderStatus.CONFIRMED));
            var source = List.of(new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, orderId));

            assertThat(refusal(() -> service.create(new LoadEstimates.CreateEstimate(source, null, List.of(truckId), null)))
                    .fieldErrors()).extracting(f -> f.field()).containsExactly("warehouseId");

            UUID unknown = UUID.randomUUID();
            when(warehouses.findWarehouse(unknown)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.create(new LoadEstimates.CreateEstimate(source, unknown, List.of(truckId),
                    null))).isInstanceOf(NotFoundException.class);

            when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                    new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", false)));
            assertThat(refusal(() -> service.create(new LoadEstimates.CreateEstimate(source, WAREHOUSE, List.of(truckId),
                    null))).fieldErrors()).extracting(f -> f.code()).containsExactly("WarehouseInactive");

            when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                    new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
            assertThat(service.create(new LoadEstimates.CreateEstimate(source, WAREHOUSE, List.of(truckId), null))
                    .warehouseId()).isEqualTo(WAREHOUSE);
        }
    }

    @Nested
    @DisplayName("the vehicle types the plan may use")
    class VehicleTypes {

        @Test
        @DisplayName("none given means every active type; none active is VALIDATION_FAILED")
        void defaultsToTheActiveTypes() {
            var source = List.of(new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, orderId));
            assertThat(service.create(new LoadEstimates.CreateEstimate(source, null, null, null)).vehicleTypeIds())
                    .containsExactly(truckId);

            truck.apply("Truck 5t", "TRUCK", 6000, 2400, 2400, 0, null, null, new BigDecimal("5000"), 3, null, "INACTIVE");
            var thrown = refusal(() -> service.create(new LoadEstimates.CreateEstimate(source, null, List.of(), null)));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("vehicleTypeIds");
        }

        @Test
        @DisplayName("a chosen type that does not exist is VEHICLE_TYPE_NOT_FOUND; an INACTIVE one VEHICLE_TYPE_INACTIVE, not silently left out")
        void chosenTypesAreCheckedNotDropped() {
            var source = List.of(new LoadEstimates.Source(LoadEstimates.SourceType.ORDER, orderId));
            UUID unknown = UUID.randomUUID();
            when(vehicleTypes.findById(unknown)).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.create(new LoadEstimates.CreateEstimate(source, null, List.of(unknown), null)))
                    .errorCode()).isEqualTo(ErrorCode.VEHICLE_TYPE_NOT_FOUND);

            truck.apply("Truck 5t", "TRUCK", 6000, 2400, 2400, 0, null, null, new BigDecimal("5000"), 3, null, "INACTIVE");
            assertThat(refusal(() -> service.create(new LoadEstimates.CreateEstimate(source, null, List.of(truckId), null)))
                    .errorCode()).isEqualTo(ErrorCode.VEHICLE_TYPE_INACTIVE);
        }
    }

    @Nested
    @DisplayName("lines without a size or weight (LP2-3)")
    class MissingPackaging {

        @Test
        @DisplayName("when no line can be measured the estimate is LOAD_ESTIMATE_PACKAGING_MISSING, one field error per item to complete")
        void allLinesBlocked() {
            org.mockito.Mockito.doReturn(List.of(unresolved("CUP-12OZ", 10, List.of("weightKg")))).when(packaging).resolveAll(any());

            var thrown = refusal(() -> service.create(create()));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING);
            assertThat(thrown.fieldErrors()).singleElement().satisfies(f -> {
                assertThat(f.field()).isEqualTo("inventoryItems[" + itemId + "]");
                assertThat(f.message()).contains("CUP-12OZ").contains("weightKg").contains(lineId.toString());
            });
            verify(estimates, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a carton with a zero side is blocked like a missing one")
        void zeroSideIsBlocked() {
            org.mockito.Mockito.doReturn(List.of(resolved("CUP-12OZ", 10, 0, 400, 300, "2.5"))).when(packaging).resolveAll(any());

            assertThat(refusal(() -> service.create(create())).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING);
        }

        @Test
        @DisplayName("with one line measurable and one not, the estimate is stored incomplete and names the blocked line")
        void oneLineBlocked() {
            UUID other = UUID.randomUUID();
            var lineA = new OrderSummary.LineSummary(lineId, "CUP-12OZ", 10, Money.vnd(1000), Money.vnd(10_000), List.of());
            var lineB = new OrderSummary.LineSummary(other, "LID-90", 10, Money.vnd(100), Money.vnd(1_000), List.of());
            OrderSummary base = order(OrderStatus.READY_TO_FULFILL, WAREHOUSE, 10, 0, 0);
            when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-0001", base.customerId(),
                    OrderStatus.READY_TO_FULFILL, base.total(), List.of(lineA, lineB), NOW, null, null, null, null, null,
                    null, null, null, null, WAREHOUSE, NOW, null, null, base.lifecycle())));
            org.mockito.Mockito.doReturn(List.of(resolved("CUP-12OZ", 10, 600, 400, 300, "2.5"),
                    unresolved("LID-90", 10, List.of("lengthCm", "weightKg")))).when(packaging).resolveAll(any());

            var view = service.create(create());

            assertThat(view.incomplete()).isTrue();
            assertThat(view.missing()).singleElement().satisfies(m -> {
                assertThat(m.sku()).isEqualTo("LID-90");
                assertThat(m.missingFields()).containsExactly("lengthCm", "weightKg");
            });
            assertThat(view.lines()).extracting(LoadEstimates.EstimateLine::sku).containsExactly("CUP-12OZ");
        }
    }

    @Nested
    @DisplayName("planning and storing")
    class Storing {

        @Test
        @DisplayName("the planner places the cartons; the estimate is stored CURRENT and read back as stored")
        void storesTheEstimate() {
            var view = service.create(create());

            assertThat(view.status()).isEqualTo("CURRENT");
            assertThat(view.sourceType()).isEqualTo(LoadEstimates.SourceType.ORDER);
            assertThat(view.selectionMode()).isEqualTo(SelectionMode.SINGLE_TYPE);
            assertThat(view.lines()).singleElement().satisfies(l -> {
                assertThat(l.quantity()).isEqualTo(10);
                assertThat(l.cartons()).isEqualTo(10);
                assertThat(l.weightKg()).isEqualByComparingTo("2.5");
                assertThat(l.dimsMm().l()).isEqualTo(600);
            });
            assertThat(view.vehicles()).isNotEmpty();
            assertThat(view.unplaced()).isEmpty();
            assertThat(view.meta().seed()).isZero();
            assertThat(view.meta().placedBoxes()).isEqualTo(10);
            assertThat(view.incomplete()).isFalse();
            verify(estimates).lockSource("ORDER:" + orderId);
        }

        @Test
        @DisplayName("an earlier current estimate of the source is superseded first")
        void supersedesTheCurrentOne() {
            LoadEstimateJpaEntity earlier = new LoadEstimateJpaEntity(UUID.randomUUID(), "ORDER", orderId, WAREHOUSE,
                    json.createArrayNode(), "SINGLE_TYPE", json.createArrayNode(), json.createObjectNode(),
                    json.createArrayNode(), json.createArrayNode());
            when(estimates.findAllBySourceTypeAndSourceIdAndStatus("ORDER", orderId, "CURRENT")).thenReturn(List.of(earlier));

            service.create(create());

            assertThat(earlier.getStatus()).isEqualTo("SUPERSEDED");
            verify(estimates).saveAllAndFlush(List.of(earlier));
        }

        @Test
        @DisplayName("an order that became ineligible while it was planned is refused and nothing is saved")
        void orderChangedStatusMeanwhile() {
            when(orders.lockedStatus(orderId)).thenReturn(Optional.of(OrderStatus.CANCELLED));

            var thrown = refusal(() -> service.create(create()));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
            assertThat(thrown.getMessage()).contains("nothing was saved");
            verify(estimates, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an order that vanished while it was planned is refused too")
        void orderVanished() {
            when(orders.lockedStatus(orderId)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.create(create())).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("an order shipped in part or released elsewhere while it was planned is refused: estimate again")
        void orderChangedMeanwhile() {
            when(orders.findById(orderId)).thenReturn(
                    Optional.of(order(OrderStatus.READY_TO_FULFILL, WAREHOUSE, 10, 0, 0)),
                    Optional.of(order(OrderStatus.READY_TO_FULFILL, WAREHOUSE, 10, 4, 0)));

            var thrown = refusal(() -> service.create(create()));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
            assertThat(thrown.getMessage()).contains("changed while it was estimated");

            when(orders.findById(orderId)).thenReturn(
                    Optional.of(order(OrderStatus.READY_TO_FULFILL, WAREHOUSE, 10, 0, 0)),
                    Optional.of(order(OrderStatus.READY_TO_FULFILL, OTHER_WAREHOUSE, 10, 0, 0)));
            assertThat(refusal(() -> service.create(create())).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("a carton too big for every vehicle is unplaced, not an error")
        void oversizeCartonIsUnplaced() {
            org.mockito.Mockito.doReturn(List.of(resolved("CUP-12OZ", 10, 9000, 400, 300, "2.5"))).when(packaging).resolveAll(any());

            var view = service.create(create());

            assertThat(view.unplaced()).isNotEmpty();
            assertThat(view.meta().unplacedBoxes()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("a transfer order (SCRUM-287 PR T)")
    class Transfers {

        private final UUID transferId = UUID.randomUUID();

        private TransferFulfillment.TransferView transfer(String status, UUID from) {
            return new TransferFulfillment.TransferView(transferId, "TO-0001", from, OTHER_WAREHOUSE, status,
                    TransferDeliveryMode.FULL_TRUCKLOAD, null, null, null,
                    List.of(new TransferFulfillment.Line(lineId, 1, "CUP-12OZ", null, 20, 0)));
        }

        private LoadEstimates.CreateEstimate request(UUID warehouse) {
            return new LoadEstimates.CreateEstimate(List.of(new LoadEstimates.Source(LoadEstimates.SourceType.TRANSFER,
                    transferId)), warehouse, List.of(truckId), null);
        }

        @Test
        @DisplayName("a transfer that does not exist is not found; naming another warehouse than its source is VALIDATION_FAILED")
        void guards() {
            when(transfers.find(transferId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.create(request(null))).isInstanceOf(NotFoundException.class);

            when(transfers.find(transferId)).thenReturn(Optional.of(transfer("APPROVED", WAREHOUSE)));
            assertThat(refusal(() -> service.create(request(OTHER_WAREHOUSE))).fieldErrors())
                    .extracting(f -> f.field()).containsExactly("warehouseId");
        }

        @Test
        @DisplayName("a transfer is estimated until it is dispatched: a dispatched or completed one is not eligible")
        void statusMustBeBeforeDispatch() {
            for (String bad : List.of("IN_TRANSIT", "COMPLETED", "CANCELLED")) {
                when(transfers.find(transferId)).thenReturn(Optional.of(transfer(bad, WAREHOUSE)));

                assertThat(refusal(() -> service.create(request(null))).errorCode()).as(bad)
                        .isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
            }
        }

        @Test
        @DisplayName("every requested unit is estimated, at the source warehouse")
        void estimatesTheRequest() {
            when(transfers.find(transferId)).thenReturn(Optional.of(transfer("PICKING", WAREHOUSE)));

            var view = service.create(request(WAREHOUSE));

            assertThat(view.sourceType()).isEqualTo(LoadEstimates.SourceType.TRANSFER);
            assertThat(view.warehouseId()).isEqualTo(WAREHOUSE);
            assertThat(view.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(20));
            verify(estimates).lockSource("TRANSFER:" + transferId);
        }

        @Test
        @DisplayName("a transfer dispatched or edited while it was planned is refused")
        void changedMeanwhile() {
            when(transfers.find(transferId)).thenReturn(Optional.of(transfer("PICKING", WAREHOUSE)),
                    Optional.of(transfer("IN_TRANSIT", WAREHOUSE)));

            assertThat(refusal(() -> service.create(request(null))).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);

            when(transfers.find(transferId)).thenReturn(Optional.of(transfer("PICKING", WAREHOUSE)),
                    Optional.of(transfer("PICKING", OTHER_WAREHOUSE)));
            assertThat(refusal(() -> service.create(request(null))).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("a transfer none of whose lines can be measured is LOAD_ESTIMATE_PACKAGING_MISSING")
        void unmeasured() {
            when(transfers.find(transferId)).thenReturn(Optional.of(transfer("DRAFT", WAREHOUSE)));
            org.mockito.Mockito.doReturn(List.of(unresolved("CUP-12OZ", 20, List.of("weightKg")))).when(packaging).resolveAll(any());

            assertThat(refusal(() -> service.create(request(null))).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING);
        }
    }

    @Nested
    @DisplayName("reading estimates back")
    class Reading {

        private LoadEstimateJpaEntity row(String status) {
            return new LoadEstimateJpaEntity(UUID.randomUUID(), "ORDER", orderId, WAREHOUSE, json.valueToTree(List.of(truckId)),
                    "FEWEST_VEHICLES", json.createArrayNode(), json.createObjectNode(), json.createArrayNode(),
                    json.createArrayNode());
        }

        @Test
        @DisplayName("an estimate that does not exist is LOAD_ESTIMATE_NOT_FOUND; one of another warehouse is OUT_OF_DATA_SCOPE")
        void get() {
            UUID unknown = UUID.randomUUID();
            when(estimates.findById(unknown)).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.get(unknown)).errorCode()).isEqualTo(ErrorCode.LOAD_ESTIMATE_NOT_FOUND);

            LoadEstimateJpaEntity row = row("CURRENT");
            when(estimates.findById(row.getId())).thenReturn(Optional.of(row));
            assertThat(service.get(row.getId()).selectionMode()).isEqualTo(SelectionMode.FEWEST_VEHICLES);
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.get(row.getId())).errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("the current estimate needs its source type and id, and exists")
        void current() {
            assertThat(refusal(() -> service.current(null, orderId)).fieldErrors()).extracting(f -> f.field())
                    .containsExactly("sourceType");
            assertThat(refusal(() -> service.current(LoadEstimates.SourceType.ORDER, null)).fieldErrors())
                    .extracting(f -> f.field()).containsExactly("sourceId");

            when(estimates.findBySourceTypeAndSourceIdAndStatus("ORDER", orderId, "CURRENT")).thenReturn(Optional.empty());
            assertThat(refusal(() -> service.current(LoadEstimates.SourceType.ORDER, orderId)).errorCode())
                    .isEqualTo(ErrorCode.LOAD_ESTIMATE_NOT_FOUND);

            LoadEstimateJpaEntity row = row("CURRENT");
            when(estimates.findBySourceTypeAndSourceIdAndStatus("ORDER", orderId, "CURRENT")).thenReturn(Optional.of(row));
            assertThat(service.current(LoadEstimates.SourceType.ORDER, orderId).id()).isEqualTo(row.getId());
        }
    }
}
