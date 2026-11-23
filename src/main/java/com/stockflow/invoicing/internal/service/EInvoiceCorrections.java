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
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
     * @param drafts        the adjustments drawn up, one per invoice reached
     * @param unplaced      pieces refunded, by order line, that the invoice of the return's shipment could not take:
     *                      more than it still holds, it is being replaced, or part of a carton whose price does not
     *                      divide into whole dong. The accountant adjusts for them by hand
     * @param pending       pieces kept until the invoice of the return's shipment is issued (WF-05 BE-28): taken off it
     *                      then, by themselves ({@link #drawUpPendingReturns})
     * @param onReplacement pieces on a replacement invoice, which no provider adjusts (Q-9): reduced on the provider's
     *                      portal by the accountant
     */
    public record ReturnAdjustments(List<EInvoice.Snapshot> drafts, Map<UUID, Long> unplaced,
                                    Map<UUID, Long> pending, Map<UUID, Long> onReplacement) {

        public ReturnAdjustments {
            pending = pending == null ? Map.of() : Map.copyOf(pending);
            onReplacement = onReplacement == null ? Map.of() : Map.copyOf(onReplacement);
        }

        /** As before pieces could wait for their own invoice, or sit on a replacement. */
        public ReturnAdjustments(List<EInvoice.Snapshot> drafts, Map<UUID, Long> unplaced) {
            this(drafts, unplaced, Map.of(), Map.of());
        }
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
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code RETURN_REQUEST_NOT_FOUND};
     *         {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for {@code DELIVERY_FAILED}, the system's alone;
     *         {@code VALIDATION_FAILED} for a line that is not the invoice's, a quantity of zero, a price that is not whole dong, a return of
     *         another order, or a buyer changed on anything but the correction of an error; and what
     *         {@link EInvoice#drawUpAdjustment} refuses
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "einvoice", resourceId = "#command.originalId()")
    @Transactional
    public EInvoice.Snapshot adjust(Adjust command) {
        if (command.basis() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "basis: ERROR, RETURN or TRADE_DISCOUNT");
        }
        if (command.basis().systemOnly()) {
            // WF-05 HĐ-3: drawn up when the delivery is corrected, whole, by EInvoiceDrafts.reverseForCorrectedDelivery
            // - which also stops the invoice standing for its shipment. Drawn up by hand it would do neither.
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("basis %s: drawn up by the "
                    + "system when a delivery is corrected to a failure, never by hand").formatted(command.basis()));
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
     * taken off the issued invoice of <b>the shipment the return was against</b> - and only that one (WF-05 BE-28,
     * HĐ-4; 17 §4.10 step 40: the invoice of the shipment that carried the goods) - as far as it still holds them, in a
     * draft adjustment naming the return (TT 91 Điều 10 khoản 5 điểm c.1; no agreement is needed, the return is the
     * record). The accountant reads and issues it.
     *
     * <ul>
     *   <li><b>That invoice not issued yet</b> (a draft, refused, at the provider, or not drawn up): the pieces are kept
     *       ({@link EInvoiceRepository#keepPendingReturn}) and taken off it once it is issued
     *       ({@link #drawUpPendingReturns}). They used to be taken off another delivery's invoice - another day, often
     *       another tax period - which is the wrong invoice in law.</li>
     *   <li><b>That invoice a replacement</b>: no provider adjusts one (Q-9; TT 91 Điều 10 khoản 6 điểm a; SePay 422,
     *       sandbox T-5), and a return is never corrected by replacing (khoản 5 điểm c.1): the pieces are reported
     *       {@code onReplacement}, for the accountant to reduce on the provider's portal.</li>
     *   <li><b>More pieces than it still holds</b>, a piece of a carton not worth whole dong, an invoice being replaced:
     *       {@code unplaced}, the accountant's. Never spilt onto another shipment's invoice.</li>
     * </ul>
     *
     * <p>A return of an order delivered without shipments names none, and is taken off the order's originals in force,
     * the oldest first, as before.</p>
     *
     * <p>Whole cartons come off as cartons at the carton's price. Pieces that are not whole cartons come off as pieces,
     * when the carton's price divides into whole dong; otherwise they are left for the accountant.</p>
     *
     * <p>A return is taken off once: a second call for the same return - the event delivered again - draws nothing up,
     * even if its drafts were voided since, and keeps nothing twice.</p>
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
        if (resolved.shipmentId() != null) {
            Optional<EInvoice> own = invoices.findLiveOriginalOfShipment(resolved.shipmentId());
            if (own.isEmpty() || own.get().status() != EInvoiceStatus.ISSUED) {
                // Not issued yet (or not even drawn up): kept, and taken off it once it is (BE-28).
                Instant now = clock.instant();
                remaining.forEach((orderLine, pieces) -> invoices.keepPendingReturn(
                        new EInvoiceRepository.PendingReturnPieces(resolved.returnRequestId(), resolved.rmaNumber(),
                                resolved.orderId(), resolved.orderNumber(), resolved.shipmentId(), orderLine, pieces),
                        now));
                return new ReturnAdjustments(List.of(), Map.of(), remaining, Map.of());
            }
            EInvoice invoice = invoices.findByIdForUpdate(own.get().id()).orElseThrow();
            if (invoice.status() != EInvoiceStatus.ISSUED) {
                return new ReturnAdjustments(List.of(), Map.copyOf(remaining));
            }
            if (invoice.type() == EInvoiceType.REPLACEMENT) {
                // Kept as well: the accountant's record of the reduction made on the portal takes these pieces
                // (EInvoicePortalReductions, basis RETURN).
                Instant now = clock.instant();
                remaining.forEach((orderLine, pieces) -> invoices.keepPendingReturn(
                        new EInvoiceRepository.PendingReturnPieces(resolved.returnRequestId(), resolved.rmaNumber(),
                                resolved.orderId(), resolved.orderNumber(), resolved.shipmentId(), orderLine, pieces),
                        now));
                return new ReturnAdjustments(List.of(), Map.of(), Map.of(), remaining);
            }
            List<EInvoice.Snapshot> drafts = new ArrayList<>();
            reduceOn(invoice, remaining, resolved.returnRequestId(), resolved.rmaNumber()).ifPresent(drafts::add);
            remaining.values().removeIf(left -> left <= 0);
            return new ReturnAdjustments(drafts, Map.copyOf(remaining));
        }
        List<UUID> inForce = invoices.findInForceOfOrder(resolved.orderId());
        // Locked in id order, like every correction, each read once as the lock refreshes it; then walked oldest
        // first. One that is gone or no longer issued by the time its lock is held is passed over below.
        Map<UUID, EInvoice> locked = new HashMap<>();
        for (UUID id : new TreeSet<>(inForce)) {
            invoices.findByIdForUpdate(id).ifPresent(invoice -> locked.put(id, invoice));
        }
        List<EInvoice.Snapshot> drafts = new ArrayList<>();
        for (UUID candidate : inForce) {
            EInvoice invoice = locked.get(candidate);
            if (invoice == null || invoice.status() != EInvoiceStatus.ISSUED
                    || invoice.type() == EInvoiceType.REPLACEMENT) {
                continue;
            }
            reduceOn(invoice, remaining, resolved.returnRequestId(), resolved.rmaNumber()).ifPresent(drafts::add);
        }
        remaining.values().removeIf(left -> left <= 0);
        return new ReturnAdjustments(drafts, Map.copyOf(remaining));
    }

    /**
     * The pieces of returns kept for a shipment's invoice that was not issued yet (WF-05 BE-28), taken off it now that
     * it is: one draft adjustment per return, as {@link #drawUpForReturn} would have drawn up. A return adjusted
     * meanwhile - by hand, or by an earlier run - draws nothing up again. What the invoice cannot take (a replacement
     * issued in its place, more than it holds) is reported for the accountant.
     *
     * <p>A transaction of its own, for the same reason as {@link #drawUpForReturn}.</p>
     *
     * @return per return kept for the shipment, what became of its pieces
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<EInvoiceRepository.PendingReturnPieces, ReturnAdjustments> drawUpPendingReturns(UUID shipmentId) {
        List<EInvoiceRepository.PendingReturnPieces> kept = invoices.findPendingReturnsOfShipment(shipmentId);
        if (kept.isEmpty()) {
            return Map.of();
        }
        Optional<EInvoice> own = invoices.findLiveOriginalOfShipment(shipmentId);
        if (own.isEmpty() || own.get().status() != EInvoiceStatus.ISSUED) {
            return Map.of();
        }
        EInvoice invoice = invoices.findByIdForUpdate(own.get().id()).orElseThrow();
        if (invoice.status() != EInvoiceStatus.ISSUED) {
            return Map.of();
        }
        Map<UUID, List<EInvoiceRepository.PendingReturnPieces>> byReturn = new LinkedHashMap<>();
        kept.forEach(pieces -> byReturn.computeIfAbsent(pieces.returnRequestId(), id -> new ArrayList<>()).add(pieces));
        Map<EInvoiceRepository.PendingReturnPieces, ReturnAdjustments> outcome = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (List<EInvoiceRepository.PendingReturnPieces> pieces : byReturn.values()) {
            EInvoiceRepository.PendingReturnPieces first = pieces.getFirst();
            if (invoices.hasAdjustmentForReturn(first.returnRequestId())) {
                invoices.settlePendingReturn(first.returnRequestId(), shipmentId, now);
                continue;
            }
            Map<UUID, Long> remaining = new LinkedHashMap<>();
            pieces.forEach(line -> remaining.merge(line.orderLineId(), line.pieces(), Long::sum));
            if (invoice.type() == EInvoiceType.REPLACEMENT) {
                // Still kept: they are taken off by the record of the reduction made on the portal (Q-9).
                outcome.put(first, new ReturnAdjustments(List.of(), Map.of(), Map.of(), remaining));
                continue;
            }
            invoices.settlePendingReturn(first.returnRequestId(), shipmentId, now);
            List<EInvoice.Snapshot> drafts = new ArrayList<>();
            reduceOn(invoice, remaining, first.returnRequestId(), first.rmaNumber()).ifPresent(drafts::add);
            remaining.values().removeIf(left -> left <= 0);
            outcome.put(first, new ReturnAdjustments(drafts, Map.copyOf(remaining)));
        }
        return outcome;
    }

    /**
     * Takes {@code remaining} pieces off one issued invoice, as far as it still holds them, in one draft adjustment
     * naming the return; what it took is subtracted from {@code remaining}. Empty when it took nothing, or something
     * replaces it already.
     */
    private Optional<EInvoice.Snapshot> reduceOn(EInvoice invoice, Map<UUID, Long> remaining, UUID returnRequestId,
                                                 String rmaNumber) {
        EInvoice.Corrections corrections = invoices.correctionsOf(invoice.id());
        if (corrections.liveReplacement()) {
            return Optional.empty();
        }
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
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
            return Optional.empty();
        }
        // Dated the day it is drawn up; re-dated to the day it is issued when it is submitted (WF-05 Q-1).
        LocalDate date = BusinessCalendar.date(clock.instant());
        EInvoice adjustment = EInvoice.drawUpAdjustment(Identifiers.newId(), invoices.nextCode(date), invoice,
                CorrectionBasis.RETURN, t.getString("einvoice.correction.return").formatted(rmaNumber),
                invoice.buyer(), lines, date, returnRequestId, List.of(), corrections);
        return Optional.of(invoices.save(adjustment).snapshot());
    }

    /** {@code pieces} of {@code line} taken off it: as cartons when they are whole cartons, else as pieces. */
    static EInvoiceLine reduction(EInvoiceLine line, long pieces, int lineNo, ResourceBundle t) {
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
