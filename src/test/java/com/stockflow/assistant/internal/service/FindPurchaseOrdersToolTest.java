package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * findPurchaseOrders says what a buyer needs beside the status: whether the supplier refused the order, whether it is
 * late, and which amount includes VAT (BIZ-5, BIZ-12).
 */
class FindPurchaseOrdersToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /** 01:00 on 29 October in Vietnam, still the 28th in UTC: "today" must be the Vietnamese day. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T18:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    private final ProcurementService procurement = mock(ProcurementService.class);
    private final SupplierService suppliers = mock(SupplierService.class);
    private final FindPurchaseOrdersTool tool = new FindPurchaseOrdersTool(procurement, suppliers, CLOCK);

    private static ToolArguments args(String supplier) {
        return args("", supplier);
    }

    private static ToolArguments args(String poNumber, String supplier) {
        return ToolArguments.parse(JSON, "{\"status\":\"ANY\",\"poNumber\":\"" + poNumber + "\",\"supplier\":\""
                + supplier + "\",\"limit\":50}");
    }

    private static PurchaseOrderSummary po(String number, String status, String supplierAnswer, LocalDate expected,
                                           String responseNote) {
        return po(UUID.randomUUID(), number, status, supplierAnswer, expected, responseNote, List.of());
    }

    /** As the list returns a row ({@code lines} empty), or as the detail read returns it. */
    private static PurchaseOrderSummary po(UUID id, String number, String status, String supplierAnswer,
                                           LocalDate expected, String responseNote, List<POLineSummary> lines) {
        return new PurchaseOrderSummary(id, number, "STANDARD", null, UUID.randomUUID(), "GOVIET",
                "Xưởng gỗ Việt", UUID.randomUUID(), "HCM", "Ho Chi Minh warehouse", status, "VND",
                LocalDate.of(2026, 10, 1), expected, new BigDecimal("71000000.00"), new BigDecimal("7100000.00"),
                new BigDecimal("78100000.00"), null, lines, Instant.now(), "buyer", Instant.now(), "buyer", false,
                0, UUID.randomUUID(), Instant.now(), UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
                Instant.now(), null, null, null, null, 30, 14, Instant.now(), supplierAnswer, null, null, responseNote,
                0, BigDecimal.ZERO);
    }

    private static POLineSummary line(int lineNo, String sku, int ordered, int received, String status) {
        return new POLineSummary(UUID.randomUUID(), lineNo, UUID.randomUUID(), sku, "a product", "EACH", ordered,
                received, new BigDecimal("400.00"), new BigDecimal("10"), new BigDecimal("440.00"), status, false,
                false, false);
    }

    private List<FindPurchaseOrdersTool.Row> rows(PurchaseOrderSummary... orders) {
        when(procurement.list(any())).thenReturn(PageResponse.of(List.of(orders), 0, 50, orders.length));
        return tool.run(args("")).purchaseOrders();
    }

    @Test
    @DisplayName("an order still awaited after its expected date is overdue, counted in whole Vietnamese days")
    void overdue() {
        FindPurchaseOrdersTool.Row row = rows(po("PO-1", "CONFIRMED", "PENDING", LocalDate.of(2026, 10, 15), null))
                .getFirst();

        assertThat(row.overdue()).isTrue();
        assertThat(row.daysLate()).isEqualTo(14);
        assertThat(row.supplierConfirmationStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("overdue only while the supplier has the order, goods are awaited and the expected day is over")
    void onlyWhileAwaited() {
        List<FindPurchaseOrdersTool.Row> found = rows(
                po("PO-APPROVED", "APPROVED", "NOT_SENT", LocalDate.of(2026, 10, 20), null),
                po("PO-PARTIAL", "PARTIALLY_RECEIVED", "CONFIRMED", TODAY.minusDays(1), null),
                po("PO-DUE-TODAY", "CONFIRMED", "CONFIRMED", TODAY, null),
                po("PO-NO-DATE", "CONFIRMED", "PENDING", null, null),
                po("PO-RECEIVED", "RECEIVED", "CONFIRMED", LocalDate.of(2026, 10, 15), null),
                po("PO-DRAFT", "DRAFT", "NOT_SENT", LocalDate.of(2026, 10, 15), null));

        assertThat(found).extracting(FindPurchaseOrdersTool.Row::poNumber, FindPurchaseOrdersTool.Row::overdue,
                        FindPurchaseOrdersTool.Row::daysLate)
                .containsExactly(
                        tuple("PO-APPROVED", false, null),
                        tuple("PO-PARTIAL", true, 1L),
                        tuple("PO-DUE-TODAY", false, null),
                        tuple("PO-NO-DATE", false, null),
                        tuple("PO-RECEIVED", false, null),
                        tuple("PO-DRAFT", false, null));
    }

    @Test
    @DisplayName("an order the supplier refused shows the refusal and its reason, and is not reported as late")
    void refused() {
        FindPurchaseOrdersTool.Row row = rows(po("PO-2", "CONFIRMED", "REJECTED", LocalDate.of(2026, 10, 15),
                "Out of raw material until December")).getFirst();

        assertThat(row.status()).isEqualTo("CONFIRMED");
        assertThat(row.supplierConfirmationStatus()).isEqualTo("REJECTED");
        assertThat(row.rejectionReason()).isEqualTo("Out of raw material until December");
        assertThat(row.overdue()).isFalse();
        assertThat(row.daysLate()).isNull();
    }

    @Test
    @DisplayName("a note recorded with an acceptance is not returned; a long refusal reason is cut")
    void reasons() {
        String longReason = "x".repeat(FindPurchaseOrdersTool.Row.MAX_REASON + 50);
        List<FindPurchaseOrdersTool.Row> found = rows(
                po("PO-ACCEPTED", "CONFIRMED", "CONFIRMED", TODAY.plusDays(3), "Delivery by truck"),
                po("PO-REFUSED", "CONFIRMED", "REJECTED", TODAY.plusDays(3), longReason));

        assertThat(found.get(0).rejectionReason()).isNull();
        assertThat(found.get(1).rejectionReason()).hasSize(FindPurchaseOrdersTool.Row.MAX_REASON + 1).endsWith("…");
    }

    @Test
    @DisplayName("the amounts say which one includes VAT, and the type tells goods from printing")
    void amounts() throws Exception {
        FindPurchaseOrdersTool.Row row = rows(po("PO-3", "CONFIRMED", "PENDING", TODAY.plusDays(5), null)).getFirst();

        assertThat(row.type()).isEqualTo("STANDARD");
        assertThat(row.subtotal()).isEqualByComparingTo("71000000");
        assertThat(row.taxTotal()).isEqualByComparingTo("7100000");
        assertThat(row.totalIncludingTax()).isEqualByComparingTo("78100000");
        assertThat(JSON.writeValueAsString(row)).contains("\"totalIncludingTax\"").doesNotContain("totalAmount");
    }

    @Test
    @DisplayName("the one PO asked about comes with its lines: what arrived and what the supplier still owes")
    void linesOfTheOneOrder() {
        UUID id = UUID.randomUUID();
        List<POLineSummary> lines = List.of(line(1, "EVAL-LID-90", 1000, 0, "OPEN"),
                line(2, "EVAL-CUP-12OZ", 500, 200, "PARTIALLY_RECEIVED"),
                line(3, "EVAL-CUP-16OZ", 100, 60, "CLOSED"));
        when(procurement.list(any())).thenReturn(PageResponse.of(
                List.of(po(id, "PO-PLAY-0001", "PARTIALLY_RECEIVED", "CONFIRMED", TODAY.plusDays(5), null, List.of())),
                0, 50, 1));
        when(procurement.findById(id)).thenReturn(Optional.of(
                po(id, "PO-PLAY-0001", "PARTIALLY_RECEIVED", "CONFIRMED", TODAY.plusDays(5), null, lines)));

        FindPurchaseOrdersTool.Row row = tool.run(args("PO-PLAY-0001", "")).purchaseOrders().getFirst();

        assertThat(row.lineCount()).isEqualTo(3);
        assertThat(row.lines()).extracting(FindPurchaseOrdersTool.Line::sku, FindPurchaseOrdersTool.Line::ordered,
                        FindPurchaseOrdersTool.Line::received, FindPurchaseOrdersTool.Line::stillToReceive)
                .as("a short-closed line owes nothing, whatever ordered minus received says")
                .containsExactly(tuple("EVAL-LID-90", 1000, 0, 1000), tuple("EVAL-CUP-12OZ", 500, 200, 300),
                        tuple("EVAL-CUP-16OZ", 100, 60, 0));
        assertThat(row.lines().getFirst().unitPriceBeforeTax()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("on an order the supplier refused, or one not approved yet, no line is still to receive")
    void nothingToReceive() {
        UUID refused = UUID.randomUUID();
        when(procurement.list(any())).thenReturn(PageResponse.of(List.of(
                po(refused, "PO-9", "CONFIRMED", "REJECTED", TODAY.plusDays(5), "No material", List.of())), 0, 50, 1));
        when(procurement.findById(refused)).thenReturn(Optional.of(po(refused, "PO-9", "CONFIRMED", "REJECTED",
                TODAY.plusDays(5), "No material", List.of(line(1, "EVAL-LID-90", 1000, 0, "OPEN")))));
        assertThat(tool.run(args("")).purchaseOrders().getFirst().lines())
                .extracting(FindPurchaseOrdersTool.Line::stillToReceive).containsExactly(0);

        UUID pending = UUID.randomUUID();
        when(procurement.list(any())).thenReturn(PageResponse.of(List.of(
                po(pending, "PO-10", "PENDING_APPROVAL", "NOT_SENT", TODAY.plusDays(5), null, List.of())), 0, 50, 1));
        when(procurement.findById(pending)).thenReturn(Optional.of(po(pending, "PO-10", "PENDING_APPROVAL",
                "NOT_SENT", TODAY.plusDays(5), null, List.of(line(1, "EVAL-LID-90", 1000, 0, "OPEN")))));
        assertThat(tool.run(args("")).purchaseOrders().getFirst().lines())
                .extracting(FindPurchaseOrdersTool.Line::ordered, FindPurchaseOrdersTool.Line::stillToReceive)
                .containsExactly(tuple(1000, 0));
    }

    @Test
    @DisplayName("among several matches only the PO whose number was typed in full is read in detail")
    void numberTypedInFull() {
        UUID asked = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(procurement.list(any())).thenReturn(PageResponse.of(List.of(
                po(other, "PO-PLAY-00010", "CONFIRMED", "PENDING", TODAY.plusDays(5), null, List.of()),
                po(asked, "PO-PLAY-0001", "CONFIRMED", "PENDING", TODAY.plusDays(5), null, List.of())), 0, 50, 2));
        when(procurement.findById(asked)).thenReturn(Optional.of(po(asked, "PO-PLAY-0001", "CONFIRMED", "PENDING",
                TODAY.plusDays(5), null, List.of(line(1, "EVAL-CUP-12OZ", 500, 0, "OPEN")))));

        List<FindPurchaseOrdersTool.Row> found = tool.run(args("po-play-0001", "")).purchaseOrders();

        assertThat(found).extracting(FindPurchaseOrdersTool.Row::poNumber, FindPurchaseOrdersTool.Row::lineCount)
                .containsExactly(tuple("PO-PLAY-00010", null), tuple("PO-PLAY-0001", 1));
        verify(procurement, never()).findById(other);
    }

    @Test
    @DisplayName("a list of several POs carries no lines and reads no detail")
    void listsCarryNoLines() {
        List<FindPurchaseOrdersTool.Row> found = rows(po("PO-1", "CONFIRMED", "PENDING", TODAY.plusDays(5), null),
                po("PO-2", "DRAFT", "NOT_SENT", TODAY.plusDays(5), null));

        assertThat(found).allSatisfy(row -> assertThat(row.lines()).isNull());
        verify(procurement, never()).findById(any());
    }

    @Test
    @DisplayName("a total above the rows returned is flagged, so the answer can say some were left out")
    void truncated() {
        when(procurement.list(any())).thenReturn(PageResponse.of(
                List.of(po("PO-4", "DRAFT", "NOT_SENT", TODAY.plusDays(5), null)), 0, 1, 120));
        FindPurchaseOrdersTool.Answer cut = tool.run(args(""));
        when(procurement.list(any())).thenReturn(PageResponse.of(
                List.of(po("PO-5", "DRAFT", "NOT_SENT", TODAY.plusDays(5), null)), 0, 50, 1));
        FindPurchaseOrdersTool.Answer whole = tool.run(args(""));

        assertThat(cut.total()).isEqualTo(120);
        assertThat(cut.truncated()).isTrue();
        assertThat(whole.truncated()).isFalse();
    }

    @Test
    @DisplayName("several suppliers match: candidates and a note, and no purchase order is searched")
    void severalSuppliers() {
        when(suppliers.list(0, SupplierMatch.MAX_CANDIDATES + 1, "Xưởng", null, null)).thenReturn(PageResponse.of(
                List.of(supplier("GOVIET", "Xưởng gỗ Việt"), supplier("INNHANH", "Xưởng in Nhanh")), 0, 6, 2));

        FindPurchaseOrdersTool.Answer answer = tool.run(args("Xưởng"));

        assertThat(answer.purchaseOrders()).isEmpty();
        assertThat(answer.supplierCandidates()).extracting(SupplierMatch.Candidate::supplierCode)
                .containsExactly("GOVIET", "INNHANH");
        assertThat(answer.note()).contains("Several suppliers match");
        verify(procurement, never()).list(any());
    }

    private static SupplierSummary supplier(String code, String name) {
        return new SupplierSummary(UUID.randomUUID(), code, name, "Contact Person", "sales@example.test",
                "0900000002", "0300000001", "ACTIVE", 30, 14, "EMAIL", null, BigDecimal.ZERO, false, BigDecimal.ZERO,
                Instant.now(), Instant.now());
    }
}
