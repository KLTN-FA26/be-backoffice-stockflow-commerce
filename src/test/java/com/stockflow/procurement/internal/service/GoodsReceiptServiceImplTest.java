package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.GoodsReceiptConfirmed;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReceiptApproval;
import com.stockflow.procurement.internal.domain.ReceiptLine;
import com.stockflow.procurement.internal.domain.ReceivingLocation;
import com.stockflow.procurement.internal.domain.ReceivingLocations;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder.LineStatus;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder.Status;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrders;
import com.stockflow.procurement.internal.repository.GoodsReceiptSearch;
import com.stockflow.procurement.internal.service.GoodsReceipts.Create;
import com.stockflow.procurement.internal.service.GoodsReceipts.Decision;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewLine;
import com.stockflow.procurement.internal.service.GoodsReceipts.Part;
import com.stockflow.procurement.internal.service.GoodsReceipts.ReceiptView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs warehouse/03 (receipt): BR-01 receivable PO, BR-02 over-receipt tolerance, BR-03 lot and expiry,
 * BR-05 a confirmed count is final, BR-06 expiry warning, BR-08 QC accounts for every unit.
 */
class GoodsReceiptServiceImplTest {

    // 03:00Z = 10:00 in Saigon, 2026-10-22
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final GoodsReceiptRepository receipts = mock(GoodsReceiptRepository.class);
    private final GoodsReceiptSearch search = mock(GoodsReceiptSearch.class);
    private final ReceivingPurchaseOrders orders = mock(ReceivingPurchaseOrders.class);
    private final ReceivingLocations locations = mock(ReceivingLocations.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final PutawayTasks putawayTasks = mock(PutawayTasks.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final InventoryControlService inventoryControl = mock(InventoryControlService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UomConversionService units = mock(UomConversionService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private GoodsReceiptServiceImpl service = service(NOW);

    private final UUID user = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final UUID poId = UUID.randomUUID();
    private final UUID poLineId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private final ReceivingLocation receivingBin = new ReceivingLocation(UUID.randomUUID(), "HCM-RCV-01", warehouse,
            "RECEIVING");
    private final ReceivingLocation qcArea = new ReceivingLocation(UUID.randomUUID(), "HCM-QC-01", warehouse,
            "QUALITY_CONTROL");
    private final ReceivingLocation quarantineA = new ReceivingLocation(UUID.randomUUID(), "HCM-QUA-01", warehouse,
            "QUARANTINE");
    private final ReceivingLocation quarantineB = new ReceivingLocation(UUID.randomUUID(), "HCM-QUA-02", warehouse,
            "QUARANTINE");
    private final ReceivingLocation otherWarehouseBin = new ReceivingLocation(UUID.randomUUID(), "HN-RCV-01",
            UUID.randomUUID(), "RECEIVING");

    private GoodsReceiptServiceImpl service(Instant now) {
        return new GoodsReceiptServiceImpl(receipts, search, orders, locations, inventory, warehouses, putawayTasks, identity,
                inventoryControl, units, users, events, Clock.fixed(now, ZoneOffset.UTC));
    }

    private InventoryItemPolicy plainItem() {
        return new InventoryItemPolicy(itemId, "CUP-01", false, false, false);
    }

    @BeforeEach
    void defaults() {
        when(receipts.save(any(GoodsReceipt.class))).thenAnswer(call -> call.getArgument(0));
        when(receipts.nextNumber(any())).thenReturn("GR-20261022-0001");
        when(receipts.receivedOnOtherReceipts(anyIterable(), any())).thenReturn(Map.of());
        when(receipts.confirmedQuantities(any())).thenReturn(Map.of());
        when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(plainItem()));
        when(inventory.itemPolicies(anyCollection())).thenAnswer(call -> Map.of(itemId, plainItem()));
        when(inventory.receiptLinesWithStockPackages(anyCollection())).thenReturn(Set.of());
        when(identity.displayNames(anyCollection())).thenAnswer(call -> new java.util.HashMap<UUID, String>());
        when(inventoryControl.expiryWarningDays()).thenReturn(30);
        when(search.orderLabels(anyCollection())).thenAnswer(call -> new java.util.HashMap<UUID, GoodsReceiptSearch.OrderLabel>());
        when(warehouses.findWarehouse(warehouse))
                .thenReturn(Optional.of(new WarehouseView(warehouse, "HCM", "Kho HCM", "HCM", true)));
        for (ReceivingLocation l : List.of(receivingBin, qcArea, quarantineA, quarantineB, otherWarehouseBin)) {
            when(locations.byCode(l.code())).thenReturn(Optional.of(l));
        }
        when(locations.byIds(anyCollection())).thenAnswer(call -> {
            java.util.Map<UUID, ReceivingLocation> all = new java.util.HashMap<>();
            for (ReceivingLocation l : List.of(receivingBin, qcArea, quarantineA, quarantineB)) {
                all.put(l.id(), l);
            }
            return all;
        });
        when(locations.hasArea(warehouse, "QUALITY_CONTROL")).thenReturn(true);
    }

    private ReceivingPurchaseOrder po(Status status, String ordered, String tolerance, boolean subcontract) {
        ReceivingPurchaseOrder order = new ReceivingPurchaseOrder(poId, "PO-1", status, UUID.randomUUID(), warehouse,
                UUID.randomUUID(), tolerance == null ? null : new BigDecimal(tolerance), false,
                List.of(new ReceivingPurchaseOrder.Line(poLineId, 1, itemId, new BigDecimal(ordered), LineStatus.OPEN)),
                subcontract);
        when(orders.find(poId)).thenReturn(Optional.of(order));
        when(orders.lock(poId)).thenReturn(Optional.of(order));
        return order;
    }

    private GoodsReceipt draft(ReceiptLine... lines) {
        GoodsReceipt receipt = GoodsReceipt.draft(UUID.randomUUID(), "GR-20261022-0001", poId, UUID.randomUUID(),
                warehouse, user, null, null, NOW);
        receipt.replaceLines(List.of(lines));
        when(receipts.findById(receipt.id())).thenReturn(Optional.of(receipt));
        when(receipts.findByIdForUpdate(receipt.id())).thenReturn(Optional.of(receipt));
        return receipt;
    }

    private ReceiptLine counted(int qty, boolean qc) {
        return ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), qty, null, null, null, qc, null);
    }

    private NewLine newLine(int qty) {
        return new NewLine(poLineId, qty, null, null, "HCM-RCV-01", null);
    }

    private static ErrorCode code(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).isNotNull();
        return thrown.errorCode();
    }

    private static BusinessException thrown(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).isNotNull();
        return thrown;
    }

    // ------------------------------------------------------------------ create

    @Nested
    class CreateReceipt {

        @Test
        @DisplayName("an unknown purchase order is PURCHASE_ORDER_NOT_FOUND")
        void unknownOrder() {
            UUID missing = UUID.randomUUID();
            when(orders.find(missing)).thenReturn(Optional.empty());
            assertThat(code(() -> service.create(new Create(missing, null, null), user)))
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("BR-01: goods are received only against a CONFIRMED or PARTIALLY_RECEIVED order")
        void orderMustBeReceivable() {
            for (Status status : List.of(Status.DRAFT, Status.PENDING_APPROVAL, Status.APPROVED, Status.RECEIVED,
                    Status.CLOSED, Status.CANCELLED)) {
                po(status, "10", null, false);
                assertThat(code(() -> service.create(new Create(poId, null, null), user)))
                        .as(status.name()).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
            }
            verify(receipts, never()).save(any(GoodsReceipt.class));
        }

        @Test
        @DisplayName("BR-01: an order the supplier rejected takes no receipt even when CONFIRMED")
        void rejectedBySupplier() {
            ReceivingPurchaseOrder rejected = new ReceivingPurchaseOrder(poId, "PO-1", Status.CONFIRMED,
                    UUID.randomUUID(), warehouse, UUID.randomUUID(), null, true, List.of());
            when(orders.find(poId)).thenReturn(Optional.of(rejected));
            assertThat(code(() -> service.create(new Create(poId, null, null), user)))
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
        }

        @Test
        @DisplayName("a draft is made in the order's warehouse, numbered for the Saigon business day, notes trimmed")
        void createsDraft() {
            po(Status.PARTIALLY_RECEIVED, "10", null, false);
            service = service(Instant.parse("2026-10-22T18:00:00Z"));

            ReceiptView view = service.create(new Create(poId, "  DN-77 ", "   "), user);

            verify(receipts).nextNumber(LocalDate.of(2026, 10, 23));
            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
            assertThat(view.warehouseId()).isEqualTo(warehouse);
            assertThat(view.deliveryNote()).isEqualTo("DN-77");
            assertThat(view.note()).isNull();
            assertThat(view.receivedBy()).isEqualTo(user);
            assertThat(view.lines()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ counting

    @Nested
    class Counting {

        @Test
        @DisplayName("an unknown receipt is GOODS_RECEIPT_NOT_FOUND")
        void unknownReceipt() {
            UUID missing = UUID.randomUUID();
            when(receipts.findById(missing)).thenReturn(Optional.empty());
            assertThat(code(() -> service.replaceLines(missing, List.of()))).isEqualTo(ErrorCode.GOODS_RECEIPT_NOT_FOUND);
        }

        @Test
        @DisplayName("a counted line is on the receipt; a plain item needs no QC (2-step flow)")
        void happyPath() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();

            ReceiptView view = service.replaceLines(receipt.id(), List.of(newLine(40)));

            assertThat(view.lines()).hasSize(1);
            assertThat(view.lines().get(0).quantity()).isEqualTo(40);
            assertThat(view.lines().get(0).locationCode()).isEqualTo("HCM-RCV-01");
            assertThat(view.lines().get(0).qcRequired()).isFalse();
            assertThat(view.lines().get(0).sku()).isEqualTo("CUP-01");
        }

        @Test
        @DisplayName("an item that needs QC is counted as a QC line, except on a SUBCONTRACT order (production QC)")
        void qcRequiredFlag() {
            InventoryItemPolicy policy = new InventoryItemPolicy(itemId, "CUP-01", false, false, true);
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(policy));
            when(inventory.itemPolicies(anyCollection())).thenAnswer(call -> Map.of(itemId, policy));
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt standard = draft();
            assertThat(service.replaceLines(standard.id(), List.of(newLine(5))).lines().get(0).qcRequired()).isTrue();

            po(Status.CONFIRMED, "100", null, true);
            GoodsReceipt subcontract = draft();
            assertThat(service.replaceLines(subcontract.id(), List.of(newLine(5))).lines().get(0).qcRequired())
                    .isFalse();
        }

        @Test
        @DisplayName("recounting the same PO line and lot keeps the draft line's id (cartons point at it)")
        void recountKeepsLineId() {
            po(Status.CONFIRMED, "100", null, false);
            ReceiptLine existing = counted(10, false);
            GoodsReceipt receipt = draft(existing);

            ReceiptView view = service.replaceLines(receipt.id(), List.of(newLine(25)));

            assertThat(view.lines().get(0).id()).isEqualTo(existing.id());
            assertThat(view.lines().get(0).quantity()).isEqualTo(25);
        }

        @Test
        @DisplayName("SCRUM-473 D5: a SUBCONTRACT receipt refuses cartons on lines[i].packages, and labels none")
        void subcontractRefusesCartons() {
            po(Status.CONFIRMED, "100", null, true);
            GoodsReceipt receipt = draft();
            NewLine withCartons = new NewLine(poLineId, 10, null, null, "HCM-RCV-01", null, "CARTON",
                    List.of(new GoodsReceipts.NewPackage(null, 10)));

            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(withCartons)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_PACKAGES_NOT_SUPPORTED);
            assertThat(e.fieldErrors()).extracting(FieldError::field, FieldError::code)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("lines[0].packages",
                            "RECEIPT_PACKAGES_NOT_SUPPORTED"));
            verify(receipts, never()).save(any(GoodsReceipt.class));
            verify(inventory, never()).countReceivingPackages(any());
        }

        @Test
        @DisplayName("a line left out of the recount that has labelled cartons is RECEIPT_LINE_HAS_PACKAGES")
        void droppedLineWithCartons() {
            po(Status.CONFIRMED, "100", null, false);
            ReceiptLine existing = counted(10, false);
            GoodsReceipt receipt = draft(existing);
            when(inventory.receiptLinesWithStockPackages(anyCollection())).thenReturn(Set.of(existing.id()));

            assertThat(code(() -> service.replaceLines(receipt.id(), List.of()))).isEqualTo(
                    ErrorCode.RECEIPT_LINE_HAS_PACKAGES);
            assertThat(receipt.lines()).containsExactly(existing);
        }

        @Test
        @DisplayName("a line that is not a line of the order is RECEIPT_LINE_NOT_ON_PO, named on lines[i].purchaseOrderLineId")
        void lineNotOnOrder() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(),
                    List.of(new NewLine(UUID.randomUUID(), 1, null, null, "HCM-RCV-01", null))));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_LINE_NOT_ON_PO);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("lines[0].purchaseOrderLineId");
        }

        @Test
        @DisplayName("a PO line that is not open (closed short, cancelled) takes no more goods: PO_LINE_NOT_OPEN")
        void closedPoLine() {
            ReceivingPurchaseOrder closed = new ReceivingPurchaseOrder(poId, "PO-1", Status.PARTIALLY_RECEIVED,
                    UUID.randomUUID(), warehouse, UUID.randomUUID(), null, false,
                    List.of(new ReceivingPurchaseOrder.Line(poLineId, 1, itemId, BigDecimal.TEN, LineStatus.CLOSED)));
            when(orders.find(poId)).thenReturn(Optional.of(closed));
            GoodsReceipt receipt = draft();
            assertThat(code(() -> service.replaceLines(receipt.id(), List.of(newLine(1)))))
                    .isEqualTo(ErrorCode.PO_LINE_NOT_OPEN);
        }

        @Test
        @DisplayName("an unknown location code is LOCATION_NOT_FOUND; a bin of another warehouse or the wrong area type "
                + "is LOCATION_AREA_MISMATCH")
        void locationGuards() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            when(locations.byCode("NOPE")).thenReturn(Optional.empty());

            assertThat(code(() -> service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 1, null, null, "nope", null))))).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
            assertThat(code(() -> service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 1, null, null, "HN-RCV-01", null)))))
                    .isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
            assertThat(code(() -> service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 1, null, null, "HCM-QC-01", null)))))
                    .isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
            assertThat(code(() -> service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 1, null, null, null, null))))).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        }

        @Test
        @DisplayName("a location code is matched trimmed and case-insensitively")
        void locationCodeNormalised() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            ReceiptView view = service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 3, null, null, "  hcm-rcv-01 ", null)));
            assertThat(view.lines().get(0).locationCode()).isEqualTo("HCM-RCV-01");
        }

        // ---- BR-03 / BR-06: lot and expiry

        private void tracked(boolean lot, boolean expiry) {
            trackedWithMaxShelfLife(lot, expiry, null);
        }

        private void trackedWithMaxShelfLife(boolean lot, boolean expiry, Integer maxShelfLifeDays) {
            InventoryItemPolicy policy = new InventoryItemPolicy(itemId, "CUP-01", lot, expiry, false,
                    maxShelfLifeDays);
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(policy));
            when(inventory.itemPolicies(anyCollection())).thenAnswer(call -> Map.of(itemId, policy));
        }

        private List<String> fieldCodes(NewLine line) {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(line)));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
            return e.fieldErrors().stream().map(FieldError::code).toList();
        }

        @Test
        @DisplayName("BR-03: a lot-tracked item needs a lot number; an untracked one refuses one")
        void lotRules() {
            tracked(true, false);
            assertThat(fieldCodes(newLine(1))).containsExactly("LOT_REQUIRED");
            tracked(false, false);
            assertThat(fieldCodes(new NewLine(poLineId, 1, "L1", null, "HCM-RCV-01", null)))
                    .containsExactly("LOT_NOT_ALLOWED");
        }

        @Test
        @DisplayName("BR-03: an expiry-tracked item needs an expiry date; an untracked one refuses one")
        void expiryRules() {
            tracked(false, true);
            assertThat(fieldCodes(newLine(1))).containsExactly("EXPIRY_REQUIRED");
            tracked(false, false);
            assertThat(fieldCodes(new NewLine(poLineId, 1, null, TODAY.plusDays(90), "HCM-RCV-01", null)))
                    .containsExactly("EXPIRY_NOT_ALLOWED");
        }

        @Test
        @DisplayName("BR-RCP-004: an expiry date up to receiving day + maximum shelf life is accepted, one day more is refused "
                + "on that line with the latest allowed date in the message")
        void expiryWithinMaxShelfLife() {
            trackedWithMaxShelfLife(true, true, 30);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt ok = draft();
            ReceiptView view = service.replaceLines(ok.id(),
                    List.of(new NewLine(poLineId, 1, "L1", TODAY.plusDays(30), "HCM-RCV-01", null)));
            assertThat(view.lines()).hasSize(1);

            GoodsReceipt receipt = draft();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 1, "L1", TODAY.plusDays(10), "HCM-RCV-01", null),
                    new NewLine(poLineId, 1, "L2", TODAY.plusDays(31), "HCM-RCV-01", null))));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
            assertThat(e.fieldErrors()).hasSize(1);
            FieldError error = e.fieldErrors().get(0);
            assertThat(error.field()).isEqualTo("lines[1].expiryDate");
            assertThat(error.code()).isEqualTo("EXPIRY_EXCEEDS_MAX_SHELF_LIFE");
            assertThat(error.message()).contains(TODAY.plusDays(30).toString());
        }

        @Test
        @DisplayName("BR-RCP-004: with no maximum shelf life configured the expiry date has no upper bound")
        void noMaxShelfLifeNoUpperBound() {
            trackedWithMaxShelfLife(true, true, null);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            ReceiptView view = service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 1, "L1", TODAY.plusYears(50), "HCM-RCV-01", null)));
            assertThat(view.lines()).hasSize(1);
        }

        @Test
        @DisplayName("BR-RCP-004: an expiry date not after the receiving day stays EXPIRY_NOT_AFTER_RECEIPT whatever the maximum")
        void lowerBoundUnchangedWithMax() {
            trackedWithMaxShelfLife(true, true, 30);
            assertThat(fieldCodes(new NewLine(poLineId, 1, "L1", TODAY.minusDays(1), "HCM-RCV-01", null)))
                    .containsExactly("EXPIRY_NOT_AFTER_RECEIPT");
        }

        @Test
        @DisplayName("BR-06: an expiry date must be after the receiving day (Saigon): today and yesterday are refused, "
                + "tomorrow is fine")
        void expiryAfterReceiving() {
            tracked(true, true);
            assertThat(fieldCodes(new NewLine(poLineId, 1, "L1", TODAY, "HCM-RCV-01", null)))
                    .containsExactly("EXPIRY_NOT_AFTER_RECEIPT");
            assertThat(fieldCodes(new NewLine(poLineId, 1, "L1", TODAY.minusDays(1), "HCM-RCV-01", null)))
                    .containsExactly("EXPIRY_NOT_AFTER_RECEIPT");

            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            ReceiptView view = service.replaceLines(receipt.id(),
                    List.of(new NewLine(poLineId, 1, "L1", TODAY.plusDays(1), "HCM-RCV-01", null)));
            assertThat(view.lines().get(0).lotNumber()).isEqualTo("L1");
        }

        @Test
        @DisplayName("a lot number that is blank counts as none")
        void blankLotIsNone() {
            tracked(true, false);
            assertThat(fieldCodes(new NewLine(poLineId, 1, "   ", null, "HCM-RCV-01", null)))
                    .containsExactly("LOT_REQUIRED");
        }

        @Test
        @DisplayName("the same PO line and lot twice in one count is DUPLICATE_RECEIPT_LINE")
        void duplicateLot() {
            tracked(true, false);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 1, "L1", null, "HCM-RCV-01", null),
                    new NewLine(poLineId, 2, "L1", null, "HCM-RCV-01", null))));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.DUPLICATE_RECEIPT_LINE);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("lines[1].lotNumber");
        }

        @Test
        @DisplayName("two lots of the same PO line are two lines")
        void twoLotsAreFine() {
            tracked(true, false);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            ReceiptView view = service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 1, "L1", null, "HCM-RCV-01", null),
                    new NewLine(poLineId, 2, "L2", null, "HCM-RCV-01", null)));
            assertThat(view.lines()).hasSize(2);
        }

        @Test
        @DisplayName("every refusal of a count is reported at once; the code is the first line's, and the message counts the rest")
        void reportsEveryLineAtOnce() {
            tracked(true, true);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 1, null, null, "HCM-RCV-01", null),
                    new NewLine(UUID.randomUUID(), 1, null, null, "HCM-RCV-01", null))));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);
            assertThat(e.fieldErrors()).extracting(FieldError::field)
                    .containsExactly("lines[0].lotNumber", "lines[0].expiryDate", "lines[1].purchaseOrderLineId");
            assertThat(e.getMessage()).startsWith("Receipt line 1:").endsWith("(and 2 more)");
        }

        // ---- BR-02 tolerance: flagged on the count, decided by the manager at confirmation (SCRUM-477)

        @Test
        @DisplayName("BR-02: ordered 100 with 5% tolerance receives up to 105 in all: 105 is fine, 106 is accepted but flagged OVER_TOLERANCE")
        void toleranceBoundary() {
            po(Status.CONFIRMED, "100", "5", false);
            GoodsReceipt receipt = draft();
            ReceiptView within = service.replaceLines(receipt.id(), List.of(newLine(105)));
            assertThat(within.lines()).hasSize(1);
            assertThat(within.warnings()).isEmpty();
            assertThat(within.lines().get(0).warnings()).isEmpty();

            ReceiptView over = service.replaceLines(receipt.id(), List.of(newLine(106)));
            assertThat(over.lines().get(0).quantity()).isEqualTo(106);
            assertThat(over.lines().get(0).warnings()).containsExactly(GoodsReceipts.OVER_TOLERANCE);
            assertThat(over.warnings()).containsExactly(GoodsReceipts.OVER_TOLERANCE);
            assertThat(over.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        }

        @Test
        @DisplayName("BR-02: the limit is rounded down (10 ordered, 2.5% -> 10, not 11) and no tolerance means no excess")
        void toleranceRoundsDown() {
            po(Status.CONFIRMED, "10", "2.5", false);
            GoodsReceipt receipt = draft();
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(11))).warnings())
                    .containsExactly(GoodsReceipts.OVER_TOLERANCE);
            po(Status.CONFIRMED, "10", null, false);
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(11))).warnings())
                    .containsExactly(GoodsReceipts.OVER_TOLERANCE);
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(10))).warnings()).isEmpty();
        }

        @Test
        @DisplayName("BR-02: units already posted on other receipts count against the limit; the flag is on the line")
        void toleranceCountsOtherReceipts() {
            po(Status.PARTIALLY_RECEIVED, "100", "0", false);
            GoodsReceipt receipt = draft();
            when(receipts.receivedOnOtherReceipts(anyIterable(), eq(receipt.id()))).thenReturn(Map.of(poLineId, 60));

            ReceiptView over = service.replaceLines(receipt.id(), List.of(newLine(41)));
            assertThat(over.lines().get(0).warnings()).containsExactly(GoodsReceipts.OVER_TOLERANCE);
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(40))).warnings()).isEmpty();
        }

        @Test
        @DisplayName("BR-02: two lots of one PO line are summed against the limit, and both lines are flagged")
        void toleranceSumsLots() {
            tracked(true, false);
            po(Status.CONFIRMED, "10", null, false);
            GoodsReceipt receipt = draft();
            ReceiptView view = service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 6, "L1", null, "HCM-RCV-01", null),
                    new NewLine(poLineId, 6, "L2", null, "HCM-RCV-01", null)));
            assertThat(view.lines()).extracting(l -> l.warnings()).containsExactly(
                    List.of(GoodsReceipts.OVER_TOLERANCE), List.of(GoodsReceipts.OVER_TOLERANCE));
        }
    }

    // ------------------------------------------------------------------ confirm

    @Nested
    class Confirm {

        @Test
        @DisplayName("BR-01 is asked again at confirmation: the order may have been cancelled since the count")
        void orderNoLongerReceivable() {
            po(Status.CANCELLED, "10", null, false);
            GoodsReceipt receipt = draft(counted(5, false));
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
            verify(inventory, never()).receive(any());
        }

        @Test
        @DisplayName("BR-02 is asked again at confirmation (another receipt may have been confirmed meanwhile): the "
                + "count is not posted, it waits for the manager (BR-RCP-001)")
        void toleranceAtConfirm() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(counted(50, false));
            when(receipts.receivedOnOtherReceipts(anyIterable(), eq(receipt.id()))).thenReturn(Map.of(poLineId, 60));

            ReceiptView view = service.confirm(receipt.id(), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
            assertThat(view.approval().requestedBy()).isEqualTo(user);
            assertThat(view.approval().decision()).isNull();
            assertThat(view.confirmedAt()).isNull();
            verify(inventory, never()).receive(any());
            verify(orders, never()).recordProgress(any(), any(), any(), any(), any(), any());
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("docs 03 edge: a receipt with QC goods and a warehouse without a QUALITY_CONTROL area is QC_AREA_MISSING")
        void qcAreaMissing() {
            po(Status.CONFIRMED, "100", null, false);
            when(locations.hasArea(warehouse, "QUALITY_CONTROL")).thenReturn(false);
            GoodsReceipt receipt = draft(counted(5, true));
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.QC_AREA_MISSING);
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        }

        @Test
        @DisplayName("an empty receipt cannot be confirmed (INVALID_RECEIPT_TRANSITION)")
        void emptyReceipt() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("BR-05: a confirmed receipt is not confirmed twice")
        void notTwice() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(counted(5, false));
            service.confirm(receipt.id(), user);
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            verify(inventory, times(1)).receive(any());
        }

        @Test
        @DisplayName("a full receipt of a plain item: the goods are counted in as stock, the order is RECEIVED and the "
                + "goods are announced ready for putaway")
        void fullReceipt() {
            ReceivingPurchaseOrder order = po(Status.CONFIRMED, "100", null, false);
            ReceiptLine line = counted(100, false);
            GoodsReceipt receipt = draft(line);
            when(receipts.confirmedQuantities(poId)).thenReturn(Map.of(poLineId, 100));
            when(orders.supplierName(order.supplierId())).thenReturn(Optional.of("ACME"));

            ReceiptView view = service.confirm(receipt.id(), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
            ArgumentCaptor<ReceiveStockCommand> receive = ArgumentCaptor.forClass(ReceiveStockCommand.class);
            verify(inventory).receive(receive.capture());
            assertThat(receive.getValue().sku().code()).isEqualTo("CUP-01");
            assertThat(receive.getValue().quantity()).isEqualTo(100);
            assertThat(receive.getValue().locationCode()).isEqualTo("HCM-RCV-01");
            assertThat(receive.getValue().receiptLineId()).isEqualTo(line.id());
            assertThat(receive.getValue().origin().supplierName()).isEqualTo("ACME");
            verify(orders).recordProgress(eq(order), eq(Map.of(poLineId, LineStatus.RECEIVED)),
                    eq(Status.RECEIVED), eq(user), eq(receipt.id()), eq("GR-20261022-0001"));
            verify(events).publishEvent(any(GoodsReceiptConfirmed.class));
            verify(events).publishEvent(any(ReceiptStockReadyForPutaway.class));
        }

        @Test
        @DisplayName("a partial receipt leaves the line and the order PARTIALLY_RECEIVED")
        void partialReceipt() {
            ReceivingPurchaseOrder order = po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(counted(40, false));
            when(receipts.confirmedQuantities(poId)).thenReturn(Map.of(poLineId, 40));

            service.confirm(receipt.id(), user);

            verify(orders).recordProgress(eq(order), eq(Map.of(poLineId, LineStatus.PARTIALLY_RECEIVED)),
                    eq(Status.PARTIALLY_RECEIVED), eq(user), eq(receipt.id()), any());
        }

        @Test
        @DisplayName("over-receipt within tolerance (105 of 100 at 5%) still closes the line as RECEIVED")
        void overReceiptWithinTolerance() {
            ReceivingPurchaseOrder order = po(Status.CONFIRMED, "100", "5", false);
            GoodsReceipt receipt = draft(counted(105, false));
            when(receipts.confirmedQuantities(poId)).thenReturn(Map.of(poLineId, 105));
            service.confirm(receipt.id(), user);
            verify(orders).recordProgress(eq(order), eq(Map.of(poLineId, LineStatus.RECEIVED)), eq(Status.RECEIVED),
                    any(), any(), any());
        }

        @Test
        @DisplayName("a QC line goes to QC, the putaway announcement waits for QC; the confirmation event says so")
        void qcLine() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(counted(10, true));

            ReceiptView view = service.confirm(receipt.id(), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.IN_QC);
            ArgumentCaptor<GoodsReceiptConfirmed> confirmed = ArgumentCaptor.forClass(GoodsReceiptConfirmed.class);
            verify(events).publishEvent(confirmed.capture());
            assertThat(confirmed.getValue().lines()).hasSize(1);
            assertThat(confirmed.getValue().lines().get(0).qcRequired()).isTrue();
            verify(events, never()).publishEvent(any(ReceiptStockReadyForPutaway.class));
        }

        @Test
        @DisplayName("a SUBCONTRACT order's receipt is CLOSED as it is confirmed: no QC, no putaway announcement")
        void subcontract() {
            po(Status.CONFIRMED, "100", null, true);
            GoodsReceipt receipt = draft(counted(10, false));

            ReceiptView view = service.confirm(receipt.id(), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
            verify(events, never()).publishEvent(any(ReceiptStockReadyForPutaway.class));
            verify(inventory).receive(any());
        }

        @Test
        @DisplayName("a SUBCONTRACT receipt needs a QUALITY_CONTROL area too (production QC waits there)")
        void subcontractNeedsQcArea() {
            po(Status.CONFIRMED, "100", null, true);
            when(locations.hasArea(warehouse, "QUALITY_CONTROL")).thenReturn(false);
            GoodsReceipt receipt = draft(counted(10, false));
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.QC_AREA_MISSING);
        }

        @Test
        @DisplayName("the confirmation time is kept to microseconds, as the database keeps it")
        void microsecondTime() {
            po(Status.CONFIRMED, "100", null, false);
            service = service(Instant.parse("2026-10-22T03:00:00.123456789Z"));
            GoodsReceipt receipt = draft(counted(10, false));
            service.confirm(receipt.id(), user);
            assertThat(receipt.confirmedAt()).isEqualTo(Instant.parse("2026-10-22T03:00:00.123456Z"));
        }
    }

    // ------------------------------------------------------------------ cancel

    @Test
    @DisplayName("cancelling a draft voids the cartons labelled while counting; a confirmed receipt cannot be cancelled (BR-05)")
    void cancel() {
        po(Status.CONFIRMED, "100", null, false);
        ReceiptLine line = counted(5, false);
        GoodsReceipt receipt = draft(line);

        ReceiptView view = service.cancel(receipt.id());

        assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        verify(inventory).voidStockPackagesOfReceiptLines(List.of(line.id()));

        GoodsReceipt confirmed = draft(counted(5, false));
        service.confirm(confirmed.id(), user);
        assertThat(code(() -> service.cancel(confirmed.id()))).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
    }

    // ------------------------------------------------------------------ QC

    @Nested
    class Qc {

        private GoodsReceipt inQc(ReceiptLine line) {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(line);
            service.confirm(receipt.id(), user);
            return receipt;
        }

        private GoodsReceipt inQcMoved(ReceiptLine line) {
            GoodsReceipt receipt = inQc(line);
            service.moveToQc(receipt.id(), line.id(), "HCM-QC-01", user);
            return receipt;
        }

        @Test
        @DisplayName("docs 03 step 7: the QC line's goods are moved from the receiving bin to a QUALITY_CONTROL area of the warehouse")
        void moveToQc() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQc(line);

            ReceiptView view = service.moveToQc(receipt.id(), line.id(), "hcm-qc-01", user);

            assertThat(view.lines().get(0).qcLocationCode()).isEqualTo("HCM-QC-01");
            assertThat(view.lines().get(0).qcProgress()).isEqualTo(GoodsReceipts.QcProgress.IN_QC_AREA);
            ArgumentCaptor<MoveStockCommand> move = ArgumentCaptor.forClass(MoveStockCommand.class);
            verify(inventory).move(move.capture());
            assertThat(move.getValue().quantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("the QC area must be a QUALITY_CONTROL area of this warehouse")
        void moveToWrongArea() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQc(line);
            assertThat(code(() -> service.moveToQc(receipt.id(), line.id(), "HCM-QUA-01", user)))
                    .isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
            verify(inventory, never()).move(any());
        }

        @Test
        @DisplayName("a line that needs no QC is not moved to QC, and a line is moved once")
        void moveGuards() {
            ReceiptLine plain = counted(5, false);
            ReceiptLine qc = ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), 5, "L1", null,
                    null, true, null);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(plain, qc);
            service.confirm(receipt.id(), user);

            assertThat(code(() -> service.moveToQc(receipt.id(), plain.id(), "HCM-QC-01", user)))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            service.moveToQc(receipt.id(), qc.id(), "HCM-QC-01", user);
            assertThat(code(() -> service.moveToQc(receipt.id(), qc.id(), "HCM-QC-01", user)))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("BR-08: QC decides only on goods already moved to the QC area")
        void inspectBeforeMove() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQc(line);
            assertThat(code(() -> service.inspect(receipt.id(), line.id(), new Decision(10, null, null), user)))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("BR-08: accepted + quarantined + rejected must equal the units moved to QC")
        void quantityMustAddUp() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);
            assertThat(code(() -> service.inspect(receipt.id(), line.id(),
                    new Decision(6, new Part(3, "HCM-QUA-01", "scratched"), null), user)))
                    .isEqualTo(ErrorCode.QC_QUANTITY_MISMATCH);
            verify(inventory, never()).reclassify(any());
        }

        @Test
        @DisplayName("BR-08: a quarantined or rejected part needs a reason (QC_REASON_REQUIRED on its own field)")
        void reasonRequired() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);
            BusinessException e = thrown(() -> service.inspect(receipt.id(), line.id(),
                    new Decision(7, new Part(3, "HCM-QUA-01", "  "), null), user));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.QC_REASON_REQUIRED);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("quarantined.reason");
        }

        @Test
        @DisplayName("a rejected part needs its quarantine area (QC_TARGET_LOCATION_REQUIRED), and it must be a QUARANTINE "
                + "area of the warehouse")
        void targetRequired() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);
            BusinessException missing = thrown(() -> service.inspect(receipt.id(), line.id(),
                    new Decision(7, null, new Part(3, " ", "broken")), user));
            assertThat(missing.errorCode()).isEqualTo(ErrorCode.QC_TARGET_LOCATION_REQUIRED);
            assertThat(missing.fieldErrors()).extracting(FieldError::field).containsExactly("rejected.locationCode");

            BusinessException wrongArea = thrown(() -> service.inspect(receipt.id(), line.id(),
                    new Decision(7, null, new Part(3, "HCM-RCV-01", "broken")), user));
            assertThat(wrongArea.errorCode()).isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
        }

        @Test
        @DisplayName("quarantined and rejected goods go to two different quarantine locations (QC_LOCATIONS_MUST_DIFFER)")
        void locationsMustDiffer() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);
            assertThat(code(() -> service.inspect(receipt.id(), line.id(), new Decision(4,
                    new Part(3, "HCM-QUA-01", "dented"), new Part(3, "HCM-QUA-01", "broken")), user)))
                    .isEqualTo(ErrorCode.QC_LOCATIONS_MUST_DIFFER);
        }

        @Test
        @DisplayName("a part of zero units needs no area and no reason and is not recorded")
        void zeroPartIgnored() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);

            ReceiptView view = service.inspect(receipt.id(), line.id(),
                    new Decision(10, new Part(0, "nonsense", null), new Part(0, null, null)), user);

            assertThat(view.lines().get(0).inspections()).hasSize(1);
            verify(inventory, never()).reclassify(any());
        }

        @Test
        @DisplayName("all accepted: the receipt goes to putaway and the accepted units are announced from the QC area")
        void allAccepted() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);

            ReceiptView view = service.inspect(receipt.id(), line.id(), new Decision(10, null, null), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
            assertThat(view.lines().get(0).quantityForPutaway()).isEqualTo(10);
            ArgumentCaptor<ReceiptStockReadyForPutaway> ready = ArgumentCaptor.forClass(ReceiptStockReadyForPutaway.class);
            verify(events).publishEvent(ready.capture());
            assertThat(ready.getValue().lines().get(0).quantity()).isEqualTo(10);
            assertThat(ready.getValue().lines().get(0).locationCode()).isEqualTo("HCM-QC-01");
        }

        @Test
        @DisplayName("a split decision: quarantined units are reclassified QUARANTINE, rejected BLOCKED; only the accepted "
                + "part is announced for putaway")
        void splitDecision() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);

            service.inspect(receipt.id(), line.id(), new Decision(5,
                    new Part(3, "HCM-QUA-01", "dented"), new Part(2, "HCM-QUA-02", "broken")), user);

            ArgumentCaptor<ReclassifyStockCommand> reclassify = ArgumentCaptor.forClass(ReclassifyStockCommand.class);
            verify(inventory, times(2)).reclassify(reclassify.capture());
            assertThat(reclassify.getAllValues()).extracting(ReclassifyStockCommand::disposition)
                    .containsExactly(StockDisposition.QUARANTINE, StockDisposition.BLOCKED);
            assertThat(reclassify.getAllValues()).extracting(ReclassifyStockCommand::quantity).containsExactly(3, 2);
            ArgumentCaptor<ReceiptStockReadyForPutaway> ready = ArgumentCaptor.forClass(ReceiptStockReadyForPutaway.class);
            verify(events).publishEvent(ready.capture());
            assertThat(ready.getValue().lines().get(0).quantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("nothing accepted: no putaway announcement and the receipt is CLOSED")
        void nothingAccepted() {
            ReceiptLine line = counted(10, true);
            GoodsReceipt receipt = inQcMoved(line);

            ReceiptView view = service.inspect(receipt.id(), line.id(),
                    new Decision(0, null, new Part(10, "HCM-QUA-02", "all broken")), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
            verify(events, never()).publishEvent(any(ReceiptStockReadyForPutaway.class));
        }

        @Test
        @DisplayName("a line is inspected once")
        void inspectedOnce() {
            ReceiptLine line = counted(10, true);
            ReceiptLine other = ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), 5, "L2",
                    null, null, true, null);
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(line, other);
            service.confirm(receipt.id(), user);
            service.moveToQc(receipt.id(), line.id(), "HCM-QC-01", user);
            service.inspect(receipt.id(), line.id(), new Decision(10, null, null), user);
            assertThat(code(() -> service.inspect(receipt.id(), line.id(), new Decision(10, null, null), user)))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            assertThat(receipt.status()).as("the other line is still undecided").isEqualTo(GoodsReceiptStatus.IN_QC);
        }
    }

    // ------------------------------------------------------------------ over-receipt approval (SCRUM-477)

    @Nested
    class OverReceiptApproval {

        private final UUID manager = UUID.randomUUID();

        private ReceivingPurchaseOrder order;
        /** Units of the PO line held by other posted receipts. */
        private int postedElsewhere;

        @BeforeEach
        void anOrderOf100WithTolerance5() {
            order = po(Status.CONFIRMED, "100", "5", false);
            when(receipts.receivedOnOtherReceipts(anyIterable(), any()))
                    .thenAnswer(call -> postedElsewhere == 0 ? Map.of() : Map.of(poLineId, postedElsewhere));
        }

        /** A draft counting these quantities of the one PO line, confirmed by {@code user}: it now waits. */
        private GoodsReceipt waiting(int... quantities) {
            ReceiptLine[] lines = new ReceiptLine[quantities.length];
            for (int i = 0; i < quantities.length; i++) {
                lines[i] = ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), quantities[i],
                        quantities.length == 1 ? null : "L" + i, null, null, false, null);
            }
            if (quantities.length > 1) {
                InventoryItemPolicy policy = new InventoryItemPolicy(itemId, "CUP-01", true, false, false);
                when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(policy));
                when(inventory.itemPolicies(anyCollection())).thenAnswer(call -> Map.of(itemId, policy));
            }
            GoodsReceipt receipt = draft(lines);
            assertThat(service.confirm(receipt.id(), user).status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
            return receipt;
        }

        @Test
        @DisplayName("a count within the tolerance is posted at once, as before")
        void withinToleranceIsPosted() {
            GoodsReceipt receipt = draft(counted(105, false));
            assertThat(service.confirm(receipt.id(), user).status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
            verify(inventory).receive(any());
        }

        @Test
        @DisplayName("beyond the tolerance, confirming asks for approval: nothing is counted in, no event goes out")
        void confirmWaits() {
            GoodsReceipt receipt = waiting(110);

            assertThat(receipt.approval()).get().satisfies(a -> {
                assertThat(a.requestedBy()).isEqualTo(user);
                assertThat(a.requestedAt()).isEqualTo(NOW);
                assertThat(a.pending()).isTrue();
            });
            verify(inventory, never()).receive(any());
            verify(orders, never()).recordProgress(any(), any(), any(), any(), any(), any());
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("the manager approves: the whole count is posted, in the manager's name, with the reason kept")
        void approvePostsTheWholeCount() {
            GoodsReceipt receipt = waiting(110);
            when(receipts.confirmedQuantities(poId)).thenReturn(Map.of(poLineId, 110));

            ReceiptView view = service.approve(receipt.id(), manager, "  supplier sent a bonus carton ");

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
            ArgumentCaptor<ReceiveStockCommand> receive = ArgumentCaptor.forClass(ReceiveStockCommand.class);
            verify(inventory).receive(receive.capture());
            assertThat(receive.getValue().quantity()).isEqualTo(110);
            assertThat(receive.getValue().actorId()).isEqualTo(manager);
            assertThat(view.confirmedBy()).isEqualTo(manager);
            assertThat(view.approval().decision()).isEqualTo(ReceiptApproval.Decision.APPROVED);
            assertThat(view.approval().decidedBy()).isEqualTo(manager);
            assertThat(view.approval().reason()).isEqualTo("supplier sent a bonus carton");
            assertThat(view.approval().requestedBy()).isEqualTo(user);
            verify(orders).recordProgress(eq(order), eq(Map.of(poLineId, LineStatus.RECEIVED)), eq(Status.RECEIVED),
                    eq(manager), eq(receipt.id()), any());
            verify(events).publishEvent(any(GoodsReceiptConfirmed.class));
            verify(events).publishEvent(any(ReceiptStockReadyForPutaway.class));
            assertThat(view.warnings()).as("posted: no longer a count to decide on").isEmpty();
        }

        @Test
        @DisplayName("four eyes: neither whoever started the receipt nor whoever asked for the approval decides it")
        void fourEyes() {
            GoodsReceipt receipt = draft(counted(110, false));
            UUID confirmer = UUID.randomUUID();
            service.confirm(receipt.id(), confirmer);

            assertThat(code(() -> service.approve(receipt.id(), user, "ok"))).as("the receiver")
                    .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.approve(receipt.id(), confirmer, "ok"))).as("who asked")
                    .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.rejectExcess(receipt.id(), user, "no"))).isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.rejectExcess(receipt.id(), confirmer, "no")))
                    .isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
            verify(inventory, never()).receive(any());
        }

        @Test
        @DisplayName("a decision needs a reason")
        void reasonRequired() {
            GoodsReceipt receipt = waiting(110);
            for (String blank : new String[]{null, "", "   "}) {
                assertThat(code(() -> service.approve(receipt.id(), manager, blank)))
                        .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
                assertThat(code(() -> service.rejectExcess(receipt.id(), manager, blank)))
                        .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
            }
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("only a receipt that is waiting is decided; it is decided once")
        void onlyWaitingIsDecided() {
            GoodsReceipt draft = draft(counted(110, false));
            assertThat(code(() -> service.approve(draft.id(), manager, "ok"))).as("DRAFT")
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            assertThat(code(() -> service.rejectExcess(draft.id(), manager, "no"))).as("DRAFT")
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

            GoodsReceipt receipt = waiting(110);
            service.approve(receipt.id(), manager, "ok");
            assertThat(code(() -> service.approve(receipt.id(), manager, "again")))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            verify(inventory, times(1)).receive(any());
        }

        @Test
        @DisplayName("BR-01 is asked again at the decision: an order cancelled meanwhile takes no goods")
        void orderNoLongerReceivable() {
            GoodsReceipt receipt = waiting(110);
            po(Status.CANCELLED, "100", "5", false);

            assertThat(code(() -> service.approve(receipt.id(), manager, "ok")))
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
            verify(inventory, never()).receive(any());
        }

        @Test
        @DisplayName("the manager rejects the excess: only up to the tolerance is posted (110 counted, 105 allowed)")
        void rejectCutsToTheTolerance() {
            GoodsReceipt receipt = waiting(110);
            when(receipts.confirmedQuantities(poId)).thenReturn(Map.of(poLineId, 105));

            ReceiptView view = service.rejectExcess(receipt.id(), manager, "5 cartons go back to the supplier");

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
            assertThat(view.lines()).extracting(l -> l.quantity()).containsExactly(105);
            ArgumentCaptor<ReceiveStockCommand> receive = ArgumentCaptor.forClass(ReceiveStockCommand.class);
            verify(inventory).receive(receive.capture());
            assertThat(receive.getValue().quantity()).isEqualTo(105);
            assertThat(view.approval().decision()).isEqualTo(ReceiptApproval.Decision.EXCESS_REJECTED);
            assertThat(view.approval().reason()).isEqualTo("5 cartons go back to the supplier");
            assertThat(view.confirmedBy()).isEqualTo(manager);
            verify(orders).recordProgress(eq(order), any(), eq(Status.RECEIVED), eq(manager), eq(receipt.id()), any());
        }

        @Test
        @DisplayName("what other posted receipts already hold narrows the allowance: 60 posted, 50 counted, 45 stay")
        void rejectCountsOtherReceipts() {
            order = po(Status.PARTIALLY_RECEIVED, "100", "5", false);
            postedElsewhere = 60;
            GoodsReceipt receipt = waiting(50);

            ReceiptView view = service.rejectExcess(receipt.id(), manager, "over");

            assertThat(view.lines()).extracting(l -> l.quantity()).containsExactly(45);
        }

        @Test
        @DisplayName("the excess comes off the lots of the PO line from the last one counted: 70 + 50 against 105 -> 70 + 35")
        void rejectCutsFromTheLastLot() {
            GoodsReceipt receipt = waiting(70, 50);

            ReceiptView view = service.rejectExcess(receipt.id(), manager, "over");

            assertThat(view.lines()).extracting(l -> l.lotNumber() + ":" + l.quantity()).containsExactly("L0:70", "L1:35");
        }

        @Test
        @DisplayName("a lot cut to nothing is removed: 100 + 20 against 105 -> 100 + 5; 105 + 20 -> only the first lot")
        void rejectRemovesALotCutToNothing() {
            GoodsReceipt receipt = waiting(105, 20);

            ReceiptView view = service.rejectExcess(receipt.id(), manager, "over");

            assertThat(view.lines()).extracting(l -> l.lotNumber() + ":" + l.quantity()).containsExactly("L0:105");
        }

        @Test
        @DisplayName("nothing within the tolerance left (105 already posted): the receipt is cancelled with the manager's reason")
        void rejectWithNothingLeftCancels() {
            order = po(Status.PARTIALLY_RECEIVED, "100", "5", false);
            postedElsewhere = 105;
            GoodsReceipt receipt = waiting(10);

            ReceiptView view = service.rejectExcess(receipt.id(), manager, "the order is full");

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
            assertThat(view.lines()).isEmpty();
            assertThat(view.approval().decision()).isEqualTo(ReceiptApproval.Decision.EXCESS_REJECTED);
            assertThat(view.approval().reason()).isEqualTo("the order is full");
            assertThat(view.confirmedAt()).isNull();
            verify(inventory, never()).receive(any());
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("a cut below the units labelled cartons hold is refused: approve, or cancel and count again")
        void rejectBelowTheCartonsIsRefused() {
            GoodsReceipt receipt = waiting(110);
            UUID lineId = receipt.lines().get(0).id();
            when(inventory.receivingPackagesOf(anyCollection())).thenReturn(Map.of(lineId, List.of(
                    new StockPackageView(UUID.randomUUID(), "LPN-1", lineId, "CUP-01", UUID.randomUUID(), "CARTON", 106, 106,
                            106, "HCM-RCV-01", "RECEIVING", true))));

            assertThat(code(() -> service.rejectExcess(receipt.id(), manager, "over")))
                    .isEqualTo(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE);
            verify(inventory, never()).receive(any());
        }

        @Test
        @DisplayName("a lot cut to nothing that labelled cartons were counted into cannot be removed")
        void rejectRemovingALabelledLotIsRefused() {
            GoodsReceipt receipt = waiting(105, 20);
            when(inventory.receiptLinesWithStockPackages(anyCollection()))
                    .thenReturn(Set.of(receipt.lines().get(1).id()));

            assertThat(code(() -> service.rejectExcess(receipt.id(), manager, "over")))
                    .isEqualTo(ErrorCode.RECEIPT_LINE_HAS_PACKAGES);
        }

        @Test
        @DisplayName("a receipt waiting for approval can be cancelled; its cartons are voided")
        void cancelWhileWaiting() {
            GoodsReceipt receipt = waiting(110);

            ReceiptView view = service.cancel(receipt.id());

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
            verify(inventory).voidStockPackagesOfReceiptLines(anyCollection());
            assertThat(code(() -> service.approve(receipt.id(), manager, "ok")))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("a subcontract order's approved receipt is handed to production and CLOSED, as its confirmation is")
        void approveOnASubcontractOrder() {
            order = po(Status.CONFIRMED, "100", "5", true);
            GoodsReceipt receipt = waiting(110);

            ReceiptView view = service.approve(receipt.id(), manager, "ok");

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        }
    }

    // ------------------------------------------------------------------ receipt without a PO (SCRUM-478, BR-RCP-002)

    @Nested
    class WithoutPurchaseOrder {

        private final UUID manager = UUID.randomUUID();
        private final UUID supplier = UUID.randomUUID();

        @BeforeEach
        void aKnownSupplierAndItem() {
            when(orders.supplierName(supplier)).thenReturn(Optional.of("ACME"));
            when(inventory.itemPolicy(new Sku("CUP-01"))).thenReturn(Optional.of(plainItem()));
        }

        private GoodsReceipt blind(ReceiptLine... lines) {
            GoodsReceipt receipt = GoodsReceipt.blindDraft(UUID.randomUUID(), "GR-20261022-0002", supplier, warehouse,
                    user, "sample from the supplier", null, null, NOW);
            receipt.replaceLines(List.of(lines));
            when(receipts.findById(receipt.id())).thenReturn(Optional.of(receipt));
            when(receipts.findByIdForUpdate(receipt.id())).thenReturn(Optional.of(receipt));
            return receipt;
        }

        private ReceiptLine item(int qty) {
            return ReceiptLine.counted(UUID.randomUUID(), null, itemId, receivingBin.id(), qty, null, null, null,
                    false, null);
        }

        private GoodsReceipts.CreateWithoutPo create(String reason) {
            return new GoodsReceipts.CreateWithoutPo(supplier, warehouse, reason, "  DN-1 ", null);
        }

        @Test
        @DisplayName("a draft is made for the supplier, with the reason; it names no purchase order")
        void createsDraft() {
            ReceiptView view = service.createWithoutPurchaseOrder(create("  urgent: parts for a customer  "), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
            assertThat(view.withoutPurchaseOrder()).isTrue();
            assertThat(view.purchaseOrderId()).isNull();
            assertThat(view.purchaseOrderNumber()).isNull();
            assertThat(view.supplierId()).isEqualTo(supplier);
            assertThat(view.blindReason()).isEqualTo("urgent: parts for a customer");
            assertThat(view.deliveryNote()).isEqualTo("DN-1");
            assertThat(view.warehouseId()).isEqualTo(warehouse);
        }

        @Test
        @DisplayName("the reason is required (BR-RCP-002), the supplier and the warehouse must exist")
        void createRules() {
            for (String blank : new String[]{null, "", "  "}) {
                assertThat(code(() -> service.createWithoutPurchaseOrder(create(blank), user)))
                        .isEqualTo(ErrorCode.BLIND_RECEIPT_REASON_REQUIRED);
            }
            when(orders.supplierName(supplier)).thenReturn(Optional.empty());
            assertThat(code(() -> service.createWithoutPurchaseOrder(create("gift"), user)))
                    .isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
            when(orders.supplierName(supplier)).thenReturn(Optional.of("ACME"));
            UUID elsewhere = UUID.randomUUID();
            when(warehouses.findWarehouse(elsewhere)).thenReturn(Optional.empty());
            assertThat(code(() -> service.createWithoutPurchaseOrder(
                    new GoodsReceipts.CreateWithoutPo(supplier, elsewhere, "gift", null, null), user)))
                    .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
            verify(receipts, never()).save(any(GoodsReceipt.class));
        }

        @Test
        @DisplayName("lines name the item by SKU; the lot, expiry and area rules of a normal count apply")
        void countsBySku() {
            GoodsReceipt receipt = blind();

            ReceiptView view = service.replaceLines(receipt.id(), List.of(
                    new NewLine(null, 7, null, null, "HCM-RCV-01", "gift", null, null, " cup-01 ")));

            assertThat(view.lines()).hasSize(1);
            assertThat(view.lines().get(0).sku()).isEqualTo("CUP-01");
            assertThat(view.lines().get(0).purchaseOrderLineId()).isNull();
            assertThat(view.lines().get(0).quantity()).isEqualTo(7);
            assertThat(view.warnings()).isEmpty();

            BusinessException wrongArea = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(null, 7, null, null, "HCM-QC-01", null, null, null, "CUP-01"))));
            assertThat(wrongArea.fieldErrors()).extracting(FieldError::field).containsExactly("lines[0].locationCode");
        }

        @Test
        @DisplayName("each refusal is on its own field: no SKU, an unknown SKU, a PO line, the same item and lot twice")
        void countRefusals() {
            GoodsReceipt receipt = blind();
            when(inventory.itemPolicy(new Sku("NOPE"))).thenReturn(Optional.empty());

            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(null, 1, null, null, "HCM-RCV-01", null, null, null, null),
                    new NewLine(null, 1, null, null, "HCM-RCV-01", null, null, null, "NOPE"),
                    new NewLine(poLineId, 1, null, null, "HCM-RCV-01", null, null, null, "CUP-01"),
                    new NewLine(null, 1, null, null, "HCM-RCV-01", null, null, null, "CUP-01"),
                    new NewLine(null, 2, null, null, "HCM-RCV-01", null, null, null, "cup-01"))));

            assertThat(e.fieldErrors()).extracting(f -> f.field() + ":" + f.code()).containsExactly(
                    "lines[0].sku:VALIDATION_FAILED",
                    "lines[1].sku:INVENTORY_ITEM_NOT_FOUND",
                    "lines[2].purchaseOrderLineId:RECEIPT_LINE_NOT_ON_PO",
                    "lines[4].lotNumber:DUPLICATE_RECEIPT_LINE");
            assertThat(receipt.lines()).as("nothing was saved").isEmpty();
        }

        @Test
        @DisplayName("a lot-tracked item still needs its lot (BR-03), and a line of a receipt against a PO still needs its PO line")
        void lotRulesAndPoLines() {
            InventoryItemPolicy tracked = new InventoryItemPolicy(itemId, "CUP-01", true, false, false);
            when(inventory.itemPolicy(new Sku("CUP-01"))).thenReturn(Optional.of(tracked));
            GoodsReceipt receipt = blind();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(null, 1, null, null, "HCM-RCV-01", null, null, null, "CUP-01"))));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.RECEIPT_LOT_DATA_INVALID);

            po(Status.CONFIRMED, "10", null, false);
            GoodsReceipt withPo = draft();
            BusinessException bySku = thrown(() -> service.replaceLines(withPo.id(), List.of(
                    new NewLine(null, 1, null, null, "HCM-RCV-01", null, null, null, "CUP-01"))));
            assertThat(bySku.errorCode()).isEqualTo(ErrorCode.RECEIPT_LINE_NOT_ON_PO);
        }

        @Test
        @DisplayName("confirming never posts: the receipt waits for the manager, nothing is counted in, no order is touched")
        void confirmAlwaysWaits() {
            GoodsReceipt receipt = blind(item(5));

            ReceiptView view = service.confirm(receipt.id(), user);

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
            assertThat(view.approval().requestedBy()).isEqualTo(user);
            assertThat(view.confirmedAt()).isNull();
            verify(inventory, never()).receive(any());
            verify(orders, never()).lock(any());
            verify(orders, never()).recordProgress(any(), any(), any(), any(), any(), any());
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("an empty count cannot be sent for approval")
        void emptyCount() {
            GoodsReceipt receipt = blind();
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("the manager approves: stock is counted in only now, under the supplier, with no order behind it")
        void approvePosts() {
            GoodsReceipt receipt = blind(item(5));
            service.confirm(receipt.id(), user);

            ReceiptView view = service.approve(receipt.id(), manager, "samples, checked at the gate");

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
            assertThat(view.approval().decision()).isEqualTo(ReceiptApproval.Decision.APPROVED);
            assertThat(view.confirmedBy()).isEqualTo(manager);
            ArgumentCaptor<ReceiveStockCommand> receive = ArgumentCaptor.forClass(ReceiveStockCommand.class);
            verify(inventory).receive(receive.capture());
            assertThat(receive.getValue().quantity()).isEqualTo(5);
            assertThat(receive.getValue().actorId()).isEqualTo(manager);
            assertThat(receive.getValue().origin().purchaseOrderId()).isNull();
            assertThat(receive.getValue().origin().supplierId()).isEqualTo(supplier);
            assertThat(receive.getValue().origin().supplierName()).isEqualTo("ACME");
            ArgumentCaptor<GoodsReceiptConfirmed> confirmed = ArgumentCaptor.forClass(GoodsReceiptConfirmed.class);
            verify(events).publishEvent(confirmed.capture());
            assertThat(confirmed.getValue().purchaseOrderId()).isNull();
            assertThat(confirmed.getValue().lines().get(0).purchaseOrderLineId()).isNull();
            verify(events).publishEvent(any(ReceiptStockReadyForPutaway.class));
            verify(orders, never()).recordProgress(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("four eyes and a reason hold: the one who counted, the one who sent it and a blank reason are refused")
        void decisionRules() {
            GoodsReceipt receipt = blind(item(5));
            UUID sender = UUID.randomUUID();
            service.confirm(receipt.id(), sender);

            assertThat(code(() -> service.approve(receipt.id(), user, "ok"))).isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.approve(receipt.id(), sender, "ok"))).isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.reject(receipt.id(), user, "no"))).isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.reject(receipt.id(), sender, "no"))).isEqualTo(ErrorCode.RECEIPT_SELF_APPROVAL);
            assertThat(code(() -> service.approve(receipt.id(), manager, " ")))
                    .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
            assertThat(code(() -> service.reject(receipt.id(), manager, null)))
                    .isEqualTo(ErrorCode.RECEIPT_APPROVAL_REASON_REQUIRED);
            verify(inventory, never()).receive(any());
        }

        @Test
        @DisplayName("rejecting cancels the whole receipt with the reason and the decider; its cartons are voided")
        void rejectCancels() {
            GoodsReceipt receipt = blind(item(5));
            service.confirm(receipt.id(), user);

            ReceiptView view = service.reject(receipt.id(), manager, "not ours");

            assertThat(view.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
            assertThat(view.approval().decision()).isEqualTo(ReceiptApproval.Decision.REJECTED);
            assertThat(view.approval().reason()).isEqualTo("not ours");
            assertThat(view.approval().decidedBy()).isEqualTo(manager);
            assertThat(view.confirmedAt()).isNull();
            verify(inventory).voidStockPackagesOfReceiptLines(anyCollection());
            verify(inventory, never()).receive(any());
            assertThat(code(() -> service.approve(receipt.id(), manager, "late")))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("rejecting only the excess is for receipts against a PO; rejecting the receipt is for those without")
        void rejectionsBelongToTheirKind() {
            GoodsReceipt receipt = blind(item(5));
            service.confirm(receipt.id(), user);
            assertThat(code(() -> service.rejectExcess(receipt.id(), manager, "x")))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);

            po(Status.CONFIRMED, "100", "5", false);
            GoodsReceipt withPo = draft(counted(110, false));
            service.confirm(withPo.id(), user);
            assertThat(code(() -> service.reject(withPo.id(), manager, "x")))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }

        @Test
        @DisplayName("a waiting receipt can be cancelled by whoever counted it, like a draft")
        void cancelWhileWaiting() {
            GoodsReceipt receipt = blind(item(5));
            service.confirm(receipt.id(), user);
            assertThat(service.cancel(receipt.id()).status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        }

        @Test
        @DisplayName("the PO is attached as a reference only: stored, not locked, not written, nothing counted against it")
        void referenceOnly() {
            ReceivingPurchaseOrder order = new ReceivingPurchaseOrder(poId, "PO-9", Status.CONFIRMED, supplier,
                    warehouse, UUID.randomUUID(), null, false, List.of());
            when(orders.find(poId)).thenReturn(Optional.of(order));
            GoodsReceipt receipt = blind(item(5));

            ReceiptView view = service.referencePurchaseOrder(receipt.id(), poId);

            assertThat(view.referencedPurchaseOrderId()).isEqualTo(poId);
            assertThat(receipt.referencedPurchaseOrderId()).isEqualTo(poId);
            assertThat(view.purchaseOrderId()).as("it is still against no order").isNull();
            verify(orders, never()).lock(any());
            verify(orders, never()).recordProgress(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the PO must exist and be the supplier's; a receipt against a PO or a cancelled one takes none")
        void referenceRules() {
            GoodsReceipt receipt = blind(item(5));
            UUID missing = UUID.randomUUID();
            when(orders.find(missing)).thenReturn(Optional.empty());
            assertThat(code(() -> service.referencePurchaseOrder(receipt.id(), missing)))
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);

            ReceivingPurchaseOrder other = new ReceivingPurchaseOrder(poId, "PO-9", Status.CONFIRMED,
                    UUID.randomUUID(), warehouse, UUID.randomUUID(), null, false, List.of());
            when(orders.find(poId)).thenReturn(Optional.of(other));
            assertThat(code(() -> service.referencePurchaseOrder(receipt.id(), poId)))
                    .isEqualTo(ErrorCode.RECEIPT_REFERENCE_SUPPLIER_MISMATCH);
            assertThat(receipt.referencedPurchaseOrderId()).isNull();

            po(Status.CONFIRMED, "10", null, false);
            GoodsReceipt withPo = draft(counted(1, false));
            assertThat(code(() -> service.referencePurchaseOrder(withPo.id(), poId)))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

            ReceivingPurchaseOrder same = new ReceivingPurchaseOrder(poId, "PO-9", Status.CONFIRMED, supplier,
                    warehouse, UUID.randomUUID(), null, false, List.of());
            when(orders.find(poId)).thenReturn(Optional.of(same));
            service.cancel(receipt.id());
            assertThat(code(() -> service.referencePurchaseOrder(receipt.id(), poId)))
                    .isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        }
    }

    // ------------------------------------------------------------------ reads

    @Nested
    class Reads {

        @Test
        @DisplayName("BR-06: a lot expiring within the warning window of the receiving day is flagged EXPIRY_NEAR, never refused")
        void expiryNearWarning() {
            InventoryItemPolicy policy = new InventoryItemPolicy(itemId, "CUP-01", true, true, false);
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(policy));
            when(inventory.itemPolicies(anyCollection())).thenAnswer(call -> Map.of(itemId, policy));
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft();

            ReceiptView view = service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 1, "NEAR", TODAY.plusDays(30), "HCM-RCV-01", null),
                    new NewLine(poLineId, 1, "FAR", TODAY.plusDays(31), "HCM-RCV-01", null)));

            assertThat(view.lines().get(0).warnings()).containsExactly("EXPIRY_NEAR");
            assertThat(view.lines().get(1).warnings()).isEmpty();
            assertThat(view.warnings()).containsExactly("EXPIRY_NEAR");
        }

        @Test
        @DisplayName("a cancelled receipt counted nothing: it flags nothing")
        void cancelledFlagsNothing() {
            InventoryItemPolicy policy = new InventoryItemPolicy(itemId, "CUP-01", true, true, false);
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(policy));
            when(inventory.itemPolicies(anyCollection())).thenAnswer(call -> Map.of(itemId, policy));
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), 1,
                    "NEAR", TODAY.plusDays(2), null, false, null));
            ReceiptView view = service.cancel(receipt.id());
            assertThat(view.warnings()).isEmpty();
        }

        @Test
        @DisplayName("get: an unknown receipt is GOODS_RECEIPT_NOT_FOUND; a receipt whose order is gone is a state error")
        void get() {
            UUID missing = UUID.randomUUID();
            when(receipts.findById(missing)).thenReturn(Optional.empty());
            assertThat(code(() -> service.get(missing))).isEqualTo(ErrorCode.GOODS_RECEIPT_NOT_FOUND);

            GoodsReceipt orphan = draft();
            when(orders.find(poId)).thenReturn(Optional.empty());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(orphan.id()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
