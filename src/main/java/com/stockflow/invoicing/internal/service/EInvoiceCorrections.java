package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Draws up the corrections of an issued invoice (WF-05 E4; plan §1.4; TT 91/2026/TT-BTC Điều 10 as read in
 * plans/E0-legal.md). An issued invoice is never cancelled or changed; these are the ways the law gives instead, and
 * each is another invoice - a draft the accountant reads and issues like any other
 * ({@link EInvoiceIssuance}).
 *
 * <ul>
 *   <li><b>An adjustment</b> ({@link #adjust}) - what changes, line by line, signed. For an error (a wrong tax code,
 *       quantity, price, rate or amount: khoản 1 điểm b), for goods returned (khoản 5 điểm c.1), or for a trade
 *       discount by volume or revenue with the list of invoices it covers (khoản 5 điểm b).</li>
 *   <li><b>A replacement</b> ({@link #replace}) - the invoice as it should have been, for an error.</li>
 *   <li><b>The agreement</b> ({@link #attachAgreement}) - the correction of an error waits for the written agreement
 *       of the two parties before it can be sent.</li>
 *   <li><b>Returned goods</b> ({@link #drawUpForReturn}) - when a return's refund is approved, the goods refunded are
 *       taken off the invoices that invoiced them, as draft adjustments for the accountant.</li>
 * </ul>
 *
 * <p>A wrong buyer name or address with the right tax code is none of these: it is a notice and no invoice
 * ({@link EInvoiceErrorNotices}).</p>
 *
 * <p><b>Locks.</b> Every invoice a correction changes is locked before the correction is written, in id order - the
 * order {@link EInvoiceIssuanceSteps#issued} takes them in. Under the lock the invoice's other corrections are read
 * again, so two corrections of one invoice drawn up together are decided one after the other and the form first used
 * for an invoice is kept (khoản 6 điểm a).</p>
 */
@Service
public class EInvoiceCorrections {

    /**
     * One line of a correction, by the line of the invoice corrected it is about.
     *
     * <p>On an adjustment: {@code quantity} is what changes, signed - below zero reduces - in the line's unit;
     * {@code unitPrice} is what each unit changes by (the line's own price when left out, as for a quantity that was
     * wrong; the difference in price when the price was wrong); {@code taxRate} the rate the change is taxed at (the
     * line's own when left out). A rate that was wrong is two lines: the whole line off at the old rate, and on again
     * at the right one.</p>
     *
     * <p>On a replacement: what the line should have said; anything left out stays as it was, and a quantity of zero
     * drops the line.</p>
     */
    public record LineChange(UUID originalLineId, Integer quantity, BigDecimal unitPrice, BigDecimal taxRate,
                             String itemName) {
    }

    /** The buyer as the correction is to name it; a null field stays as the invoice corrected has it. */
    public record Buyer(String name, String taxCode, String address, String email) {

        boolean changesNothing() {
            return name == null && taxCode == null && address == null && email == null;
        }
    }

    /**
     * @param returnRequestId   the return a {@link CorrectionBasis#RETURN} adjustment is for
     * @param listedInvoiceIds  the buyer's other invoices a {@link CorrectionBasis#TRADE_DISCOUNT} adjustment covers
     */
    public record Adjust(UUID originalId, CorrectionBasis basis, String reason, Buyer buyer, List<LineChange> lines,
                         UUID returnRequestId, List<UUID> listedInvoiceIds) {
    }

    public record Replace(UUID originalId, String reason, Buyer buyer, List<LineChange> lines) {
    }

    /**
     * What became of a return's goods on the order's invoices.
     *
     * @param drafts    the adjustments drawn up, one per invoice reached
     * @param unplaced  pieces refunded, by order line, that no issued invoice in force could take: not invoiced yet,
     *                  on an invoice being replaced, or part of a carton whose price does not divide into whole dong.
     *                  The accountant adjusts for them by hand
     */
    public record ReturnAdjustments(List<EInvoice.Snapshot> drafts, Map<UUID, Long> unplaced) {
    }

    private final EInvoiceRepository invoices;
    private final ReturnRequestLookup returns;
    private final EInvoiceAgreements agreements;
    private final FileTransfers files;
    private final Clock clock;

    EInvoiceCorrections(EInvoiceRepository invoices, ReturnRequestLookup returns, EInvoiceAgreements agreements,
                        FileTransfers files, Clock clock) {
        this.invoices = invoices;
        this.returns = returns;
        this.agreements = agreements;
        this.files = files;
        this.clock = clock;
    }

    /**
     * Draws up an adjustment of an issued invoice, as a draft.
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code RETURN_REQUEST_NOT_FOUND}; {@code VALIDATION_FAILED}
     *         for a line that is not the invoice's, a quantity of zero, a price that is not whole dong, a return of
     *         another order, or a buyer changed on anything but the correction of an error; and what
     *         {@link EInvoice#drawUpAdjustment} refuses
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "einvoice", resourceId = "#command.originalId()")
    @Transactional
    public EInvoice.Snapshot adjust(Adjust command) {
        if (command.basis() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "basis: ERROR, RETURN or TRADE_DISCOUNT");
        }
        Map<UUID, EInvoice> locked = lock(command.originalId(), command.listedInvoiceIds());
        EInvoice original = locked.get(command.originalId());
        InvoiceParty buyer = buyer(original, command.buyer(), command.basis() == CorrectionBasis.ERROR);
        List<EInvoiceLine> lines = new ArrayList<>();
        for (LineChange change : command.lines() == null ? List.<LineChange>of() : command.lines()) {
            EInvoiceLine line = lineOf(original, change.originalLineId());
            if (change.quantity() == null || change.quantity() == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "lines: an adjustment line says how much changes, above or below zero");
            }
            lines.add(line.corrected(Identifiers.newId(), lines.size() + 1, change.quantity(),
                    line.unitsPerCarton(), line.unit(), price(change.unitPrice(), line.unitPrice()),
                    rate(change.taxRate(), line.taxRate()), name(change.itemName(), line.itemName())));
        }
        if (lines.isEmpty() && command.basis() == CorrectionBasis.ERROR && !buyer.equals(original.buyer())
                && command.reason() != null && !command.reason().isBlank()) {
            // The buyer's details were wrong and no money changes: the adjustment still says what it corrects.
            ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
            String text = t.getString("einvoice.correction.note.buyer").formatted(command.reason().strip());
            lines.add(EInvoiceLine.note(Identifiers.newId(), 1, text.substring(0, Math.min(500, text.length())),
                    t.getString("einvoice.unit.note")));
        }
        if (command.basis() == CorrectionBasis.RETURN) {
            requireReturnOf(original, command.returnRequestId());
            requireWithinInvoiced(original, lines);
        }
        List<EInvoice> listed = new ArrayList<>();
        for (UUID listedId : command.listedInvoiceIds() == null ? List.<UUID>of() : command.listedInvoiceIds()) {
            EInvoice other = locked.get(listedId);
            if (invoices.correctionsOf(listedId).liveReplacement()) {
                throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_FORM_FIXED,
                        "Invoice %s is being replaced; it is not listed on an adjustment".formatted(other.code()));
            }
            listed.add(other);
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        EInvoice adjustment = EInvoice.drawUpAdjustment(Identifiers.newId(), invoices.nextCode(today), original,
                command.basis(), command.reason(), buyer, lines, today, command.returnRequestId(), listed,
                invoices.correctionsOf(original.id()));
        return invoices.save(adjustment).snapshot();
    }

    /**
     * Draws up the replacement of an issued invoice, as a draft: its lines as they were, with what is corrected.
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code VALIDATION_FAILED} for a line that is not the
     *         invoice's or a price that is not whole dong; and what {@link EInvoice#drawUpReplacement} refuses
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "einvoice", resourceId = "#command.originalId()")
    @Transactional
    public EInvoice.Snapshot replace(Replace command) {
        EInvoice original = lock(command.originalId(), List.of()).get(command.originalId());
        Map<UUID, LineChange> changes = new HashMap<>();
        for (LineChange change : command.lines() == null ? List.<LineChange>of() : command.lines()) {
            lineOf(original, change.originalLineId());
            changes.put(change.originalLineId(), change);
        }
        List<EInvoiceLine> lines = new ArrayList<>();
        for (EInvoiceLine line : original.lines()) {
            LineChange change = changes.get(line.id());
            int quantity = change == null || change.quantity() == null ? line.quantity() : change.quantity();
            if (quantity == 0) {
                continue;
            }
            lines.add(line.corrected(Identifiers.newId(), lines.size() + 1, quantity, line.unitsPerCarton(),
                    line.unit(), price(change == null ? null : change.unitPrice(), line.unitPrice()),
                    rate(change == null ? null : change.taxRate(), line.taxRate()),
                    name(change == null ? null : change.itemName(), line.itemName())));
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        EInvoice replacement = EInvoice.drawUpReplacement(Identifiers.newId(), invoices.nextCode(today), original,
                command.reason(), buyer(original, command.buyer(), true), lines, today,
                invoices.correctionsOf(original.id()));
        return invoices.save(replacement).snapshot();
    }

    /**
     * Attaches the signed agreement of the two parties to a draft correction of an error: the PDF, its number and its
     * date. The file is checked, scanned and stored before the invoice is locked, and deleted again if the invoice
     * refuses it. Attaching again replaces what was there.
     *
     * @throws BusinessException {@code UNSUPPORTED_MEDIA_TYPE} for anything but a PDF; what
     *         {@link EInvoice#attachAgreement} refuses; {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot attachAgreement(UUID id, long expectedVersion, String number, LocalDate date,
                                             FileUpload upload) {
        if (!"application/pdf".equals(upload.contentType())) {
            // The e-invoice category also holds the invoice's own XML; an agreement is a signed document, a PDF.
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "agreement: a signed PDF");
        }
        try (PreparedFiles prepared = files.prepare(FileCategory.EINVOICE, upload)) {
            return agreements.attach(id, expectedVersion, number, date, prepared);
        }
    }

    /**
     * Goods that came back were refunded (the return's settlement was approved): each order line's refunded pieces are
     * taken off the issued invoices in force that invoiced that line, the oldest first, as far as each still holds
     * them - one draft adjustment per invoice, naming the return (TT 91 Điều 10 khoản 5 điểm c.1; no agreement is
     * needed, the return is the record). The accountant reads and issues them.
     *
     * <p>Whole cartons come off as cartons at the carton's price. Pieces that are not whole cartons come off as pieces,
     * when the carton's price divides into whole dong; otherwise they are left for the accountant, as are pieces no
     * issued invoice holds - the delivery's invoice not issued yet, or being replaced.</p>
     *
     * <p>A return is taken off once: a second call for the same return - the event delivered again - draws nothing up,
     * even if its drafts were voided since.</p>
     *
     * <p>A transaction of its own: its caller, the return's listener, must commit the announcement of what is left to
     * the accountant even when the invoice's rules refuse the adjustment - a refusal joining the caller's transaction
     * would mark it rollback-only and the announcement would go with it.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnAdjustments drawUpForReturn(ReturnResolved resolved) {
        Map<UUID, Long> remaining = new LinkedHashMap<>();
        for (ReturnResolved.Line line : resolved.lines()) {
            if (line.creditQuantity() > 0) {
                remaining.merge(line.orderLineId(), (long) line.creditQuantity(), Long::sum);
            }
        }
        if (remaining.isEmpty() || invoices.hasAdjustmentForReturn(resolved.returnRequestId())) {
            return new ReturnAdjustments(List.of(), Map.of());
        }
        List<EInvoice> candidates = invoices.findInForceOfOrder(resolved.orderId());
        // Locked in id order, like every correction; then read again and walked oldest first.
        new TreeSet<>(candidates.stream().map(EInvoice::id).toList()).forEach(invoices::findByIdForUpdate);
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        LocalDate date = BusinessCalendar.date(resolved.resolvedAt() == null ? clock.instant()
                : resolved.resolvedAt());
        List<EInvoice.Snapshot> drafts = new ArrayList<>();
        for (EInvoice candidate : candidates) {
            EInvoice invoice = invoices.findById(candidate.id()).orElseThrow();
            EInvoice.Corrections corrections = invoices.correctionsOf(invoice.id());
            if (invoice.status() != EInvoiceStatus.ISSUED || corrections.liveReplacement()) {
                continue;
            }
            Map<UUID, Long> taken = invoices.piecesTakenOff(invoice.id());
            List<EInvoiceLine> lines = new ArrayList<>();
            for (EInvoiceLine line : invoice.lines()) {
                Long wanted = line.kind() == EInvoiceLine.Kind.GOODS ? remaining.get(line.orderLineId()) : null;
                if (wanted == null || wanted <= 0) {
                    continue;
                }
                long take = Math.min(wanted, line.pieces() - taken.getOrDefault(line.orderLineId(), 0L));
                EInvoiceLine reduction = take <= 0 ? null : reduction(line, take, lines.size() + 1, t);
                if (reduction == null) {
                    continue;
                }
                lines.add(reduction);
                taken.merge(line.orderLineId(), take, Long::sum);
                remaining.merge(line.orderLineId(), -take, Long::sum);
            }
            if (lines.isEmpty()) {
                continue;
            }
            EInvoice adjustment = EInvoice.drawUpAdjustment(Identifiers.newId(), invoices.nextCode(date), invoice,
                    CorrectionBasis.RETURN, t.getString("einvoice.correction.return")
                            .formatted(resolved.rmaNumber()), invoice.buyer(), lines, date,
                    resolved.returnRequestId(), List.of(), corrections);
            drafts.add(invoices.save(adjustment).snapshot());
        }
        remaining.values().removeIf(left -> left <= 0);
        return new ReturnAdjustments(drafts, Map.copyOf(remaining));
    }

    /** {@code pieces} of {@code line} taken off it: as cartons when they are whole cartons, else as pieces. */
    private static EInvoiceLine reduction(EInvoiceLine line, long pieces, int lineNo, ResourceBundle t) {
        Integer perCarton = line.unitsPerCarton();
        if (perCarton == null || pieces % perCarton == 0) {
            return line.corrected(Identifiers.newId(), lineNo, (int) -(perCarton == null ? pieces : pieces / perCarton),
                    perCarton, line.unit(), line.unitPrice(), line.taxRate(), line.itemName());
        }
        BigDecimal[] piecePrice = line.unitPrice().divideAndRemainder(BigDecimal.valueOf(perCarton));
        if (piecePrice[1].signum() != 0) {
            // A piece of this carton is not worth whole dong: the invoice cannot state it, the accountant will.
            return null;
        }
        return line.corrected(Identifiers.newId(), lineNo, (int) -pieces, null, t.getString("einvoice.unit.piece"),
                piecePrice[0], line.taxRate(), line.itemName());
    }

    /** The invoice corrected and every invoice listed, locked in id order and read afresh. */
    private Map<UUID, EInvoice> lock(UUID originalId, List<UUID> listedIds) {
        TreeSet<UUID> ids = new TreeSet<>(listedIds == null ? List.of() : listedIds);
        ids.add(originalId);
        Map<UUID, EInvoice> locked = new HashMap<>();
        for (UUID id : ids) {
            locked.put(id, invoices.findByIdForUpdate(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)));
        }
        return locked;
    }

    private InvoiceParty buyer(EInvoice original, Buyer changed, boolean mayChange) {
        InvoiceParty current = original.buyer();
        if (changed == null || changed.changesNothing()) {
            return current;
        }
        if (!mayChange) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "buyer: only the correction of an error names the buyer differently");
        }
        return new InvoiceParty(
                changed.name() == null ? current.name() : EInvoiceDrafts.limited(changed.name(), "buyer.name", 200),
                changed.taxCode() == null ? current.taxCode() : EInvoiceDrafts.taxCode(changed.taxCode()),
                changed.address() == null ? current.address()
                        : EInvoiceDrafts.limited(changed.address(), "buyer.address", 500),
                changed.email() == null ? current.email() : EInvoiceDrafts.email(changed.email()));
    }

    private void requireReturnOf(EInvoice original, UUID returnRequestId) {
        if (returnRequestId == null) {
            return;
        }
        ReturnRequestReference reference = returns.find(returnRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND));
        if (!reference.orderId().equals(original.orderId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "returnRequestId: return %s is not of the invoice's order".formatted(reference.rmaNumber()));
        }
        if (invoices.hasLiveReturnAdjustment(returnRequestId, original.id())) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Return %s already has an adjustment of invoice %s".formatted(reference.rmaNumber(),
                            original.code()));
        }
    }

    /**
     * Goods returned are never more than the invoice still holds of their order line: what it invoiced, less what every
     * live adjustment of it that changes a quantity - a return's or an error's - already took off
     * ({@link EInvoiceRepository#piecesTakenOff}).
     */
    private void requireWithinInvoiced(EInvoice original, List<EInvoiceLine> reductions) {
        Map<UUID, Long> held = new HashMap<>();
        for (EInvoiceLine line : original.lines()) {
            if (line.kind() == EInvoiceLine.Kind.GOODS) {
                held.merge(line.orderLineId(), line.pieces(), Long::sum);
            }
        }
        invoices.piecesTakenOff(original.id()).forEach((line, pieces) -> held.merge(line, -pieces, Long::sum));
        for (EInvoiceLine reduction : reductions) {
            if (reduction.kind() == EInvoiceLine.Kind.GOODS
                    && held.merge(reduction.orderLineId(), reduction.pieces(), Long::sum) < 0) {
                throw new BusinessException(ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE,
                        "Line %d returns more of %s than invoice %s still holds".formatted(reduction.lineNo(),
                                reduction.sku(), original.code()));
            }
        }
    }

    private static EInvoiceLine lineOf(EInvoice invoice, UUID lineId) {
        return invoice.lines().stream().filter(line -> line.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "lines: %s is not a line of invoice %s".formatted(lineId, invoice.code())));
    }

    private static BigDecimal price(BigDecimal given, BigDecimal original) {
        if (given == null) {
            return original;
        }
        if (given.signum() < 0 || given.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "lines.unitPrice: whole dong, not below zero - the quantity's sign says which way it changes");
        }
        return given.setScale(0, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal rate(BigDecimal given, BigDecimal original) {
        if (given == null) {
            return original;
        }
        if (given.signum() < 0 || given.compareTo(BigDecimal.valueOf(100)) >= 0
                || given.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "lines.taxRate: 0 to below 100, two decimals at most");
        }
        return given;
    }

    private static String name(String given, String original) {
        return given == null || given.isBlank() ? original : EInvoiceDrafts.limited(given, "lines.itemName", 500);
    }
}
