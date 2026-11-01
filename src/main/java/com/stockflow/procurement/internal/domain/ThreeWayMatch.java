package com.stockflow.procurement.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The three-way match of a supplier invoice (SCRUM-309, BR-INV-002, kltn-docs 04 §4): every invoice line against what
 * its PO line ordered at what price, and what its receipts brought in and QC passed, less what MATCHED invoices billed
 * already. Pure: the caller reads the {@link PoLineBasis facts} under the purchase orders' locks and records what this
 * returns.
 *
 * <ul>
 *   <li><b>Quantity</b> (SCRUM-313; kltn-docs 04 BR-02, BR-05): a PO line is billed for units received, less what QC
 *       rejected, never twice. Units QC has not concluded on - not inspected, or held in quarantine for a second
 *       decision - are not billable yet: billing them is {@link InvoiceVarianceKind#AWAITING_QC}, billing beyond them
 *       {@link InvoiceVarianceKind#OVER_INVOICED}. Lines of one invoice on one PO line add up. A line naming its receipt
 *       line is held to that delivery as well.</li>
 *   <li><b>Price</b> (SCRUM-314): the unit price against the PO line's net price (its price less its discount); off by
 *       more than {@code tolerancePercent} of it, either way, is {@link InvoiceVarianceKind#PRICE}. A free PO line
 *       (net price 0) billed at any price is one too.</li>
 *   <li><b>Tax</b>: the rate is the PO line's (kltn-docs 04 "khớp thuế/phí theo điều khoản PO").</li>
 *   <li><b>Subcontract</b> (BR-PRD-17, kltn-docs 04 BR-07): a SUBCONTRACT order is billed for the units its settlement
 *       passed - nothing while the settlement waits ({@link InvoiceVarianceKind#SETTLEMENT_PENDING}) - and, across the
 *       order's MATCHED invoices and this one, no more than the settlement's net payable (with the same tolerance):
 *       the loss deduction is the supplier's to take off.</li>
 * </ul>
 *
 * <p>Amounts are before tax: tax is a rate check of its own, and the settlement is net.</p>
 */
public final class ThreeWayMatch {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ThreeWayMatch() {
    }

    /**
     * What the order and its receipts say about one PO line.
     *
     * @param netUnitPrice the agreed price less the line's discount
     * @param billable     units received less those QC rejected or has not concluded on; for a SUBCONTRACT order the
     *                     units its final settlement passed
     * @param awaitingQc   units received that QC has not concluded on (not inspected, or quarantined)
     * @param invoiced     units MATCHED (or paid) invoices billed already, this invoice excluded
     */
    public record PoLineBasis(UUID poLineId, UUID purchaseOrderId, boolean subcontract, BigDecimal netUnitPrice,
                              BigDecimal taxRate, int billable, int awaitingQc, int invoiced) {

        public PoLineBasis {
            Objects.requireNonNull(poLineId, "poLineId");
            Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
            Objects.requireNonNull(netUnitPrice, "netUnitPrice");
            Objects.requireNonNull(taxRate, "taxRate");
        }
    }

    /** The same for one receipt line: its billable units, its units awaiting QC, and what was billed against it. */
    public record ReceiptLineBasis(UUID receiptLineId, int billable, int awaitingQc, int invoiced) {
    }

    /**
     * A SUBCONTRACT order's settlement: {@code settled} once it is FINAL, with the units it passed and its net payable;
     * {@code invoicedAmount} what the order's MATCHED invoices billed already, before tax.
     */
    public record SettlementBasis(UUID purchaseOrderId, boolean settled, int passedQuantity, BigDecimal netPayable,
                                  BigDecimal invoicedAmount) {
    }

    /**
     * @param poLines          every PO line the invoice bills
     * @param receiptLines     every receipt line it names
     * @param settlements      every SUBCONTRACT order it bills
     * @param tolerancePercent how far a unit price may stray from the net price, in percent of it
     * @return what is wrong, in the order of the invoice's lines, then the orders' settlements; empty when it matches
     */
    public static List<SupplierInvoice.Variance> match(List<SupplierInvoice.Line> lines,
                                                       Map<UUID, PoLineBasis> poLines,
                                                       Map<UUID, ReceiptLineBasis> receiptLines,
                                                       Map<UUID, SettlementBasis> settlements,
                                                       BigDecimal tolerancePercent, Supplier<UUID> ids) {
        Objects.requireNonNull(tolerancePercent, "tolerancePercent");
        List<SupplierInvoice.Variance> found = new ArrayList<>();
        Map<UUID, Integer> billedOnPoLine = new HashMap<>();
        Map<UUID, Integer> billedOnReceiptLine = new HashMap<>();
        Map<UUID, BigDecimal> billedOnSubcontract = new LinkedHashMap<>();

        for (SupplierInvoice.Line line : lines) {
            PoLineBasis po = Objects.requireNonNull(poLines.get(line.purchaseOrderLineId()),
                    () -> "No basis for PO line " + line.purchaseOrderLineId());
            int before = billedOnPoLine.getOrDefault(po.poLineId(), 0);
            billedOnPoLine.put(po.poLineId(), before + line.quantity());
            int left = po.billable() - po.invoiced() - before;

            boolean quantityFlagged = false;
            if (line.quantity() > left) {
                quantityFlagged = true;
                InvoiceVarianceKind kind;
                if (po.subcontract() && !settlementOf(settlements, po).settled()) {
                    kind = InvoiceVarianceKind.SETTLEMENT_PENDING;
                } else if (line.quantity() <= left + po.awaitingQc()) {
                    kind = InvoiceVarianceKind.AWAITING_QC;
                } else {
                    kind = InvoiceVarianceKind.OVER_INVOICED;
                }
                found.add(quantity(ids, line, kind, left));
            }

            if (line.receiptLineId() != null) {
                ReceiptLineBasis receipt = Objects.requireNonNull(receiptLines.get(line.receiptLineId()),
                        () -> "No basis for receipt line " + line.receiptLineId());
                int earlier = billedOnReceiptLine.getOrDefault(receipt.receiptLineId(), 0);
                billedOnReceiptLine.put(receipt.receiptLineId(), earlier + line.quantity());
                int leftOnReceipt = receipt.billable() - receipt.invoiced() - earlier;
                // The order-level variance already says the units are not there; the delivery adds nothing then.
                if (!quantityFlagged && line.quantity() > leftOnReceipt) {
                    InvoiceVarianceKind kind = line.quantity() <= leftOnReceipt + receipt.awaitingQc()
                            ? InvoiceVarianceKind.AWAITING_QC : InvoiceVarianceKind.OVER_INVOICED_RECEIPT;
                    found.add(quantity(ids, line, kind, leftOnReceipt));
                }
            }

            if (offPrice(line.unitPrice(), po.netUnitPrice(), tolerancePercent)) {
                found.add(new SupplierInvoice.Variance(ids.get(), line.id(), null, InvoiceVarianceKind.PRICE,
                        po.netUnitPrice(), line.unitPrice(), line.unitPrice().subtract(po.netUnitPrice())
                        .multiply(BigDecimal.valueOf(line.quantity())).setScale(2, RoundingMode.HALF_UP)));
            }

            if (line.taxRate().compareTo(po.taxRate()) != 0) {
                found.add(new SupplierInvoice.Variance(ids.get(), line.id(), null, InvoiceVarianceKind.TAX_RATE,
                        po.taxRate(), line.taxRate(), line.netAmount().multiply(line.taxRate().subtract(po.taxRate()))
                        .divide(HUNDRED, 2, RoundingMode.HALF_UP)));
            }

            if (po.subcontract()) {
                billedOnSubcontract.merge(po.purchaseOrderId(), line.netAmount(), BigDecimal::add);
            }
        }

        billedOnSubcontract.forEach((orderId, amount) -> {
            SettlementBasis settlement = Objects.requireNonNull(settlements.get(orderId),
                    () -> "No settlement basis for order " + orderId);
            if (!settlement.settled()) {
                return;
            }
            BigDecimal ceiling = settlement.netPayable().multiply(HUNDRED.add(tolerancePercent))
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal billed = settlement.invoicedAmount().add(amount);
            if (billed.compareTo(ceiling) > 0) {
                found.add(new SupplierInvoice.Variance(ids.get(), null, orderId, InvoiceVarianceKind.ABOVE_SETTLEMENT,
                        settlement.netPayable().subtract(settlement.invoicedAmount()).max(BigDecimal.ZERO), amount,
                        billed.subtract(settlement.netPayable())));
            }
        });
        return found;
    }

    private static SettlementBasis settlementOf(Map<UUID, SettlementBasis> settlements, PoLineBasis po) {
        return Objects.requireNonNull(settlements.get(po.purchaseOrderId()),
                () -> "No settlement basis for order " + po.purchaseOrderId());
    }

    /** The units beyond what was left, at the invoice's price: what paying the line as billed would overpay. */
    private static SupplierInvoice.Variance quantity(Supplier<UUID> ids, SupplierInvoice.Line line,
                                                     InvoiceVarianceKind kind, int left) {
        int expected = Math.max(left, 0);
        return new SupplierInvoice.Variance(ids.get(), line.id(), null, kind, BigDecimal.valueOf(expected),
                BigDecimal.valueOf(line.quantity()), line.unitPrice()
                .multiply(BigDecimal.valueOf(line.quantity() - expected)).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Off by more than {@code tolerancePercent} of the expected price, either way: a supplier billing well below the
     * order is as likely a wrong line as a gift. Exactly at the tolerance passes.
     */
    static boolean offPrice(BigDecimal invoiced, BigDecimal expected, BigDecimal tolerancePercent) {
        if (expected.signum() == 0) {
            return invoiced.signum() != 0;
        }
        BigDecimal allowed = expected.abs().multiply(tolerancePercent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return invoiced.subtract(expected).abs().compareTo(allowed) > 0;
    }
}
