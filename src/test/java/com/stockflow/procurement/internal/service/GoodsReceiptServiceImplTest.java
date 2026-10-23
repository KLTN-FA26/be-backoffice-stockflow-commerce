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
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
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
    private final IdentityService identity = mock(IdentityService.class);
    private final InventoryControlService inventoryControl = mock(InventoryControlService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
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
        return new GoodsReceiptServiceImpl(receipts, search, orders, locations, inventory, warehouses, identity,
                inventoryControl, events, Clock.fixed(now, ZoneOffset.UTC));
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
        return receipt;
    }

    private ReceiptLine counted(int qty, boolean qc) {
        return ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), qty, null, null, null, qc);
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
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(new InventoryItemPolicy(itemId, "CUP-01", false,
                    false, true)));
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
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(new InventoryItemPolicy(itemId, "CUP-01", lot,
                    expiry, false)));
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

        // ---- BR-02 tolerance

        @Test
        @DisplayName("BR-02: ordered 100 with 5% tolerance receives up to 105 in all: 105 is fine, 106 is OVER_RECEIPT_TOLERANCE")
        void toleranceBoundary() {
            po(Status.CONFIRMED, "100", "5", false);
            GoodsReceipt receipt = draft();
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(105))).lines()).hasSize(1);
            assertThat(code(() -> service.replaceLines(receipt.id(), List.of(newLine(106)))))
                    .isEqualTo(ErrorCode.OVER_RECEIPT_TOLERANCE);
        }

        @Test
        @DisplayName("BR-02: the limit is rounded down (10 ordered, 2.5% -> 10, not 11) and no tolerance means no excess")
        void toleranceRoundsDown() {
            po(Status.CONFIRMED, "10", "2.5", false);
            GoodsReceipt receipt = draft();
            assertThat(code(() -> service.replaceLines(receipt.id(), List.of(newLine(11)))))
                    .isEqualTo(ErrorCode.OVER_RECEIPT_TOLERANCE);
            po(Status.CONFIRMED, "10", null, false);
            assertThat(code(() -> service.replaceLines(receipt.id(), List.of(newLine(11)))))
                    .isEqualTo(ErrorCode.OVER_RECEIPT_TOLERANCE);
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(10))).lines()).hasSize(1);
        }

        @Test
        @DisplayName("BR-02: units already on other (non-cancelled) receipts count against the limit; the tolerance is "
                + "reported on the quantity field of the line")
        void toleranceCountsOtherReceipts() {
            po(Status.PARTIALLY_RECEIVED, "100", "0", false);
            GoodsReceipt receipt = draft();
            when(receipts.receivedOnOtherReceipts(anyIterable(), eq(receipt.id()))).thenReturn(Map.of(poLineId, 60));

            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(newLine(41))));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.OVER_RECEIPT_TOLERANCE);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("lines[0].quantity");
            assertThat(service.replaceLines(receipt.id(), List.of(newLine(40))).lines()).hasSize(1);
        }

        @Test
        @DisplayName("BR-02: two lots of one PO line are summed against the limit, and both lines are marked")
        void toleranceSumsLots() {
            tracked(true, false);
            po(Status.CONFIRMED, "10", null, false);
            GoodsReceipt receipt = draft();
            BusinessException e = thrown(() -> service.replaceLines(receipt.id(), List.of(
                    new NewLine(poLineId, 6, "L1", null, "HCM-RCV-01", null),
                    new NewLine(poLineId, 6, "L2", null, "HCM-RCV-01", null))));
            assertThat(e.fieldErrors()).extracting(FieldError::field)
                    .containsExactly("lines[0].quantity", "lines[1].quantity");
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
        @DisplayName("BR-02 is asked again at confirmation (another receipt may have been confirmed meanwhile)")
        void toleranceAtConfirm() {
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(counted(50, false));
            when(receipts.receivedOnOtherReceipts(anyIterable(), eq(receipt.id()))).thenReturn(Map.of(poLineId, 60));
            assertThat(code(() -> service.confirm(receipt.id(), user))).isEqualTo(ErrorCode.OVER_RECEIPT_TOLERANCE);
            assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
            verify(inventory, never()).receive(any());
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
                    null, true);
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
                    null, null, true);
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

    // ------------------------------------------------------------------ reads

    @Nested
    class Reads {

        @Test
        @DisplayName("BR-06: a lot expiring within the warning window of the receiving day is flagged EXPIRY_NEAR, never refused")
        void expiryNearWarning() {
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(new InventoryItemPolicy(itemId, "CUP-01", true,
                    true, false)));
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
            when(inventory.itemPolicy(itemId)).thenReturn(Optional.of(new InventoryItemPolicy(itemId, "CUP-01", true,
                    true, false)));
            po(Status.CONFIRMED, "100", null, false);
            GoodsReceipt receipt = draft(ReceiptLine.counted(UUID.randomUUID(), poLineId, itemId, receivingBin.id(), 1,
                    "NEAR", TODAY.plusDays(2), null, false));
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
