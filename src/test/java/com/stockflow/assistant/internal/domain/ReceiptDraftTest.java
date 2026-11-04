package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reading of a delivery note becomes, rule by rule, with no model and no Spring: the model's values are never
 * taken as they came, and every check the receipt screen makes is flagged first under the screen's own code.
 */
class ReceiptDraftTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    /** Line 1: cups, not lot-tracked. Line 2: ink, lot and expiry tracked, 200 of 500 received. */
    private static final List<ReceiptDraft.OrderLine> OPEN = List.of(
            new ReceiptDraft.OrderLine(1, "CUP-KRAFT-12OZ", 5000, 0, false, false),
            new ReceiptDraft.OrderLine(2, "INK-FOOD-BLUE", 500, 200, true, true));

    private static final ReceiptDraft.Rules RULES = new ReceiptDraft.Rules(new BigDecimal("5"), TODAY, 30);

    private static ReceiptDraft draft(ReceiptDraft.ReadLine... read) {
        return ReceiptDraft.of("PO-HCM-DEMO-0002", "Giấy Việt", OPEN, List.of(read), RULES);
    }

    private static ReceiptDraft.ReadLine cups(Integer quantity) {
        return new ReceiptDraft.ReadLine(1, quantity, "", "", "Cốc giấy kraft 12oz  CUP-KRAFT-12OZ  " + quantity);
    }

    private static ReceiptDraft.ReadLine ink(Integer quantity, String lot, String expiry) {
        return new ReceiptDraft.ReadLine(2, quantity, lot, expiry, "Mực in thực phẩm xanh, lô " + lot + ", HSD " + expiry);
    }

    @Test
    @DisplayName("a line naming an open purchase-order line is proposed with the order's figures and nothing to check")
    void matching() {
        ReceiptDraft draft = draft(cups(1200), ink(300, "L2401", "2027-11-07"));

        assertThat(draft.poNumber()).isEqualTo("PO-HCM-DEMO-0002");
        assertThat(draft.supplierName()).isEqualTo("Giấy Việt");
        assertThat(draft.lines()).hasSize(2);
        ReceiptDraft.Line cups = draft.lines().get(0);
        assertThat(cups.poLineNo()).isEqualTo(1);
        assertThat(cups.sku()).isEqualTo("CUP-KRAFT-12OZ");
        assertThat(cups.ordered()).isEqualTo(5000);
        assertThat(cups.outstanding()).isEqualTo(5000);
        assertThat(cups.proposedQuantity()).isEqualTo(1200);
        assertThat(cups.lotNumber()).as("not lot-tracked: the receipt refuses a lot").isNull();
        assertThat(cups.expiryDate()).isNull();
        assertThat(cups.warnings()).isEmpty();
        ReceiptDraft.Line ink = draft.lines().get(1);
        assertThat(ink.outstanding()).isEqualTo(300);
        assertThat(ink.lotNumber()).isEqualTo("L2401");
        assertThat(ink.expiryDate()).isEqualTo(LocalDate.of(2027, 11, 7));
        assertThat(ink.warnings()).isEmpty();
        assertThat(draft.unmatched()).isEmpty();
        assertThat(draft.warnings()).isEmpty();
    }

    @Test
    @DisplayName("more than is still to receive is proposed as read and flagged, across the note's lines; beyond the "
            + "tolerance it is flagged as the receipt screen will refuse it")
    void overDelivery() {
        // 300 still to receive on line 2; tolerance 5% of 500 allows up to 525 in all, so 325 more.
        ReceiptDraft within = draft(ink(200, "L1", "2027-11-07"), ink(110, "L2", "2027-11-07"));
        ReceiptDraft beyond = draft(ink(200, "L1", "2027-11-07"), ink(126, "L2", "2027-11-07"));

        assertThat(within.lines()).allSatisfy(line -> {
            assertThat(line.warnings()).containsExactly(ReceiptDraft.OVER_DELIVERY);
            assertThat(line.proposedQuantity()).as("never capped").isIn(200, 110);
        });
        assertThat(beyond.lines()).allSatisfy(line -> assertThat(line.warnings())
                .containsExactly(ReceiptDraft.OVER_DELIVERY, ReceiptDraft.OVER_RECEIPT_TOLERANCE));
        assertThat(draft(ink(300, "L1", "2027-11-07")).lines().getFirst().warnings())
                .as("exactly what is still to receive").isEmpty();
    }

    @Test
    @DisplayName("an expiry on or before the receiving day is kept and flagged; one inside the warning window warns")
    void expiry() {
        ReceiptDraft draft = draft(ink(10, "A", "2026-10-28"), ink(10, "B", "2026-10-29"), ink(10, "C", "2026-11-28"),
                ink(10, "D", "2026-11-29"));

        assertThat(draft.lines()).extracting(ReceiptDraft.Line::expiryDate).containsExactly(
                LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 28),
                LocalDate.of(2026, 11, 29));
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::warnings).containsExactly(
                List.of(ReceiptDraft.EXPIRY_NOT_AFTER_RECEIPT), List.of(ReceiptDraft.EXPIRY_NOT_AFTER_RECEIPT),
                List.of(ReceiptDraft.EXPIRY_NEAR), List.of());
    }

    @Test
    @DisplayName("a date that is not a real YYYY-MM-DD date is no date: never re-read day first or month first")
    void unreadableExpiry() {
        ReceiptDraft draft = draft(ink(10, "A", "2027-02-30"), ink(10, "B", "07/11/2027"), ink(10, "C", ""),
                ink(10, "D", null));

        assertThat(draft.lines()).extracting(ReceiptDraft.Line::expiryDate).containsOnlyNulls();
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::warnings).containsExactly(
                List.of(ReceiptDraft.EXPIRY_UNREADABLE), List.of(ReceiptDraft.EXPIRY_UNREADABLE),
                List.of(ReceiptDraft.EXPIRY_REQUIRED), List.of(ReceiptDraft.EXPIRY_REQUIRED));
    }

    @Test
    @DisplayName("a lot-tracked line needs a lot a receipt line can hold; an untracked one never carries one")
    void lots() {
        ReceiptDraft draft = draft(ink(10, " ", "2027-11-07"), ink(10, "L".repeat(65), "2027-11-07"),
                ink(10, "  L-7 ", "2027-11-07"),
                new ReceiptDraft.ReadLine(1, 10, "LOT-99", "2027-11-07", "cups with a lot and a date"),
                ink(10, "-", "2027-11-07"), ink(10, "n/a", "2027-11-07"));

        assertThat(draft.lines()).extracting(ReceiptDraft.Line::lotNumber)
                .containsExactly(null, null, "L-7", null, null, null);
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::warnings).containsExactly(
                List.of(ReceiptDraft.LOT_REQUIRED), List.of(ReceiptDraft.LOT_UNREADABLE), List.of(), List.of(),
                List.of(ReceiptDraft.LOT_REQUIRED), List.of(ReceiptDraft.LOT_REQUIRED));
        assertThat(draft.lines().get(3).expiryDate()).as("cups are not expiry-tracked").isNull();
    }

    @Test
    @DisplayName("no positive whole number read is no quantity, flagged, and adds nothing to the line's total")
    void unreadableQuantity() {
        ReceiptDraft draft = draft(cups(0), cups(null), cups(-5), cups(5000));

        assertThat(draft.lines()).extracting(ReceiptDraft.Line::proposedQuantity)
                .containsExactly(null, null, null, 5000);
        assertThat(draft.lines().subList(0, 3)).allSatisfy(line -> assertThat(line.warnings())
                .contains(ReceiptDraft.QUANTITY_UNREADABLE).doesNotContain(ReceiptDraft.OVER_DELIVERY));
        assertThat(draft.lines().get(3).warnings()).doesNotContain(ReceiptDraft.OVER_DELIVERY);
    }

    @Test
    @DisplayName("a line naming no line, a line the order does not have, or one not open is listed apart, unchanged")
    void unmatched() {
        ReceiptDraft draft = draft(
                new ReceiptDraft.ReadLine(0, 40, "", "", "Pallet gỗ (trả lại)"),
                new ReceiptDraft.ReadLine(7, 12, "", "", "Nắp ly 90mm"),
                new ReceiptDraft.ReadLine(null, null, null, null, "Ống hút giấy"),
                new ReceiptDraft.ReadLine(null, 0, null, null, "  "),
                cups(100));

        assertThat(draft.unmatched()).containsExactly(
                new ReceiptDraft.Unmatched("Pallet gỗ (trả lại)", 40),
                new ReceiptDraft.Unmatched("Nắp ly 90mm", 12),
                new ReceiptDraft.Unmatched("Ống hút giấy", null));
        assertThat(draft.lines()).extracting(ReceiptDraft.Line::poLineNo).containsExactly(1);
        assertThat(draft.warnings()).isEmpty();

        // A line already received is not among the open lines the draft is given: the same as one the order lacks.
        ReceiptDraft closed = ReceiptDraft.of("PO-1", "S", List.of(OPEN.get(0)), List.of(ink(10, "L", "2027-11-07")),
                RULES);
        assertThat(closed.lines()).isEmpty();
        assertThat(closed.unmatched()).extracting(ReceiptDraft.Unmatched::quantity).containsExactly(10);
    }

    @Test
    @DisplayName("the same purchase-order line and lot twice is flagged on both; two unread lots are not")
    void duplicates() {
        ReceiptDraft draft = draft(cups(10), cups(20), ink(5, "L1", "2027-11-07"), ink(5, "L1", "2027-11-07"),
                ink(5, "L2", "2027-11-07"), ink(5, "", "2027-11-07"), ink(5, "", "2027-11-07"));

        assertThat(draft.lines()).extracting(line -> line.warnings().contains(ReceiptDraft.DUPLICATE_RECEIPT_LINE))
                .containsExactly(true, true, true, true, false, false, false);
    }

    @Test
    @DisplayName("nothing read says so; more than a hundred lines keeps the first hundred and says so")
    void wholeDraft() {
        assertThat(draft().warnings()).containsExactly(ReceiptDraft.NOTHING_READ);
        assertThat(draft(new ReceiptDraft.ReadLine(null, null, null, null, null)).warnings())
                .containsExactly(ReceiptDraft.NOTHING_READ);

        List<ReceiptDraft.ReadLine> many = new ArrayList<>();
        for (int i = 0; i < ReceiptDraft.MAX_LINES + 5; i++) {
            many.add(new ReceiptDraft.ReadLine(0, 1, "", "", "line " + i));
        }
        ReceiptDraft crowded = ReceiptDraft.of("PO-1", "S", OPEN, many, RULES);
        assertThat(crowded.unmatched()).hasSize(ReceiptDraft.MAX_LINES);
        assertThat(crowded.warnings()).containsExactly(ReceiptDraft.TOO_MANY_LINES);
    }

    @Test
    @DisplayName("the text read is one line of at most 300 characters, control characters gone")
    void readText() {
        ReceiptDraft draft = draft(new ReceiptDraft.ReadLine(1, 1, "", "", "Cốc\n\tgiấy\u0000  12oz"),
                new ReceiptDraft.ReadLine(1, 1, "", "", "x".repeat(400)));

        assertThat(draft.lines().get(0).readText()).isEqualTo("Cốc giấy 12oz");
        assertThat(draft.lines().get(1).readText()).hasSize(ReceiptDraft.MAX_READ_TEXT + 1).endsWith("…");
    }
}
