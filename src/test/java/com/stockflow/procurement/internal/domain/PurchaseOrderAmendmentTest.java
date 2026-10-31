package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The amendment rules of the aggregate on their own (SCRUM-117): who may amend, what a request is checked
 * against, what it proposes, and how the pointers and the supplier's answer move. No database, no catalog.
 */
class PurchaseOrderAmendmentTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate EXPECTED = ORDER_DATE.plusDays(7);
    private static final UUID LINE_1 = UUID.randomUUID();
    private static final UUID LINE_2 = UUID.randomUUID();

    private static PoLine line(UUID id, int no, String sku, int quantity, int received, long price) {
        return new PoLine(id, no, UUID.randomUUID(), new Sku(sku), "EACH", sku, quantity, received,
                Money.vnd(price), BigDecimal.ZERO, received > 0 ? PoLineStatus.PARTIALLY_RECEIVED : PoLineStatus.OPEN);
    }

    /** Line 1: 10 x 1000, line 2: 5 x 2000; the total is 20,000. */
    private static List<PoLine> twoLines() {
        return List.of(line(LINE_1, 1, "CUP-12OZ", 10, 0, 1000), line(LINE_2, 2, "CUP-16OZ", 5, 0, 2000));
    }

    private static PurchaseOrder order(PurchaseOrder.Type type, PurchaseOrderStatus status,
                                       SupplierConfirmationStatus response, UUID pending, List<PoLine> lines) {
        return new PurchaseOrder(new PurchaseOrder.State(PurchaseOrderId.newId(), "PO-20261001-000001", type,
                type == PurchaseOrder.Type.SUBCONTRACT ? UUID.randomUUID() : null, UUID.randomUUID(),
                UUID.randomUUID(), status, Money.VND, lines, ORDER_DATE, EXPECTED, null, 30, 7, 1,
                UUID.randomUUID(), pending, UUID.randomUUID(), NOW, UUID.randomUUID(), NOW, false,
                status == PurchaseOrderStatus.CONFIRMED ? UUID.randomUUID() : null,
                status == PurchaseOrderStatus.CONFIRMED ? NOW : null, null, null, null, null, null, response,
                response == SupplierConfirmationStatus.CONFIRMED ? NOW : null,
                response == SupplierConfirmationStatus.CONFIRMED ? "REF-1" : null, null, 3, NOW, "buyer", NOW,
                "buyer"));
    }

    private static PurchaseOrder approved() {
        return order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.APPROVED, SupplierConfirmationStatus.NOT_SENT,
                null, twoLines());
    }

    private static PurchaseOrder confirmed(SupplierConfirmationStatus response) {
        return order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.CONFIRMED, response, null, twoLines());
    }

    private static PurchaseOrder.AmendmentLine keep(UUID id, int quantity) {
        return new PurchaseOrder.AmendmentLine(id, quantity, null, null, null);
    }

    private static PurchaseOrder.AmendmentRequest request(PurchaseOrder.AmendmentLine... lines) {
        return new PurchaseOrder.AmendmentRequest(EXPECTED, null, "Supplier can deliver less", List.of(lines));
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void proposeRefusesUnlessStandardApprovedOrConfirmed() {
        var req = request(keep(LINE_1, 8), keep(LINE_2, 5));
        for (PurchaseOrderStatus status : List.of(PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.PENDING_APPROVAL,
                PurchaseOrderStatus.PARTIALLY_RECEIVED)) {
            var po = order(PurchaseOrder.Type.STANDARD, status, SupplierConfirmationStatus.NOT_SENT, null, twoLines());
            assertCode(() -> po.proposeAmendment(req), ErrorCode.PO_AMENDMENT_NOT_ALLOWED);
        }
        var subcontract = order(PurchaseOrder.Type.SUBCONTRACT, PurchaseOrderStatus.APPROVED,
                SupplierConfirmationStatus.NOT_SENT, null, List.of(line(LINE_1, 1, "PRINT", 10, 0, 1000)));
        assertCode(() -> subcontract.proposeAmendment(request(keep(LINE_1, 8))), ErrorCode.PO_AMENDMENT_NOT_ALLOWED);
    }

    @Test
    void proposeRefusesWhenSupplierRejected() {
        var po = confirmed(SupplierConfirmationStatus.REJECTED);
        assertCode(() -> po.proposeAmendment(request(keep(LINE_1, 8), keep(LINE_2, 5))),
                ErrorCode.PO_AMENDMENT_NOT_ALLOWED);
    }

    @Test
    void proposeRefusesWhenSomethingWasReceived() {
        var po = order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.CONFIRMED, SupplierConfirmationStatus.CONFIRMED,
                null, List.of(line(LINE_1, 1, "CUP-12OZ", 10, 1, 1000), line(LINE_2, 2, "CUP-16OZ", 5, 0, 2000)));
        assertCode(() -> po.proposeAmendment(request(keep(LINE_1, 8), keep(LINE_2, 5))),
                ErrorCode.PO_AMENDMENT_NOT_ALLOWED);
    }

    @Test
    void proposeRefusesWhileAnotherWaits() {
        var po = order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.APPROVED, SupplierConfirmationStatus.NOT_SENT,
                UUID.randomUUID(), twoLines());
        assertCode(() -> po.proposeAmendment(request(keep(LINE_1, 8), keep(LINE_2, 5))),
                ErrorCode.PO_AMENDMENT_ALREADY_PENDING);
    }

    @Test
    void proposeRefusesUnknownCancelledOrDuplicatedLine() {
        // The aggregate holds live lines only, so a CANCELLED or WITHDRAWN line is simply not one of them.
        var cancelledId = UUID.randomUUID();
        var po = approved();
        var req = request(keep(LINE_1, 8), keep(LINE_1, 9), keep(UUID.randomUUID(), 1), keep(cancelledId, 1),
                new PurchaseOrder.AmendmentLine(null, 1, null, null, null));

        assertThatThrownBy(() -> po.proposeAmendment(req)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(FieldError::field)
                    .containsExactly("lines[1].lineId", "lines[2].lineId", "lines[3].lineId", "lines[4].lineId");
        });
    }

    @Test
    void proposeRefusesNoLineKeptBlankReasonAndFieldRanges() {
        var po = approved();

        var noLine = new PurchaseOrder.AmendmentRequest(EXPECTED.minusDays(30), null, " ", List.of());
        assertThatThrownBy(() -> po.proposeAmendment(noLine)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(FieldError::field)
                    .containsExactlyInAnyOrder("reason", "expectedDate", "lines");
        });

        var tooLong = new PurchaseOrder.AmendmentRequest(EXPECTED, null, "x".repeat(501),
                List.of(keep(LINE_1, 8), keep(LINE_2, 5)));
        assertThatThrownBy(() -> po.proposeAmendment(tooLong)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("reason"));

        var ranges = request(keep(LINE_1, 0), keep(LINE_2, 1_000_001),
                new PurchaseOrder.AmendmentLine(LINE_1, 1, BigDecimal.ZERO, new BigDecimal("101"), null));
        assertThatThrownBy(() -> po.proposeAmendment(ranges)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(FieldError::field).contains("lines[0].quantityOrdered",
                    "lines[1].quantityOrdered", "lines[2].unitPrice", "lines[2].taxRate");
        });
    }

    @Test
    void proposeRefusesNothingChanged() {
        var po = approved();
        assertCode(() -> po.proposeAmendment(request(keep(LINE_1, 10), keep(LINE_2, 5))),
                ErrorCode.PO_AMENDMENT_NO_CHANGE);
    }

    @Test
    void proposeKeepsBlankPriceTaxAndDescription() {
        var po = approved();

        var proposal = po.proposeAmendment(request(
                new PurchaseOrder.AmendmentLine(LINE_1, 8, null, null, " "), keep(LINE_2, 5)));

        var after = proposal.kept().get(0).after();
        var before = po.lines().get(0);
        assertThat(after.unitPrice()).isEqualTo(before.unitPrice());
        assertThat(after.taxRate()).isEqualByComparingTo(before.taxRate());
        assertThat(after.description()).isEqualTo(before.description());
        assertThat(after.quantityOrdered()).isEqualTo(8);
        assertThat(after.id()).isEqualTo(LINE_1);
        assertThat(after.lineNo()).isEqualTo(1);
    }

    @Test
    void proposeRemovesLinesLeftOut() {
        var po = approved();

        var proposal = po.proposeAmendment(request(keep(LINE_1, 8)));

        assertThat(proposal.removed()).extracting(PoLine::id).containsExactly(LINE_2);
        assertThat(proposal.kept()).hasSize(1);
        assertThat(proposal.subtotal().amount()).isEqualByComparingTo("8000");
        assertThat(proposal.totalAmount().amount()).isEqualByComparingTo("8000");
        assertThat(po.lines()).hasSize(2);   // proposing changes nothing
    }

    @Test
    void proposeRecordsChanges() {
        var po = approved();
        LocalDate moved = EXPECTED.plusDays(5);

        var proposal = po.proposeAmendment(new PurchaseOrder.AmendmentRequest(moved, null, "Less, later",
                List.of(keep(LINE_1, 8))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("line1.quantityBefore", "10");
        expected.put("line1.quantityAfter", "8");
        expected.put("line2.removed", "true");
        expected.put("expectedAtBefore", EXPECTED.toString());
        expected.put("expectedAtAfter", moved.toString());
        expected.put("totalAmountBefore", po.totalAmount().amount().toPlainString());
        expected.put("totalAmountAfter", proposal.totalAmount().amount().toPlainString());
        assertThat(proposal.changes()).isEqualTo(expected);
        assertThat(proposal.expectedAt()).isEqualTo(moved);
        assertThat(proposal.reason()).isEqualTo("Less, later");
    }

    @Test
    void requestThenApplyMovesPointersAndResetsSupplierResponse() {
        var po = confirmed(SupplierConfirmationStatus.CONFIRMED);
        UUID revision = UUID.randomUUID();

        po.requestAmendment(revision);
        assertThat(po.pendingRevisionId()).isEqualTo(revision);

        po.applyApprovedAmendment(revision, 2);
        assertThat(po.activeRevisionId()).isEqualTo(revision);
        assertThat(po.pendingRevisionId()).isNull();
        assertThat(po.revisionNo()).isEqualTo(2);
        assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.PENDING);
        assertThat(po.supplierRespondedAt()).isNull();
        assertThat(po.supplierReference()).isNull();
        assertThat(po.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
    }

    @Test
    void applyOnApprovedOrderLeavesSupplierResponseNotSent() {
        var po = approved();
        UUID revision = UUID.randomUUID();
        po.requestAmendment(revision);

        po.applyApprovedAmendment(revision, 2);

        assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.NOT_SENT);
        assertThat(po.activeRevisionId()).isEqualTo(revision);
    }

    @Test
    void decidableRefusesWithoutPendingOrAfterReceipt() {
        assertCode(() -> approved().requireAmendmentDecidable(), ErrorCode.PO_AMENDMENT_NOT_PENDING);

        var received = order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.PARTIALLY_RECEIVED,
                SupplierConfirmationStatus.CONFIRMED, UUID.randomUUID(),
                List.of(line(LINE_1, 1, "CUP-12OZ", 10, 1, 1000)));
        assertCode(received::requireAmendmentDecidable, ErrorCode.PO_AMENDMENT_NOT_ALLOWED);

        var waiting = approved();
        waiting.requestAmendment(UUID.randomUUID());
        waiting.requireAmendmentDecidable();   // does not throw
    }

    @Test
    void dropClearsPendingOnly() {
        var po = confirmed(SupplierConfirmationStatus.CONFIRMED);
        UUID active = po.activeRevisionId();
        po.requestAmendment(UUID.randomUUID());

        po.dropAmendment();

        assertThat(po.pendingRevisionId()).isNull();
        assertThat(po.activeRevisionId()).isEqualTo(active);
        assertThat(po.revisionNo()).isEqualTo(1);
        assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.CONFIRMED);
    }

    @Test
    void supplierRefusalBlocksApprovalButNotDropping() {
        var po = order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.CONFIRMED, SupplierConfirmationStatus.PENDING,
                UUID.randomUUID(), twoLines());
        po.recordSupplierConfirmation(SupplierConfirmationStatus.REJECTED, null, "Cannot supply", NOW);

        assertCode(po::requireAmendmentDecidable, ErrorCode.PO_AMENDMENT_NOT_ALLOWED);
        po.requirePendingAmendment();   // does not throw
        po.dropAmendment();
        assertThat(po.pendingRevisionId()).isNull();
        assertCode(po::dropAmendment, ErrorCode.PO_AMENDMENT_NOT_PENDING);
    }

    @Test
    void aLineAnEarlierAmendmentRemovedNoLongerCounts() {
        // As the adapter loads an order after an approved amendment removed line 2: still there, CANCELLED.
        var removedBefore = new PoLine(LINE_2, 2, UUID.randomUUID(), new Sku("CUP-16OZ"), "EACH", "CUP-16OZ", 5, 0,
                Money.vnd(2000), BigDecimal.ZERO, PoLineStatus.CANCELLED);
        var po = order(PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.CONFIRMED, SupplierConfirmationStatus.PENDING,
                null, List.of(line(LINE_1, 1, "CUP-12OZ", 10, 0, 1000), removedBefore));

        assertThat(po.totalAmount().amount()).as("only line 1 is ordered").isEqualByComparingTo("10000");
        var proposal = po.proposeAmendment(request(keep(LINE_1, 8)));
        assertThat(proposal.removed()).as("not removed a second time").isEmpty();
        assertThat(proposal.changes()).doesNotContainKey("line2.removed")
                .containsEntry("totalAmountBefore", po.totalAmount().amount().toPlainString());
        assertThatThrownBy(() -> po.proposeAmendment(request(keep(LINE_1, 8), keep(LINE_2, 5))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors())
                        .extracting(FieldError::field).containsExactly("lines[1].lineId"));
    }
}
