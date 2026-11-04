package com.stockflow.assistant.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A goods receipt proposed from a photographed delivery note (design §8.1, FEAT-10): what the goods-receipt screen
 * prefills for the person counting the delivery, to check against the paper and correct. Nothing in it is written
 * anywhere; the receipt is created, counted and confirmed on that screen, under its own checks, as before.
 *
 * <p><b>The model reads, this class decides.</b> A misread expiry or lot is not a cosmetic error: FEFO (BR-STK-006)
 * and lot trace run on them for every order the stock feeds, and a recall that trusts a wrong lot misses customers. So
 * nothing the model wrote is taken as it came. A line is proposed against a purchase-order line only when the model
 * named one of the order's <em>open</em> lines; the quantity is a positive whole number or nothing; a date is a real
 * calendar date or nothing; and the receipt screen's checks of what was read are made here first and flagged under the
 * code that screen refuses with ({@code LOT_REQUIRED}, {@code EXPIRY_REQUIRED}, {@code EXPIRY_NOT_AFTER_RECEIPT},
 * {@code OVER_RECEIPT_TOLERANCE}, {@code DUPLICATE_RECEIPT_LINE}) or warns with ({@code EXPIRY_NEAR}), so a person
 * reads the same words on both. The receiving location is not read and not proposed: the screen asks for it.</p>
 *
 * <p><b>Flagged, never corrected.</b> A quantity above what is still to receive is proposed as read and flagged
 * {@link #OVER_DELIVERY}: capping it would hide goods that are physically on the dock. An expiry already past is kept
 * and flagged: either the goods are expired or the date was misread, and only the person holding the paper can tell.
 * A lot or date the item is not tracked by is left out, because the receipt screen refuses one there; the printed
 * value stays visible in {@code readText}.</p>
 *
 * <p><b>Limits.</b> {@link OrderLine#received()} counts confirmed receipts only, while the receipt screen's tolerance
 * check also counts drafts not yet confirmed: a second draft for the same delivery is caught there, not here. Units
 * are the purchase order's; a note that counts in cartons without printing what a carton holds reads as
 * {@link #QUANTITY_UNREADABLE} by instruction, and nothing here can tell whether a model converted correctly.</p>
 *
 * @param warnings codes about the draft as a whole: {@link #NOTHING_READ}, {@link #TOO_MANY_LINES}
 */
public record ReceiptDraft(String poNumber, String supplierName, List<Line> lines, List<Unmatched> unmatched,
                           List<String> warnings) {

    /** Lines of one note that are proposed; the rest are dropped with {@link #TOO_MANY_LINES}. */
    public static final int MAX_LINES = 100;

    /** Characters of the text read for a line that are returned: enough for a product name, codes, lot and date. */
    public static final int MAX_READ_TEXT = 300;

    /** The longest lot number a receipt line takes ({@code ReceiptLinesRequest.lotNumber}). */
    public static final int MAX_LOT = 64;

    // ---------------------------------------------------------------------------------- about the whole draft

    /** No usable line: the photo is not a delivery note, could not be read, or the provider declined to read it. */
    public static final String NOTHING_READ = "NOTHING_READ";

    /** The note had more lines than {@value #MAX_LINES}; only the first ones are proposed. */
    public static final String TOO_MANY_LINES = "TOO_MANY_LINES";

    // ---------------------------------------------------------------------------------- about one line

    /** No positive whole number of units was read for the line. */
    public static final String QUANTITY_UNREADABLE = "QUANTITY_UNREADABLE";

    /** The note's lines for this purchase-order line add up to more than is still to receive on it. */
    public static final String OVER_DELIVERY = "OVER_DELIVERY";

    /** They add up to more than the supplier's over-receipt tolerance allows: the receipt screen refuses it (BR-02). */
    public static final String OVER_RECEIPT_TOLERANCE = "OVER_RECEIPT_TOLERANCE";

    /** The SKU is lot-tracked and no lot number was read (BR-03). */
    public static final String LOT_REQUIRED = "LOT_REQUIRED";

    /** Something was read as the lot number, but nothing a receipt line can hold. */
    public static final String LOT_UNREADABLE = "LOT_UNREADABLE";

    /** The SKU is expiry-tracked and no expiry date was read (BR-03). */
    public static final String EXPIRY_REQUIRED = "EXPIRY_REQUIRED";

    /** Something was read as the expiry date, but not a real calendar date. */
    public static final String EXPIRY_UNREADABLE = "EXPIRY_UNREADABLE";

    /** The expiry date is not after the receiving date: the receipt screen refuses it (BR-06). */
    public static final String EXPIRY_NOT_AFTER_RECEIPT = "EXPIRY_NOT_AFTER_RECEIPT";

    /** The expiry date falls within the warning window of the receiving date (BR-06): a warning, as on the receipt. */
    public static final String EXPIRY_NEAR = "EXPIRY_NEAR";

    /** Another line of the note has the same purchase-order line and lot: the receipt takes one line per pair. */
    public static final String DUPLICATE_RECEIPT_LINE = "DUPLICATE_RECEIPT_LINE";

    public ReceiptDraft {
        lines = List.copyOf(lines);
        unmatched = List.copyOf(unmatched);
        warnings = List.copyOf(warnings);
    }

    /**
     * One open line of the purchase order, as procurement reports it: OPEN or PARTIALLY_RECEIVED.
     *
     * @param received units the confirmed receipts counted in against it; a receipt not yet confirmed is not in it
     */
    public record OrderLine(int lineNo, String sku, int ordered, int received, boolean lotTracked,
                            boolean expiryTracked) {

        /** What the supplier still has to deliver on the line. */
        public int outstanding() {
            return Math.max(0, ordered - received);
        }

        /**
         * What may still be received before the receipt screen refuses: ordered × (1 + tolerance / 100), rounded
         * down, less what is received, as {@code ReceivingPurchaseOrder.Line.receivableLimit} counts it.
         */
        long receivable(BigDecimal tolerancePercent) {
            return BigDecimal.valueOf(ordered).multiply(BigDecimal.ONE.add(tolerancePercent.movePointLeft(2)))
                    .setScale(0, RoundingMode.FLOOR).longValue() - received;
        }
    }

    /**
     * One goods line as the model read it. Every field is the model's and may be anything; the caller turns a value of
     * the wrong JSON type into null rather than guessing.
     *
     * @param poLineNo   the purchase-order line the model matched; 0 or null for none
     * @param quantity   units delivered; 0 or null when unreadable
     * @param lotNumber  as printed; blank for none
     * @param expiryDate {@code YYYY-MM-DD}; blank for none
     * @param readText   the line as the model read it
     */
    public record ReadLine(Integer poLineNo, Integer quantity, String lotNumber, String expiryDate, String readText) {
    }

    /**
     * The rules that are not the order's own.
     *
     * @param tolerancePercent  the supplier's over-receipt tolerance (BR-02); zero when none is set
     * @param receivedOn        the business date a receipt created now would carry: expiry is judged against it
     * @param expiryWarningDays BR-06's window ({@code stockflow.inventory.expiry-warning-days})
     */
    public record Rules(BigDecimal tolerancePercent, LocalDate receivedOn, int expiryWarningDays) {

        public Rules {
            tolerancePercent = tolerancePercent == null ? BigDecimal.ZERO : tolerancePercent;
            Objects.requireNonNull(receivedOn, "receivedOn");
        }
    }

    /**
     * A note line matched to an open purchase-order line: what the receipt screen prefills, and what to check.
     *
     * @param outstanding      what the supplier still has to deliver on the purchase-order line
     * @param proposedQuantity the units read; null when none could be. Never capped
     * @param lotNumber        as read; null when none was, or when the SKU is not lot-tracked
     * @param expiryDate       as read; null when none was, or when the SKU is not expiry-tracked. Kept when past
     * @param readText         what the model read, for the person to compare with the paper
     * @param warnings         what to check before saving, as codes (the constants of {@link ReceiptDraft})
     */
    public record Line(int poLineNo, String sku, int ordered, int outstanding, Integer proposedQuantity,
                       String lotNumber, LocalDate expiryDate, String readText, List<String> warnings) {

        public Line {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * A note line that matches no open line of the order: the model named none, named a line the order does not
     * have, or named one that is already received, closed or cancelled. Listed so nothing on the paper is lost.
     *
     * @param quantity units read; null when none could be
     */
    public record Unmatched(String readText, Integer quantity) {
    }

    /**
     * Matches what was read to the order and checks it.
     *
     * @param openLines the order's OPEN and PARTIALLY_RECEIVED lines; a line named that is not here is unmatched
     * @param read      what the model read, top to bottom
     */
    public static ReceiptDraft of(String poNumber, String supplierName, List<OrderLine> openLines,
                                  List<ReadLine> read, Rules rules) {
        Map<Integer, OrderLine> open = new HashMap<>();
        openLines.forEach(line -> open.put(line.lineNo(), line));
        List<String> warnings = new ArrayList<>();
        List<ReadLine> considered = read;
        if (read.size() > MAX_LINES) {
            considered = read.subList(0, MAX_LINES);
            warnings.add(TOO_MANY_LINES);
        }

        List<Candidate> matched = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (ReadLine line : considered) {
            String text = readText(line.readText());
            Integer quantity = line.quantity() != null && line.quantity() > 0 ? line.quantity() : null;
            OrderLine order = line.poLineNo() == null ? null : open.get(line.poLineNo());
            if (order != null) {
                matched.add(checked(order, quantity, line, text, rules));
            } else if (!text.isEmpty() || quantity != null) {
                // An entry with neither text nor a quantity says nothing a person could act on.
                unmatched.add(new Unmatched(text, quantity));
            }
        }

        // What the note delivers per purchase-order line, and how often each line and lot appears.
        Map<Integer, Long> delivered = new HashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Candidate candidate : matched) {
            if (candidate.quantity() != null) {
                delivered.merge(candidate.order().lineNo(), (long) candidate.quantity(), Long::sum);
            }
            candidate.key().ifPresent(key -> occurrences.merge(key, 1, Integer::sum));
        }
        List<Line> lines = new ArrayList<>();
        for (Candidate candidate : matched) {
            OrderLine order = candidate.order();
            List<String> flags = new ArrayList<>(candidate.warnings());
            if (candidate.quantity() != null) {
                long total = delivered.get(order.lineNo());
                if (total > order.outstanding()) {
                    flags.add(OVER_DELIVERY);
                }
                if (total > order.receivable(rules.tolerancePercent())) {
                    flags.add(OVER_RECEIPT_TOLERANCE);
                }
            }
            if (candidate.key().map(occurrences::get).orElse(0) > 1) {
                flags.add(DUPLICATE_RECEIPT_LINE);
            }
            lines.add(new Line(order.lineNo(), order.sku(), order.ordered(), order.outstanding(), candidate.quantity(),
                    candidate.lot(), candidate.expiry(), candidate.text(), flags));
        }
        if (lines.isEmpty() && unmatched.isEmpty()) {
            warnings.add(NOTHING_READ);
        }
        return new ReceiptDraft(poNumber, supplierName, lines, unmatched, warnings);
    }

    /** What a lot column holds when it holds nothing: in the checks below these read as no lot. */
    private static final java.util.Set<String> NO_LOT = java.util.Set.of("N/A", "NA", "X", "NONE", "KHÔNG", "KHONG",
            "KO");

    /**
     * A mark that fills the lot column of a lot-tracked line, "-", "/", "x", "N/A": proposed as the lot, it would pass
     * every other check and become the lot FEFO and a recall rely on.
     */
    static boolean filler(String printed) {
        return printed.codePoints().noneMatch(Character::isLetterOrDigit)
                || NO_LOT.contains(printed.strip().toUpperCase(java.util.Locale.ROOT));
    }

    /** The checks of one line on its own; those across lines come after. */
    private static Candidate checked(OrderLine order, Integer quantity, ReadLine line, String text, Rules rules) {
        List<String> flags = new ArrayList<>();
        if (quantity == null) {
            flags.add(QUANTITY_UNREADABLE);
        }
        String lot = null;
        if (order.lotTracked()) {
            String printed = blankToNull(line.lotNumber());
            if (printed == null || filler(printed)) {
                flags.add(LOT_REQUIRED);
            } else if (printed.length() > MAX_LOT || printed.chars().anyMatch(Character::isISOControl)) {
                flags.add(LOT_UNREADABLE);
            } else {
                lot = printed;
            }
        }
        LocalDate expiry = null;
        if (order.expiryTracked()) {
            String printed = blankToNull(line.expiryDate());
            if (printed == null) {
                flags.add(EXPIRY_REQUIRED);
            } else {
                expiry = date(printed);
                if (expiry == null) {
                    flags.add(EXPIRY_UNREADABLE);
                } else if (!expiry.isAfter(rules.receivedOn())) {
                    flags.add(EXPIRY_NOT_AFTER_RECEIPT);
                } else if (ChronoUnit.DAYS.between(rules.receivedOn(), expiry) <= rules.expiryWarningDays()) {
                    flags.add(EXPIRY_NEAR);
                }
            }
        }
        return new Candidate(order, quantity, lot, expiry, text, flags);
    }

    /**
     * A matched line before the checks across lines.
     *
     * @param warnings the line's own flags
     */
    private record Candidate(OrderLine order, Integer quantity, String lot, LocalDate expiry, String text,
                             List<String> warnings) {

        /**
         * What the receipt keeps one line for: the purchase-order line and the lot. Empty for a lot-tracked line whose
         * lot was not read: two such lines may well be two lots, and {@link #LOT_REQUIRED} already sends the person to
         * the paper.
         */
        Optional<String> key() {
            if (order.lotTracked() && lot == null) {
                return Optional.empty();
            }
            return Optional.of(order.lineNo() + "|" + Objects.toString(lot, ""));
        }
    }

    /** A real calendar date in {@code YYYY-MM-DD}, or null: 2027-02-30 and 07/11/2027 are both null. */
    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    /**
     * The text as a person reads it: control characters and runs of white space become one space, and at most
     * {@value #MAX_READ_TEXT} characters are kept.
     */
    private static String readText(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (flat.length() <= MAX_READ_TEXT) {
            return flat;
        }
        int end = Character.isHighSurrogate(flat.charAt(MAX_READ_TEXT - 1)) ? MAX_READ_TEXT - 1 : MAX_READ_TEXT;
        return flat.substring(0, end) + "…";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
