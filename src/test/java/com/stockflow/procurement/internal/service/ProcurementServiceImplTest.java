package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
import com.stockflow.procurement.api.SendPurchaseOrderCommand;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.CloseKind;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PoLineStatus;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.repository.ApprovalLimitRepository;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import com.stockflow.procurement.internal.repository.ProcurementReportRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs warehouse/02: the purchase order's workflow and its guards at the service boundary - BR-01 (active SKU
 * the supplier lists), BR-PO-001 (catalog terms), four-eyes approval, BR-05 (cancel), short-close.
 */
class ProcurementServiceImplTest {

    // 03:00Z = 10:00 in Saigon on 2026-10-22
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderSearchRepository search = mock(PurchaseOrderSearchRepository.class);
    private final ProcurementReportRepository reports = mock(ProcurementReportRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PoDeliveryDecisionRepository deliveryDecisions = mock(PoDeliveryDecisionRepository.class);
    private final ProductService products = mock(ProductService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final InventoryControlService inventoryControl = mock(InventoryControlService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final SupplierItemRepository supplierItems = mock(SupplierItemRepository.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ApprovalLimitRepository limitRows = mock(ApprovalLimitRepository.class);
    /** The real decision rules over mocked stores; {@code limitOf} is stubbed, see {@link #limit}. */
    private final ApprovalLimits limits = spy(new ApprovalLimits(identity, limitRows, orders));
    private final ProcurementServiceImpl service = new ProcurementServiceImpl(orders, search, reports,
            Clock.fixed(NOW, ZoneOffset.UTC), suppliers, events, notifications, deliveryDecisions,
            new PoDeliveryContent(suppliers, notifications, new PoCommunicationProfile("Buyer", "Address", "Contact",
                    "0901234567", "buyer@example.com", "Warehouse"), products),
            products, inventory, inventoryControl, warehouses, mock(CurrentUserProvider.class), supplierItems, limits, org.mockito.Mockito.mock(com.stockflow.inventory.api.UomConversionService.class));

    private final UUID supplierId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID submitter = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    private Supplier supplier(SupplierStatus status, SupplierCommunicationChannel channel, String email) {
        return new Supplier(supplierId, new SupplierDetails("SUP", "Supplier", null, email, null, null, status, 30, 7,
                channel, channel == SupplierCommunicationChannel.API ? "https://api.example.com/po" : null, null, false,
                null), null, null);
    }

    private void listed(int moq, int pack, Integer leadTime, String catalogPrice) {
        when(supplierItems.listedForShare(eq(supplierId), anyCollection())).thenReturn(Map.of(itemId,
                new SupplierItem(UUID.randomUUID(), supplierId, itemId,
                        SupplierItemTerms.stored(null, Money.vnd(Long.parseLong(catalogPrice)), moq, pack, leadTime), 0,
                        null, null)));
    }

    @BeforeEach
    void defaults() {
        when(suppliers.findByIdForUpdate(supplierId))
                .thenReturn(Optional.of(supplier(SupplierStatus.ACTIVE, SupplierCommunicationChannel.EMAIL, "s@x.vn")));
        when(suppliers.findById(supplierId))
                .thenReturn(Optional.of(supplier(SupplierStatus.ACTIVE, SupplierCommunicationChannel.EMAIL, "s@x.vn")));
        when(warehouses.findWarehouse(warehouseId))
                .thenReturn(Optional.of(new WarehouseView(warehouseId, "HCM", "Kho HCM", "addr", true)));
        when(inventory.itemPolicy(any(Sku.class)))
                .thenReturn(Optional.of(new InventoryItemPolicy(itemId, "CUP-01", false, false, false)));
        when(inventoryControl.item(any())).thenReturn(new InventoryControlItem("CUP-01", "EACH", 0, null));
        when(products.nameForSku(anyString())).thenReturn(Optional.of("Paper cup"));
        when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PURCHASABLE));
        when(supplierItems.findSupplier(supplierId)).thenReturn(Optional.of(
                new CatalogSupplier(supplierId, "SUP", "Supplier", SupplierStatus.ACTIVE, Money.VND, 7)));
        listed(1, 1, null, "1000");
        // Nobody has an approval limit unless a test gives one: every submit waits, no approval is allowed.
        doReturn(Optional.empty()).when(limits).limitOf(any(), any(), any());
        when(orders.autoApprovedTotal(any(), any(), any(), any(), any())).thenReturn(Money.zero(Money.VND));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        when(orders.nextPoNumber(any())).thenReturn("PO-20261022-0001");
        when(orders.nextRevisionNo(any())).thenReturn(0L);
        when(orders.recordRevision(any(), anyLong(), any(), any())).thenReturn(UUID.randomUUID());
        when(search.summary(any(), any(Boolean.class))).thenReturn(Optional.of(mock(PurchaseOrderSummary.class)));
        when(notifications.prepareSupplierDelivery(any(), any(Boolean.class))).thenReturn(1);
    }

    private static BusinessException refused(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).isNotNull();
        return thrown;
    }

    private CreatePurchaseOrderCommand create(int quantity, BigDecimal price) {
        return new CreatePurchaseOrderCommand(supplierId, warehouseId, "VND", null, null,
                List.of(new CreatePOLineCommand("CUP-01", null, quantity, price)));
    }

    private PurchaseOrder savedOrder() {
        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders).save(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ creation

    @Nested
    class Creation {

        @Test
        @DisplayName("a priced line is stored as typed (a negotiated price wins), with tax defaulting to zero; the draft "
                + "is born DRAFT and CREATED goes on its timeline")
        void createsDraft() {
            service.createPurchaseOrder(create(10, new BigDecimal("900")));

            PurchaseOrder saved = savedOrder();
            assertThat(saved.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(saved.lines().get(0).unitPrice().amount()).isEqualByComparingTo("900");
            assertThat(saved.totalAmount().amount()).isEqualByComparingTo("9000");
            assertThat(saved.paymentTermDays()).isEqualTo(30);
            verify(orders).recordEvent(eq(saved.id()), isNull(), eq("CREATED"), any(), isNull(),
                    eq(PurchaseOrderStatus.DRAFT), isNull());
        }

        @Test
        @DisplayName("BR-PO-001: a line without a price takes the supplier's catalog price")
        void catalogPrice() {
            service.createPurchaseOrder(create(10, null));
            assertThat(savedOrder().lines().get(0).unitPrice().amount()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("the order's lead time is its slowest line's: the item's own lead time wins over the supplier's 7 days")
        void leadTimeFromLines() {
            listed(1, 1, 21, "1000");
            service.createPurchaseOrder(create(1, null));
            PurchaseOrder saved = savedOrder();
            assertThat(saved.leadTimeDays()).isEqualTo(21);
            assertThat(saved.expectedAt()).isEqualTo(TODAY.plusDays(21));
        }

        @Test
        @DisplayName("an unknown supplier is SUPPLIER_NOT_FOUND, an inactive one SUPPLIER_INACTIVE")
        void supplierGuards() {
            UUID missing = UUID.randomUUID();
            when(suppliers.findByIdForUpdate(missing)).thenReturn(Optional.empty());
            assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(missing, warehouseId,
                    "VND", null, null, List.of()))).errorCode()).isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);

            when(suppliers.findByIdForUpdate(supplierId)).thenReturn(Optional.of(
                    supplier(SupplierStatus.INACTIVE, SupplierCommunicationChannel.EMAIL, "s@x.vn")));
            assertThat(refused(() -> service.createPurchaseOrder(create(1, null))).errorCode())
                    .isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
            verify(orders, never()).save(any());
        }

        @Test
        @DisplayName("SCRUM-390: the receiving warehouse must exist (WAREHOUSE_NOT_FOUND) and be ACTIVE (WAREHOUSE_INACTIVE)")
        void warehouseGuards() {
            UUID missing = UUID.randomUUID();
            when(warehouses.findWarehouse(missing)).thenReturn(Optional.empty());
            assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, missing,
                    "VND", null, null, List.of()))).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);

            when(warehouses.findWarehouse(warehouseId)).thenReturn(
                    Optional.of(new WarehouseView(warehouseId, "HCM", "Kho HCM", "addr", false)));
            assertThat(refused(() -> service.createPurchaseOrder(create(1, null))).errorCode())
                    .isEqualTo(ErrorCode.WAREHOUSE_INACTIVE);
        }

        @Test
        @DisplayName("an unknown or pseudo currency, an empty line list and a note over 2000 characters are VALIDATION_FAILED")
        void inputGuards() {
            for (String currency : new String[] {"ZZZ", "XXX", null}) {
                assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId,
                        warehouseId, currency, null, null, List.of(new CreatePOLineCommand("CUP-01", null, 1, BigDecimal.TEN)))))
                        .errorCode()).as(String.valueOf(currency)).isEqualTo(ErrorCode.VALIDATION_FAILED);
            }
            assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, warehouseId,
                    "VND", null, null, List.of()))).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, warehouseId,
                    "VND", null, "x".repeat(2001), List.of(new CreatePOLineCommand("CUP-01", null, 1, BigDecimal.TEN)))))
                    .errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("BR-01: a SKU with no inventory item is INVENTORY_ITEM_NOT_FOUND; the same SKU twice is refused")
        void skuGuards() {
            when(inventory.itemPolicy(new Sku("NOPE-01"))).thenReturn(Optional.empty());
            assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, warehouseId,
                    "VND", null, null, List.of(new CreatePOLineCommand("NOPE-01", null, 1, BigDecimal.TEN)))))
                    .errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);

            assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, warehouseId,
                    "VND", null, null, List.of(new CreatePOLineCommand("CUP-01", null, 1, BigDecimal.TEN),
                    new CreatePOLineCommand("CUP-01", null, 2, BigDecimal.TEN))))).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a line with neither a description nor a product name is PO_LINE_DESCRIPTION_REQUIRED")
        void descriptionRequired() {
            when(products.nameForSku(anyString())).thenReturn(Optional.empty());
            assertThat(refused(() -> service.createPurchaseOrder(create(1, null))).errorCode())
                    .isEqualTo(ErrorCode.PO_LINE_DESCRIPTION_REQUIRED);
        }

        @Test
        @DisplayName("BR-PO-001: a SKU the supplier does not list is SUPPLIER_TERMS_VIOLATED on lines[i].sku")
        void notListed() {
            when(supplierItems.listedForShare(eq(supplierId), anyCollection())).thenReturn(Map.of());
            BusinessException e = refused(() -> service.createPurchaseOrder(create(1, null)));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SUPPLIER_TERMS_VIOLATED);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("lines[0].sku");
            assertThat(e.fieldErrors()).extracting(FieldError::code).containsExactly("SUPPLIER_ITEM_NOT_LISTED");
        }

        @Test
        @DisplayName("BR-09 / BR-PO-001: below the MOQ and off the pack multiple are both reported on quantityOrdered")
        void moqAndPack() {
            listed(100, 50, null, "1000");
            BusinessException e = refused(() -> service.createPurchaseOrder(create(30, null)));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SUPPLIER_TERMS_VIOLATED);
            assertThat(e.fieldErrors()).extracting(FieldError::code)
                    .containsExactly("PO_LINE_BELOW_MOQ", "PO_LINE_NOT_PACK_MULTIPLE");

            service.createPurchaseOrder(create(150, null));
            assertThat(savedOrder().lines().get(0).quantityOrdered()).isEqualTo(150);
        }

        @Test
        @DisplayName("amounts are bounded: a quantity over 1,000,000 (and 0, below any MOQ), a negative tax and a tax over 100% are refused")
        void amountBounds() {
            assertThat(refused(() -> service.createPurchaseOrder(create(1_000_001, BigDecimal.TEN))).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            BusinessException zero = refused(() -> service.createPurchaseOrder(create(0, BigDecimal.TEN)));
            assertThat(zero.errorCode()).as("a zero quantity is below any MOQ, caught by the catalog terms first")
                    .isEqualTo(ErrorCode.SUPPLIER_TERMS_VIOLATED);
            assertThat(zero.fieldErrors()).extracting(FieldError::code).contains("PO_LINE_BELOW_MOQ");
            for (String tax : new String[] {"-1", "100.01"}) {
                assertThat(refused(() -> service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId,
                        warehouseId, "VND", null, null, List.of(new CreatePOLineCommand("CUP-01", null, 1,
                        BigDecimal.TEN, new BigDecimal(tax)))))).errorCode()).as("tax " + tax)
                        .isEqualTo(ErrorCode.VALIDATION_FAILED);
            }
        }

        @Test
        @DisplayName("a VND price with decimals is refused (VND has no minor unit)")
        void vndHasNoDecimals() {
            assertThat(refused(() -> service.createPurchaseOrder(create(1, new BigDecimal("10.5")))).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("01 BR-04 / 02 BR-01: a SKU that is no longer active for purchasing is refused on its own line, "
                + "with the reason")
        void notPurchasable() {
            when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PRODUCT_INACTIVE));
            BusinessException e = refused(() -> service.createPurchaseOrder(create(1, null)));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("lines[0].sku");
            assertThat(e.fieldErrors()).extracting(FieldError::code).containsExactly("PRODUCT_INACTIVE");
        }

        @Test
        @DisplayName("BR-PO-003: another open order to the same supplier, same SKU and delivery date flags a possible duplicate")
        void possibleDuplicate() {
            service.createPurchaseOrder(create(1, null));
            PurchaseOrder first = savedOrder();
            when(orders.findOpenBySupplierAndExpectedAt(eq(supplierId), any())).thenReturn(List.of(first));
            service.createPurchaseOrder(create(2, null));
            verify(search).summary(any(PurchaseOrderId.class), eq(true));
        }
    }

    // ------------------------------------------------------------------ approval

    @Nested
    class Approval {

        private PurchaseOrder draft() {
            PurchaseOrder po = PurchaseOrder.draft("PO-1", supplierId, warehouseId, Money.VND,
                    List.of(PurchaseOrder.line(1, itemId, new Sku("CUP-01"), "EACH", "Cup", 10, Money.vnd(1000), null)),
                    TODAY, TODAY.plusDays(7), null, 30, 7);
            when(orders.findByIdForUpdate(po.id())).thenReturn(Optional.of(po));
            return po;
        }

        private PurchaseOrder pending() {
            PurchaseOrder po = draft();
            po.submit(submitter, UUID.randomUUID(), 0, NOW);
            return po;
        }

        private PurchaseOrder approved() {
            PurchaseOrder po = pending();
            po.approve(approver, Money.vnd(1_000_000_000L), NOW);
            return po;
        }

        /** The approval limit {@code user} holds today (none configured for everyone else, see {@code defaults}). */
        private ApprovalLimit limit(UUID user, long vnd) {
            ApprovalLimit limit = new ApprovalLimit(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(vnd));
            doReturn(Optional.of(limit)).when(limits).limitOf(eq(user), any(), eq(TODAY));
            return limit;
        }

        @Test
        @DisplayName("an unknown purchase order is PURCHASE_ORDER_NOT_FOUND on every transition")
        void unknown() {
            UUID missing = UUID.randomUUID();
            when(orders.findByIdForUpdate(new PurchaseOrderId(missing))).thenReturn(Optional.empty());
            assertThat(refused(() -> service.submit(missing, submitter)).errorCode())
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
            assertThat(refused(() -> service.approve(missing, approver)).errorCode())
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
            assertThat(refused(() -> service.cancel(missing, approver, "x")).errorCode())
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("submit: DRAFT -> PENDING_APPROVAL; the revision freezes what is approved and SUBMITTED is logged")
        void submit() {
            PurchaseOrder po = draft();
            UUID revision = UUID.randomUUID();
            when(orders.recordRevision(any(), eq(0L), eq(submitter), isNull())).thenReturn(revision);

            service.submit(po.id().value(), submitter);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(po.pendingRevisionId()).isEqualTo(revision);
            verify(orders).recordEvent(po.id(), revision, "SUBMITTED", submitter, PurchaseOrderStatus.DRAFT,
                    PurchaseOrderStatus.PENDING_APPROVAL, null);
        }

        @Test
        @DisplayName("a resubmission after a rejection is the next revision, noted as such")
        void resubmit() {
            PurchaseOrder po = draft();
            when(orders.nextRevisionNo(po.id())).thenReturn(1L);
            service.submit(po.id().value(), submitter);
            verify(orders).recordRevision(po, 1L, submitter, "Resubmitted after rejection");
        }

        @Test
        @DisplayName("submit: only a draft; nothing is written for a refused submit (the revision is append-only)")
        void submitOnlyDraft() {
            PurchaseOrder po = pending();
            assertThat(refused(() -> service.submit(po.id().value(), submitter)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
            verify(orders, never()).recordRevision(any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("submit: a SKU discontinued since the draft was written is refused on its line")
        void submitChecksPurchasability() {
            PurchaseOrder po = draft();
            when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PRODUCT_DISCONTINUED));
            BusinessException e = refused(() -> service.submit(po.id().value(), submitter));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            verify(orders, never()).recordRevision(any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("BR-PO-002 four-eyes: the submitter cannot approve; another approver can, APPROVED goes on the timeline "
                + "and the approval is recorded against the revision")
        void fourEyes() {
            PurchaseOrder po = pending();
            limit(submitter, 1_000_000);
            limit(approver, 1_000_000);
            assertThat(refused(() -> service.approve(po.id().value(), submitter)).errorCode())
                    .isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED);
            verify(orders, never()).recordApproval(any(), any(), anyString(), any());

            service.approve(po.id().value(), approver);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.approvedBy()).isEqualTo(approver);
            verify(orders).recordApproval(po.activeRevisionId(), approver, "APPROVED", null);
            verify(orders).recordEvent(po.id(), po.activeRevisionId(), "APPROVED", approver,
                    PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.APPROVED, null);
        }

        @Test
        @DisplayName("approve: a draft is not approved (INVALID_PURCHASE_ORDER_TRANSITION)")
        void approveDraft() {
            PurchaseOrder po = draft();
            limit(approver, 1_000_000);   // however big the limit, a draft is approved only by the under-limit submit
            assertThat(refused(() -> service.approve(po.id().value(), approver)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
        }

        @Test
        @DisplayName("approve: a SKU discontinued while the order waited is refused at approval")
        void approveChecksPurchasability() {
            PurchaseOrder po = pending();
            when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PRODUCT_DISCONTINUED));
            assertThat(refused(() -> service.approve(po.id().value(), approver)).errorCode())
                    .isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
        }

        @Test
        @DisplayName("reject: back to DRAFT with the trimmed reason recorded against the pending revision; a blank reason is refused")
        void reject() {
            PurchaseOrder po = pending();
            UUID revision = po.pendingRevisionId();
            assertThat(refused(() -> service.reject(po.id().value(), approver, "  ")).errorCode())
                    .isEqualTo(ErrorCode.PO_REASON_REQUIRED);

            service.reject(po.id().value(), approver, " price too high ");

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            verify(orders).recordApproval(revision, approver, "REJECTED", "price too high");
            verify(orders).recordEvent(eq(po.id()), eq(revision), eq("REJECTED"), eq(approver),
                    eq(PurchaseOrderStatus.PENDING_APPROVAL), eq(PurchaseOrderStatus.DRAFT), anyString());
        }

        // ---- confirm / send

        @Test
        @DisplayName("confirm: APPROVED -> CONFIRMED and the order goes to the supplier as PurchaseOrderSent with its "
                + "total, currency, lines and the buyer profile")
        void confirm() {
            PurchaseOrder po = approved();

            service.confirm(po.id().value(), approver, new SendPurchaseOrderCommand(null, null));

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
            ArgumentCaptor<PurchaseOrderSent> sent = ArgumentCaptor.forClass(PurchaseOrderSent.class);
            verify(events).publishEvent(sent.capture());
            assertThat(sent.getValue().totalAmount()).isEqualByComparingTo("10000");
            assertThat(sent.getValue().currency()).isEqualTo("VND");
            assertThat(sent.getValue().recipient()).isEqualTo("s@x.vn");
            assertThat(sent.getValue().channel()).isEqualTo("EMAIL");
            assertThat(sent.getValue().lines()).hasSize(1);
            assertThat(sent.getValue().cancellation()).isFalse();
            verify(notifications).validateSupplierDelivery("EMAIL", "s@x.vn");
        }

        @Test
        @DisplayName("confirm: a supplier that went inactive, or has no delivery contact, blocks sending: CONFLICT, status unchanged "
                + "in the events")
        void confirmNeedsAReachableSupplier() {
            PurchaseOrder po = approved();
            when(suppliers.findById(supplierId)).thenReturn(Optional.of(
                    supplier(SupplierStatus.INACTIVE, SupplierCommunicationChannel.EMAIL, "s@x.vn")));
            assertThat(refused(() -> service.confirm(po.id().value(), approver, new SendPurchaseOrderCommand(null, null)))
                    .errorCode()).isEqualTo(ErrorCode.CONFLICT);
            verify(events, never()).publishEvent(any(PurchaseOrderSent.class));
        }

        @Test
        @DisplayName("confirm: an API supplier is sent to its endpoint")
        void confirmApiSupplier() {
            PurchaseOrder po = approved();
            when(suppliers.findById(supplierId)).thenReturn(Optional.of(
                    supplier(SupplierStatus.ACTIVE, SupplierCommunicationChannel.API, null)));
            service.confirm(po.id().value(), approver, new SendPurchaseOrderCommand(null, null));
            ArgumentCaptor<PurchaseOrderSent> sent = ArgumentCaptor.forClass(PurchaseOrderSent.class);
            verify(events).publishEvent(sent.capture());
            assertThat(sent.getValue().recipient()).isEqualTo("https://api.example.com/po");
        }

        @Test
        @DisplayName("confirm: only an APPROVED order; a pending one is INVALID_PURCHASE_ORDER_TRANSITION and nothing is sent")
        void confirmOnlyApproved() {
            PurchaseOrder po = pending();
            assertThat(refused(() -> service.confirm(po.id().value(), approver, new SendPurchaseOrderCommand(null, null)))
                    .errorCode()).isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
            verify(events, never()).publishEvent(any(PurchaseOrderSent.class));
        }

        // ---- supplier's answer

        @Test
        @DisplayName("the supplier's answer must be CONFIRMED or REJECTED; anything else is PO_SUPPLIER_RESPONSE_INVALID")
        void supplierAnswer() {
            PurchaseOrder po = approved();
            for (String status : new String[] {"MAYBE", null}) {
                assertThat(refused(() -> service.recordSupplierConfirmation(po.id().value(),
                        new RecordSupplierConfirmationCommand(status, null, null))).errorCode())
                        .isEqualTo(ErrorCode.PO_SUPPLIER_RESPONSE_INVALID);
            }
        }

        // ---- cancel

        @Test
        @DisplayName("BR-05: an order whose goods receipt is being counted is not cancelled (CONFLICT)")
        void cancelBlockedByReceiptInProgress() {
            PurchaseOrder po = approved();
            when(orders.hasReceiptInProgress(po.id())).thenReturn(true);
            assertThat(refused(() -> service.cancel(po.id().value(), approver, "changed our mind")).errorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
        }

        @Test
        @DisplayName("cancelling an approved order needs a reason, stops supplier delivery and records CANCELLED; "
                + "no cancellation notice goes out because nothing was sent")
        void cancelApproved() {
            PurchaseOrder po = approved();
            assertThat(refused(() -> service.cancel(po.id().value(), approver, " ")).errorCode())
                    .isEqualTo(ErrorCode.PO_REASON_REQUIRED);

            service.cancel(po.id().value(), approver, "changed our mind");

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
            verify(notifications).suppressSupplierDelivery(po.id().value());
            verify(events, never()).publishEvent(any(PurchaseOrderSent.class));
            verify(orders).recordEvent(po.id(), po.activeRevisionId(), "CANCELLED", approver,
                    PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.CANCELLED, "changed our mind");
        }

        @Test
        @DisplayName("cancelling a draft that never went for approval freezes a revision first (it keeps what was cancelled)")
        void cancelDraftFreezes() {
            PurchaseOrder po = draft();
            UUID revision = UUID.randomUUID();
            when(orders.recordRevision(any(), eq(0L), eq(approver), isNull())).thenReturn(revision);

            service.cancel(po.id().value(), approver, "duplicate");

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
            assertThat(po.activeRevisionId()).isEqualTo(revision);
        }

        @Test
        @DisplayName("cancelling a CONFIRMED order tells the supplier: a cancellation PurchaseOrderSent to the address "
                + "it was sent to")
        void cancelConfirmedNotifiesSupplier() {
            PurchaseOrder po = approved();
            po.confirm(approver, null, null, NOW);

            service.cancel(po.id().value(), approver, "supplier cannot deliver");

            verify(notifications).prepareSupplierCancellation(po.id().value());
            ArgumentCaptor<PurchaseOrderSent> sent = ArgumentCaptor.forClass(PurchaseOrderSent.class);
            verify(events).publishEvent(sent.capture());
            assertThat(sent.getValue().cancellation()).isTrue();
            assertThat(sent.getValue().cancellationReason()).isEqualTo("supplier cannot deliver");
            assertThat(sent.getValue().recipient()).isEqualTo("s@x.vn");
        }

        // ---- close

        @Test
        @DisplayName("close: only a RECEIVED order; short-close only a PARTIALLY_RECEIVED one (or force close of a "
                + "CONFIRMED one), never an order still being approved")
        void closing() {
            PurchaseOrder confirmed = approved();
            confirmed.confirm(approver, null, null, NOW);
            assertThat(refused(() -> service.close(confirmed.id().value(), approver)).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
            verify(orders, never()).save(any());

            PurchaseOrder awaiting = pending();
            assertThat(refused(() -> service.closeShort(awaiting.id().value(), approver, "short")).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
            PurchaseOrder approvedOnly = approved();
            assertThat(refused(() -> service.closeShort(approvedOnly.id().value(), approver, "short")).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
            verify(orders, never()).save(any());
        }

        @Test
        @DisplayName("short-close of a PARTIALLY_RECEIVED order is unchanged: SHORT_CLOSED on the timeline, the supplier "
                + "delivery untouched")
        void shortCloseUnchanged() {
            PurchaseOrder po = partiallyReceived();

            service.closeShort(po.id().value(), approver, "supplier cannot finish");

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CLOSED);
            assertThat(po.closeKind()).isEqualTo(CloseKind.SHORT_CLOSE);
            verify(orders).recordEvent(po.id(), po.activeRevisionId(), "SHORT_CLOSED", approver,
                    PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.CLOSED, "supplier cannot finish");
            verify(notifications, never()).suppressSupplierDelivery(any());
        }

        /** As a goods receipt leaves a confirmed order: PARTIALLY_RECEIVED, 4 of the 10 cups counted in. */
        private PurchaseOrder partiallyReceived() {
            PurchaseOrder confirmed = approved();
            confirmed.confirm(approver, null, null, NOW);
            PurchaseOrder po = new PurchaseOrder(new PurchaseOrder.State(confirmed.id(), confirmed.poNumber(),
                    confirmed.type(), null, supplierId, warehouseId, PurchaseOrderStatus.PARTIALLY_RECEIVED, Money.VND,
                    List.of(new PoLine(UUID.randomUUID(), 1, itemId, new Sku("CUP-01"), "EACH", "Cup", 10, 4,
                            Money.vnd(1000), BigDecimal.ZERO, PoLineStatus.PARTIALLY_RECEIVED)),
                    TODAY, TODAY.plusDays(7), null, 30, 7, confirmed.revisionNo(), confirmed.activeRevisionId(), null,
                    submitter, NOW, approver, NOW, false, approver, NOW, null, null, null, null, null,
                    SupplierConfirmationStatus.CONFIRMED, NOW, null, null, 3, NOW, "buyer", NOW, "buyer"));
            when(orders.findByIdForUpdate(po.id())).thenReturn(Optional.of(po));
            return po;
        }

        // ---- force close: 02 §5 Confirmed -> Closed

        @Test
        @DisplayName("02 §5: a CONFIRMED order that will not be received can be force-closed with a reason")
        void forceCloseConfirmedOrder() {
            PurchaseOrder po = approved();
            po.confirm(approver, null, null, NOW);
            when(orders.findByIdForUpdate(po.id())).thenReturn(Optional.of(po));

            service.closeShort(po.id().value(), approver, "supplier stopped production");

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CLOSED);
            assertThat(po.closeKind()).isEqualTo(CloseKind.FORCE_CLOSE);
            assertThat(po.closeReason()).isEqualTo("supplier stopped production");
            assertThat(po.closedBy()).isEqualTo(approver);
            assertThat(po.lines()).extracting(PoLine::status).containsOnly(PoLineStatus.CLOSED);
            verify(orders).save(po);
            verify(orders).recordEvent(po.id(), po.activeRevisionId(), "FORCE_CLOSED", approver,
                    PurchaseOrderStatus.CONFIRMED, PurchaseOrderStatus.CLOSED, "supplier stopped production");
            // Closed, so there is nothing left to deliver to the supplier; it is not a cancellation, so no notice goes.
            verify(notifications).suppressSupplierDelivery(po.id().value());
            verify(notifications, never()).prepareSupplierCancellation(any());
            verify(events, never()).publishEvent(any(PurchaseOrderSent.class));
        }

        @Test
        @DisplayName("force close: the reason is mandatory; nothing is written without one")
        void forceCloseNeedsAReason() {
            PurchaseOrder po = approved();
            po.confirm(approver, null, null, NOW);
            when(orders.findByIdForUpdate(po.id())).thenReturn(Optional.of(po));

            assertThat(refused(() -> service.closeShort(po.id().value(), approver, null)).errorCode())
                    .isEqualTo(ErrorCode.PO_REASON_REQUIRED);
            assertThat(refused(() -> service.closeShort(po.id().value(), approver, "  ")).errorCode())
                    .isEqualTo(ErrorCode.PO_REASON_REQUIRED);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
            verify(orders, never()).save(any());
            verify(orders, never()).recordEvent(any(), any(), anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("force close: refused while a goods receipt of the order is being counted, like a cancel")
        void forceCloseRefusedWhileReceiving() {
            PurchaseOrder po = approved();
            po.confirm(approver, null, null, NOW);
            when(orders.findByIdForUpdate(po.id())).thenReturn(Optional.of(po));
            when(orders.hasReceiptInProgress(po.id())).thenReturn(true);

            assertThat(refused(() -> service.closeShort(po.id().value(), approver, "stopped")).errorCode())
                    .isEqualTo(ErrorCode.CONFLICT);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
            verify(orders, never()).save(any());
        }

        // ---- approval limits: 02 §4 step 7, owner decision POLIMIT2

        @Test
        @DisplayName("submit within the submitter's own limit: approved at once, recording the reason and the limit used")
        void submitWithinLimitAutoApproves() {
            PurchaseOrder po = draft();                       // total 10,000
            UUID revision = UUID.randomUUID();
            when(orders.recordRevision(any(), eq(0L), eq(submitter), isNull())).thenReturn(revision);
            ApprovalLimit limit = limit(submitter, 50_000_000);

            PurchaseOrderSummary summary = service.submit(po.id().value(), submitter);

            assertThat(summary).isNotNull();
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.autoApproved()).isTrue();
            assertThat(po.approvedBy()).isEqualTo(submitter);
            assertThat(po.activeRevisionId()).isEqualTo(revision);
            assertThat(po.pendingRevisionId()).isNull();
            verify(orders).save(po);
            String reason = "Auto-approved within limit: total 10000 VND, limit 50000000 VND";
            verify(orders).recordAutoApproval(revision, submitter, submitter, limit, NOW, reason);
            verify(orders, never()).recordApproval(any(), any(), anyString(), any());
            verify(orders).recordEvent(po.id(), revision, "SUBMITTED", submitter, PurchaseOrderStatus.DRAFT,
                    PurchaseOrderStatus.PENDING_APPROVAL, null);
            verify(orders).recordEvent(po.id(), revision, "AUTO_APPROVED", submitter,
                    PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.APPROVED, reason,
                    limit.facts(Money.vnd(10_000), submitter));
        }

        @Test
        @DisplayName("rule 3: an order that was ever rejected is never auto-approved, even edited below the limit")
        void rejectedBeforeAlwaysWaits() {
            PurchaseOrder po = draft();                       // 10,000, well within the limit below
            limit(submitter, 50_000_000);
            when(orders.hasBeenRejected(po.id())).thenReturn(true);
            when(orders.nextRevisionNo(po.id())).thenReturn(1L);

            service.submit(po.id().value(), submitter);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(po.autoApproved()).isFalse();
            verify(orders, never()).recordAutoApproval(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("rule 4: the automatic approvals of the same submitter and supplier in the last 7 days count with "
                + "this order; up to the limit it is approved, one dong over it waits")
        void antiSplitting() {
            PurchaseOrder within = draft();                   // 10,000 + 40,000 already = 50,000 = the limit
            limit(submitter, 50_000);
            Instant since = NOW.minus(java.time.Duration.ofDays(7));
            when(orders.autoApprovedTotal(submitter, supplierId, Money.VND, since, within.id()))
                    .thenReturn(Money.vnd(40_000));
            service.submit(within.id().value(), submitter);
            assertThat(within.status()).isEqualTo(PurchaseOrderStatus.APPROVED);

            PurchaseOrder over = draft();                     // 10,000 + 40,001 already = one dong over
            when(orders.autoApprovedTotal(submitter, supplierId, Money.VND, since, over.id()))
                    .thenReturn(Money.vnd(40_001));
            service.submit(over.id().value(), submitter);
            assertThat(over.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(over.autoApproved()).isFalse();
        }

        @Test
        @DisplayName("rule 4: the sum is not read for an order that is over its limit anyway")
        void antiSplittingNotAskedWhenAlreadyOver() {
            PurchaseOrder po = draft();                       // 10,000
            limit(submitter, 9_999);
            service.submit(po.id().value(), submitter);
            verify(orders, never()).autoApprovedTotal(any(), any(), any(), any(), any());
        }

        private PurchaseOrder subcontractDraft() {
            PurchaseOrder po = PurchaseOrder.subcontract("SPO-1", UUID.randomUUID(), supplierId, warehouseId, Money.VND,
                    PurchaseOrder.line(1, itemId, new Sku("CUP-01"), "EACH", "Cup", 10, Money.vnd(1000), null),
                    TODAY, TODAY.plusDays(7), 30, 7);
            when(orders.findByIdForUpdate(po.id())).thenReturn(Optional.of(po));
            return po;
        }

        @Test
        @DisplayName("rule 6: a subcontract order generated by splitting a production order uses the limit of whoever "
                + "split it (the PO's creator), not of whoever submits it")
        void subcontractUsesTheCreatorsLimit() {
            UUID splitter = UUID.randomUUID();
            PurchaseOrder po = subcontractDraft();            // 10,000
            when(orders.creatorOf(po.id())).thenReturn(Optional.of(splitter));
            ApprovalLimit splitterLimit = limit(splitter, 50_000);
            UUID revision = UUID.randomUUID();
            when(orders.recordRevision(any(), eq(0L), eq(submitter), isNull())).thenReturn(revision);

            service.submit(po.id().value(), submitter);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.approvedBy()).as("the submitter is the one the system approved for").isEqualTo(submitter);
            verify(orders).recordAutoApproval(eq(revision), eq(submitter), eq(splitter), eq(splitterLimit), eq(NOW),
                    anyString());
        }

        @Test
        @DisplayName("rule 6: the submitter's own limit does not approve a subcontract order whose creator has none")
        void subcontractIgnoresTheSubmittersLimit() {
            UUID splitter = UUID.randomUUID();
            PurchaseOrder po = subcontractDraft();
            when(orders.creatorOf(po.id())).thenReturn(Optional.of(splitter));
            limit(submitter, 50_000_000);                     // the splitter has no limit configured

            service.submit(po.id().value(), submitter);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            verify(orders, never()).recordAutoApproval(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an ordinary order always uses its submitter's limit; its creator is not even looked up")
        void standardOrderUsesTheSubmitter() {
            PurchaseOrder po = draft();
            limit(submitter, 50_000);
            service.submit(po.id().value(), submitter);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            verify(orders, never()).creatorOf(any());
        }

        @Test
        @DisplayName("the limit is inclusive: a total equal to it is auto-approved, one dong more waits")
        void limitBoundary() {
            PurchaseOrder exact = draft();                    // 10,000
            limit(submitter, 10_000);
            service.submit(exact.id().value(), submitter);
            assertThat(exact.status()).isEqualTo(PurchaseOrderStatus.APPROVED);

            PurchaseOrder over = draft();
            limit(submitter, 9_999);
            service.submit(over.id().value(), submitter);
            assertThat(over.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(over.autoApproved()).isFalse();
        }

        @Test
        @DisplayName("submit above the submitter's limit, or with no limit configured (PO-NOLIMIT): PENDING_APPROVAL, "
                + "no approval recorded")
        void submitAboveOrWithoutLimitWaits() {
            PurchaseOrder above = draft();
            limit(submitter, 5_000);
            service.submit(above.id().value(), submitter);
            assertThat(above.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);

            PurchaseOrder none = draft();
            service.submit(none.id().value(), UUID.randomUUID());   // limits.limitOf: none configured for anyone else
            assertThat(none.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            verify(orders, never()).recordApproval(any(), any(), anyString(), any());
            verify(orders, never()).recordAutoApproval(any(), any(), any(), any(), any(), any());
            verify(orders, never()).recordEvent(any(), any(), eq("AUTO_APPROVED"), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a draft discontinued SKU is refused at submit before any limit is looked at")
        void submitChecksPurchasabilityBeforeAutoApproving() {
            PurchaseOrder po = draft();
            limit(submitter, 50_000_000);
            when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PRODUCT_DISCONTINUED));
            assertThat(refused(() -> service.submit(po.id().value(), submitter)).errorCode())
                    .isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            verify(orders, never()).recordApproval(any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("approve: a configured limit must cover the total; above it is PO_APPROVAL_LIMIT_EXCEEDED and the "
                + "order stays pending")
        void approveAboveOwnLimitIsRefused() {
            PurchaseOrder po = pending();                     // 10,000
            limit(approver, 9_999);

            assertThat(refused(() -> service.approve(po.id().value(), approver)).errorCode())
                    .isEqualTo(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            verify(orders, never()).save(any());
            verify(orders, never()).recordApproval(any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("approve (PO-NOLIMIT): an approver with no limit configured is bounded by the approve permission "
                + "alone and may approve any total")
        void approveWithoutConfiguredLimit() {
            PurchaseOrder po = pending();
            UUID unconfigured = UUID.randomUUID();            // limits.limitOf: none configured

            service.approve(po.id().value(), unconfigured);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.approvedBy()).isEqualTo(unconfigured);
            assertThat(po.autoApproved()).isFalse();
        }

        @Test
        @DisplayName("approve: someone whose limit is big enough approves what the submitter could not")
        void approveWithAnotherPersonsBiggerLimit() {
            PurchaseOrder po = draft();
            limit(submitter, 5_000);
            service.submit(po.id().value(), submitter);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            limit(approver, 300_000_000);

            service.approve(po.id().value(), approver);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.approvedBy()).isEqualTo(approver);
            assertThat(po.autoApproved()).isFalse();
        }

        @Test
        @DisplayName("the limit is looked up for the order's currency and today's business date")
        void limitIsPerCurrencyAndDate() {
            PurchaseOrder po = draft();
            service.submit(po.id().value(), submitter);
            verify(limits).limitOf(submitter, java.util.Currency.getInstance("VND"), TODAY);
        }
    }

    // ------------------------------------------------------------------ queries

    @Nested
    class Queries {

        @Test
        @DisplayName("list: statuses are matched case-insensitively and trimmed; an unknown status is VALIDATION_FAILED")
        void listStatuses() {
            when(search.search(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());
            service.list(new ListPurchaseOrdersQuery(0, 20, null, null, List.of(" approved ", "CONFIRMED"), null, null));
            assertThat(refused(() -> service.list(new ListPurchaseOrdersQuery(0, 20, null, null, List.of("DONE"), null,
                    null))).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("findById: empty for an unknown order")
        void findById() {
            when(search.summary(any(PurchaseOrderId.class), any(Boolean.class))).thenReturn(Optional.empty());
            assertThat(service.findById(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("supplier spend of an unknown supplier is SUPPLIER_NOT_FOUND")
        void spendUnknownSupplier() {
            UUID missing = UUID.randomUUID();
            when(orders.supplierStatus(missing)).thenReturn(Optional.empty());
            assertThat(refused(() -> service.supplierSpend(new com.stockflow.procurement.api.SupplierSpendReportQuery(0,
                    20, missing, null, null, null))).errorCode()).isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
        }
    }
}
