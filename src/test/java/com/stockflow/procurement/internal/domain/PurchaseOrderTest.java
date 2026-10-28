package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The purchase order aggregate on its own (decision D4): approval with four-eyes and a revision,
 * confirmation as sending, the supplier's answer, cancelling and closing. Receiving is the goods
 * receipt's; here a received order is rehydrated in the state receiving leaves it.
 */
class PurchaseOrderTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 10, 1);
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();

    private static PoLine line(int no, String sku, int quantity, long price) {
        return PurchaseOrder.line(no, UUID.randomUUID(), new Sku(sku), "EACH", sku, quantity, Money.vnd(price), null);
    }

    private static PurchaseOrder draft() {
        return PurchaseOrder.draft("PO-20261001-000001", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                List.of(line(1, "CUP-12OZ", 10, 1000), line(2, "CUP-16OZ", 5, 2000)), ORDER_DATE,
                ORDER_DATE.plusDays(7), null, 30, 7);
    }

    private static PurchaseOrder submitted() {
        var po = draft();
        po.submit(SUBMITTER, UUID.randomUUID(), 0, NOW);
        return po;
    }

    private static PurchaseOrder approved() {
        var po = submitted();
        po.approve(APPROVER, Money.vnd(1_000_000_000L), NOW);
        return po;
    }

    private static PurchaseOrder confirmed() {
        var po = approved();
        po.confirm(BUYER, null, null, NOW);
        return po;
    }

    /** As a goods receipt leaves it: the status moved, the lines' received quantities counted. */
    private static PurchaseOrder received(PurchaseOrderStatus status, int receivedOfFirstLine) {
        var po = confirmed();
        var lines = List.of(
                new PoLine(UUID.randomUUID(), 1, UUID.randomUUID(), new Sku("CUP-12OZ"), "EACH", "Cup", 10,
                        receivedOfFirstLine, Money.vnd(1000), BigDecimal.ZERO,
                        receivedOfFirstLine >= 10 ? PoLineStatus.RECEIVED : PoLineStatus.PARTIALLY_RECEIVED));
        return new PurchaseOrder(new PurchaseOrder.State(po.id(), po.poNumber(), po.type(), null, po.supplierId(),
                po.warehouseId(), status, po.currency(), lines, po.orderDate(), po.expectedAt(), null, 30, 7,
                po.revisionNo(), po.activeRevisionId(), null, SUBMITTER, NOW, APPROVER, NOW, false, BUYER, NOW, null, null,
                null, null, null, SupplierConfirmationStatus.CONFIRMED, NOW, null, null, 3, NOW, "buyer", NOW,
                "buyer"));
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void draftComputesTotalsFromLines() {
            var po = PurchaseOrder.draft("PO-1", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                    List.of(PurchaseOrder.line(1, UUID.randomUUID(), new Sku("CUP-12OZ"), "EACH", "Cup", 3,
                            Money.vnd(1000), new BigDecimal("10"))),
                    ORDER_DATE, null, null, 30, 7);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(po.subtotal().amount()).isEqualByComparingTo("3000");
            assertThat(po.taxTotal().amount()).isEqualByComparingTo("300");
            assertThat(po.totalAmount().amount()).isEqualByComparingTo("3300");
            assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.NOT_SENT);
        }

        @Test
        void zeroLinesRejected() {
            assertThatThrownBy(() -> PurchaseOrder.draft("PO-1", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                    List.of(), ORDER_DATE, null, null, 30, 7)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void mismatchedLineCurrencyRejected() {
            var usd = PurchaseOrder.line(1, UUID.randomUUID(), new Sku("CUP-12OZ"), "EACH", "Cup", 1,
                    new Money(BigDecimal.ONE, Currency.getInstance("USD")), null);
            assertThatThrownBy(() -> PurchaseOrder.draft("PO-1", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                    List.of(usd), ORDER_DATE, null, null, 30, 7)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void deliveryBeforeTheOrderDateRejected() {
            assertThatThrownBy(() -> PurchaseOrder.draft("PO-1", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                    List.of(line(1, "CUP-12OZ", 1, 1)), ORDER_DATE, ORDER_DATE.minusDays(1), null, 30, 7))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        void aLineNeedsAPositiveQuantityAndPrice() {
            assertThatThrownBy(() -> line(1, "CUP-12OZ", 0, 1000)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> line(1, "CUP-12OZ", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("approval (four-eyes)")
    class Approval {

        @Test
        void submitFreezesARevisionAndApproveActivatesIt() {
            var po = draft();
            UUID revision = UUID.randomUUID();
            po.submit(SUBMITTER, revision, 0, NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(po.pendingRevisionId()).isEqualTo(revision);

            po.approve(APPROVER, Money.vnd(1_000_000_000L), NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.activeRevisionId()).isEqualTo(revision);
            assertThat(po.pendingRevisionId()).isNull();
            assertThat(po.approvedBy()).isEqualTo(APPROVER);
        }

        @Test
        void theSubmitterCannotApprove() {
            var po = submitted();
            assertThatThrownBy(() -> po.approve(SUBMITTER, Money.vnd(1_000_000_000L), NOW))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED));
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
        }

        @Test
        void rejectionGoesBackToDraftWithAReason() {
            var po = submitted();
            assertThatThrownBy(() -> po.reject(" ")).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED));
            po.reject("Giá cao");
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(po.submittedBy()).isNull();
            assertThat(po.pendingRevisionId()).isNull();
        }

        @Test
        void approvingADraftIsATransitionError() {
            assertThatThrownBy(() -> draft().approve(APPROVER, Money.vnd(1_000_000_000L), NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        // 02 §5: Draft -> Approved exists, but only as the under-limit submission; the draft total is 10 x 1000 + 5 x 2000 = 20,000

        @Test
        void aDraftMayGoStraightToApprovedOnlyThroughTheUnderLimitSubmission() {
            assertThat(PurchaseOrderStatus.DRAFT.canTransitionTo(PurchaseOrderStatus.APPROVED)).isTrue();

            var po = draft();
            UUID revision = UUID.randomUUID();
            po.submitWithinLimit(SUBMITTER, revision, 0, Money.vnd(20_000), NOW);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.autoApproved()).isTrue();
            assertThat(po.submittedBy()).isEqualTo(SUBMITTER);
            assertThat(po.approvedBy()).isEqualTo(SUBMITTER);
            assertThat(po.submittedAt()).isEqualTo(NOW);
            assertThat(po.approvedAt()).isEqualTo(NOW);
            assertThat(po.activeRevisionId()).isEqualTo(revision);
            assertThat(po.pendingRevisionId()).isNull();
            assertThat(po.revisionNo()).isZero();
        }

        @Test
        void aTotalAboveTheSubmittersLimitCannotBeAutoApproved() {
            assertThat(draft().coveredBy(Money.vnd(19_999))).isFalse();
            assertThatThrownBy(() -> draft().submitWithinLimit(SUBMITTER, UUID.randomUUID(), 0, Money.vnd(19_999), NOW))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED));
        }

        @Test
        void noLimitIsZeroAndCoversNothing() {
            assertThat(draft().coveredBy(Money.zero(Money.VND))).isFalse();
            assertThatThrownBy(() -> draft().submitWithinLimit(SUBMITTER, UUID.randomUUID(), 0,
                    Money.zero(Money.VND), NOW)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED));
        }

        @Test
        void onlyADraftIsAutoApproved() {
            assertThatThrownBy(() -> submitted().submitWithinLimit(SUBMITTER, UUID.randomUUID(), 1,
                    Money.vnd(1_000_000_000L), NOW)).isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        void anAutoApprovedOrderIsConfirmedLikeAnyApprovedOne() {
            var po = draft();
            po.submitWithinLimit(SUBMITTER, UUID.randomUUID(), 0, Money.vnd(20_000), NOW);
            po.confirm(BUYER, null, null, NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
        }

        @Test
        void anApproverBelowTheTotalIsRefusedAndTheOrderStaysPending() {
            var po = submitted();
            assertThatThrownBy(() -> po.approve(APPROVER, Money.vnd(19_999), NOW))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED));
            assertThatThrownBy(() -> po.approve(APPROVER, Money.zero(Money.VND), NOW))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED));
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(po.approvedBy()).isNull();
            assertThat(po.autoApproved()).isFalse();

            po.approve(APPROVER, Money.vnd(20_000), NOW);   // a limit equal to the total is enough
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.autoApproved()).isFalse();
        }

        @Test
        void fourEyesComesBeforeTheLimit() {
            assertThatThrownBy(() -> submitted().approve(SUBMITTER, Money.zero(Money.VND), NOW))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED));
        }

        @Test
        @DisplayName("PO-NOLIMIT: an approver with no limit configured (null) approves any total")
        void approveWithoutConfiguredLimit() {
            PurchaseOrder po = submitted();
            po.approve(APPROVER, null, NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
            assertThat(po.approvedBy()).isEqualTo(APPROVER);
        }
    }

    @Nested
    @DisplayName("confirmation = sending (#36)")
    class Confirmation {

        @Test
        void confirmLocksAndAwaitsTheSupplier() {
            var po = confirmed();
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
            assertThat(po.confirmedBy()).isEqualTo(BUYER);
            assertThat(po.confirmedAt()).isEqualTo(NOW);
            assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.PENDING);
        }

        @Test
        void onlyAnApprovedOrderIsConfirmed() {
            assertThatThrownBy(() -> submitted().confirm(BUYER, null, null, NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        void aDeliveryDateIsRequired() {
            var po = PurchaseOrder.draft("PO-1", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                    List.of(line(1, "CUP-12OZ", 1, 1)), ORDER_DATE, null, null, 30, 7);
            po.submit(SUBMITTER, UUID.randomUUID(), 0, NOW);
            po.approve(APPROVER, Money.vnd(1_000_000_000L), NOW);
            assertThatThrownBy(() -> po.confirm(BUYER, null, null, NOW)).isInstanceOfSatisfying(
                    BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_DELIVERY_DATE_REQUIRED));
        }

        @Test
        void supplierAnswersExactlyOnce() {
            var po = confirmed();
            po.recordSupplierConfirmation(SupplierConfirmationStatus.CONFIRMED, " REF-1 ", null, NOW);
            assertThat(po.supplierReference()).isEqualTo("REF-1");
            po.recordSupplierConfirmation(SupplierConfirmationStatus.CONFIRMED, "REF-1", null, NOW); // same answer: no-op
            assertThatThrownBy(() -> po.recordSupplierConfirmation(SupplierConfirmationStatus.REJECTED, null, "No", NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED); // the answer never moves the status
        }

        @Test
        void aDraftCannotBeAnswered() {
            assertThatThrownBy(() -> draft().recordSupplierConfirmation(SupplierConfirmationStatus.CONFIRMED, null,
                    null, NOW)).isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        void recoveryNeedsAPendingAnswerAndAcknowledgedOverdueDate() {
            var po = confirmed();
            assertThatThrownBy(() -> po.requireDeliveryRecovery(ORDER_DATE.plusDays(30), "retry", true, false))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            po.requireDeliveryRecovery(ORDER_DATE.plusDays(30), "retry", true, true);
            assertThatThrownBy(() -> po.requireDeliveryRecovery(ORDER_DATE, "retry", false, true))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            po.recordSupplierConfirmation(SupplierConfirmationStatus.CONFIRMED, null, null, NOW);
            assertThatThrownBy(() -> po.requireDeliveryRecovery(ORDER_DATE, "retry", true, true))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }
    }

    @Nested
    @DisplayName("ending")
    class Ending {

        @Test
        void cancelBeforeReceivingCancelsTheLines() {
            var po = confirmed();
            po.cancel("Đổi NCC");
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
            assertThat(po.cancelReason()).isEqualTo("Đổi NCC");
            assertThat(po.lines()).extracting(PoLine::status).containsOnly(PoLineStatus.CANCELLED);
        }

        @Test
        void cancelNeedsAShortReasonAndNothingReceived() {
            assertThatThrownBy(() -> draft().requireCancellable("x".repeat(256))).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED));
            assertThatThrownBy(() -> received(PurchaseOrderStatus.PARTIALLY_RECEIVED, 4).cancel("late"))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        void aDraftIsFrozenBeforeItIsCancelled() {
            var po = draft();
            assertThatThrownBy(() -> po.cancel("x")).isInstanceOf(IllegalStateException.class);
            UUID revision = UUID.randomUUID();
            po.freeze(revision, 0);
            po.cancel("Không cần nữa");
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
            assertThat(po.activeRevisionId()).isEqualTo(revision);
        }

        @Test
        void closeShortWritesOffTheRest() {
            var po = received(PurchaseOrderStatus.PARTIALLY_RECEIVED, 4);
            assertThat(po.lines().getFirst().openQuantity()).isEqualTo(6);
            po.closeShort("Thiếu hàng", BUYER, NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CLOSED);
            assertThat(po.closeKind()).isEqualTo(CloseKind.SHORT_CLOSE);
            assertThat(po.closeReason()).isEqualTo("Thiếu hàng");
            assertThat(po.lines()).extracting(PoLine::status).containsOnly(PoLineStatus.CLOSED);
        }

        // 02 §5: Confirmed -> Closed (force close, with a reason): nothing was received and nothing will be

        @Test
        void aConfirmedOrderThatWillNotBeReceivedIsForceClosedWithAReason() {
            assertThat(PurchaseOrderStatus.CONFIRMED.canTransitionTo(PurchaseOrderStatus.CLOSED)).isTrue();
            var po = confirmed();

            po.closeShort(" Nhà cung cấp ngừng sản xuất ", BUYER, NOW);

            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CLOSED);
            assertThat(po.closeKind()).isEqualTo(CloseKind.FORCE_CLOSE);
            assertThat(po.closeReason()).isEqualTo("Nhà cung cấp ngừng sản xuất");
            assertThat(po.closedBy()).isEqualTo(BUYER);
            assertThat(po.closedAt()).isEqualTo(NOW);
            assertThat(po.lines()).extracting(PoLine::status).containsOnly(PoLineStatus.CLOSED);
            assertThat(po.lines()).extracting(PoLine::quantityReceived).containsOnly(0);
        }

        @Test
        void aForceCloseNeedsAReason() {
            var po = confirmed();
            for (String reason : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> po.closeShort(reason, BUYER, NOW)).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED));
            }
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
            assertThat(po.closeKind()).isNull();
        }

        @Test
        void onlyAConfirmedOrReceivingOrderIsClosedShort() {
            assertThatThrownBy(() -> draft().closeShort("x", BUYER, NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            assertThatThrownBy(() -> submitted().closeShort("x", BUYER, NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            assertThatThrownBy(() -> approved().closeShort("x", BUYER, NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            assertThatThrownBy(() -> received(PurchaseOrderStatus.RECEIVED, 10).closeShort("x", BUYER, NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }

        @Test
        void aReceivedOrderClosesNormally() {
            var po = received(PurchaseOrderStatus.RECEIVED, 10);
            po.close(BUYER, NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CLOSED);
            assertThat(po.closeKind()).isEqualTo(CloseKind.NORMAL);
            assertThat(po.lines()).extracting(PoLine::status).containsOnly(PoLineStatus.RECEIVED);
            assertThatThrownBy(() -> received(PurchaseOrderStatus.PARTIALLY_RECEIVED, 4).close(BUYER, NOW))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }
    }

    @Nested
    @DisplayName("editing a draft (SCRUM-462)")
    class EditingADraft {

        private static PurchaseOrder.DraftLine keep(PoLine line, int quantity, long price) {
            return new PurchaseOrder.DraftLine(line.id(), line.inventoryItemId(), line.sku(), line.uom(),
                    "edited", quantity, Money.vnd(price), BigDecimal.TEN);
        }

        private static PurchaseOrder.DraftLine added(String sku, int quantity, long price) {
            return new PurchaseOrder.DraftLine(null, UUID.randomUUID(), new Sku(sku), "EACH", sku, quantity,
                    Money.vnd(price), null);
        }

        @Test
        @DisplayName("warehouse, date, note and lines all change; a kept line keeps its number, a new one comes after")
        void revisesEverything() {
            var po = draft();
            PoLine first = po.lines().get(0);
            UUID warehouse = UUID.randomUUID();

            po.reviseDraft(warehouse, ORDER_DATE.plusDays(20), "call before delivery",
                    List.of(keep(first, 12, 1500), added("CUP-22OZ", 3, 4000)), 3, 12);

            assertThat(po.warehouseId()).isEqualTo(warehouse);
            assertThat(po.expectedAt()).isEqualTo(ORDER_DATE.plusDays(20));
            assertThat(po.note()).isEqualTo("call before delivery");
            assertThat(po.leadTimeDays()).isEqualTo(12);
            assertThat(po.lines()).extracting(PoLine::lineNo).containsExactly(1, 3);
            assertThat(po.lines().get(0).id()).isEqualTo(first.id());
            assertThat(po.lines().get(0).quantityOrdered()).isEqualTo(12);
            assertThat(po.lines().get(0).description()).isEqualTo("edited");
            // 12 x 1500 = 18000 + 10 % tax = 19800; 3 x 4000 = 12000
            assertThat(po.totalAmount().amount()).isEqualByComparingTo("31800");
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        }

        @Test
        @DisplayName("a new line is numbered past every number the order used, withdrawn lines included")
        void newLineNumbersAreNeverReused() {
            var po = draft();
            po.reviseDraft(po.warehouseId(), po.expectedAt(), null, List.of(added("CUP-22OZ", 1, 100)), 7, 7);
            assertThat(po.lines()).extracting(PoLine::lineNo).containsExactly(7);
        }

        @Test
        @DisplayName("only a DRAFT standard order is edited")
        void onlyADraft() {
            for (var po : List.of(submitted(), approved(), confirmed())) {
                assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), po.expectedAt(), null,
                        List.of(added("CUP-22OZ", 1, 100)), 3, 7))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_EDITABLE);
            }
            var subcontract = PurchaseOrder.subcontract("PO-SC-1", UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Money.VND, line(1, "CUP-12OZ", 10, 1000), ORDER_DATE, ORDER_DATE.plusDays(5),
                    30, 7);
            assertThatThrownBy(() -> subcontract.reviseDraft(subcontract.warehouseId(), subcontract.expectedAt(),
                    null, List.of(added("CUP-22OZ", 1, 100)), 2, 7))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_EDITABLE);
        }

        @Test
        @DisplayName("a date before the order date, no date, no line, a line of another order, a changed SKU or a repeated SKU is refused")
        void refusals() {
            var po = draft();
            PoLine first = po.lines().get(0);
            assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), ORDER_DATE.minusDays(1), null,
                    List.of(keep(first, 1, 100)), 3, 7))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), null, null, List.of(keep(first, 1, 100)), 3, 7))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), po.expectedAt(), null, List.of(), 3, 7))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), po.expectedAt(), null,
                    List.of(new PurchaseOrder.DraftLine(UUID.randomUUID(), first.inventoryItemId(), first.sku(), "EACH",
                            null, 1, Money.vnd(100), null)), 3, 7))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), po.expectedAt(), null,
                    List.of(new PurchaseOrder.DraftLine(first.id(), UUID.randomUUID(), new Sku("OTHER-SKU"), "EACH",
                            null, 1, Money.vnd(100), null)), 3, 7))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> po.reviseDraft(po.warehouseId(), po.expectedAt(), null,
                    List.of(keep(first, 1, 100), new PurchaseOrder.DraftLine(null, first.inventoryItemId(),
                            first.sku(), "EACH", null, 2, Money.vnd(100), null)), 3, 7))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            // Nothing changed by a refusal.
            assertThat(po.lines()).hasSize(2);
            assertThat(po.expectedAt()).isEqualTo(ORDER_DATE.plusDays(7));
        }

        @Test
        @DisplayName("after a rejection the draft is edited, then submitted again")
        void afterRejection() {
            var po = submitted();
            po.reject("too expensive");
            po.reviseDraft(po.warehouseId(), po.expectedAt(), null, List.of(keep(po.lines().get(1), 5, 1500)), 3, 7);
            po.submit(SUBMITTER, UUID.randomUUID(), 1, NOW);
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
            assertThat(po.lines()).extracting(PoLine::lineNo).containsExactly(2);
        }
    }
}
