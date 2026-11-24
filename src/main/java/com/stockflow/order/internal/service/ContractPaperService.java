package com.stockflow.order.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.payment.api.ContractLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Draws the two papers that follow a signed contract: the appendix that records what the parties changed after
 * signing, and the record that liquidates the contract when it ends. Both are drawn when asked for, from the order as
 * it stands, to be printed, signed and filed - neither is stored, and neither changes the contract.
 *
 * <p><b>The appendix</b> states the one thing this system lets the parties change after signing: a delivery batch's
 * window (17 BR-28), moved with the customer's consent on record. Every batch whose window in force differs from the
 * one the contract was signed with is a row - what the contract says beside what it now says - so one appendix always
 * carries the whole difference, and a second window change redraws it rather than adding "appendix 02" on top of a
 * first nobody signed. A contract with no window moved has no appendix (404).</p>
 *
 * <p><b>The liquidation</b> is for a contract that has ended: COMPLETED or LIQUIDATED with its order (WF-05 Q-6),
 * or signed and its order then cancelled, refunded or closed. One still being performed is refused (409): its figures
 * would be out of date the next day. So is one whose money is not settled (409): the record says the parties owe each
 * other nothing more, and drawing it over a debt or a refund still owed would put a false statement up for signature
 * (owner, 2026-11-23: "Còn lại" is 0 at liquidation). The goods are the order's lines against what its shipments
 * delivered and its returns took back; the value performed is the goods delivered successfully and kept, at the
 * order's prices with VAT, and 0 when nothing was delivered ({@link Order#deliveredSomething}, the one definition
 * G1 made) - printed with the fees the customer keeps paying inside the same row and named in a note under it (the
 * owner's five rows, demo run 24/11 D-6); the money is the payment module's own ({@link ContractLedger}), so the figures the parties sign are the
 * accountant's. The contract's value is printed as signed and never changed.</p>
 *
 * <p>Each reader comes twice: for the back office, under the caller's data scope as the contract itself is read, and
 * for the customer's own order.</p>
 */
@Service
public class ContractPaperService {

    /** A drawn paper and the name its download carries. */
    public record Pdf(String fileName, byte[] content) {
    }

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SalesContracts contracts;
    private final DeliveryBatchService batches;
    private final OrderRepository orders;
    private final ReturnRequestRepository returns;
    private final ContractLedger ledger;
    private final SalesDocumentFacts shared;
    private final ContractAppendixDocument appendix;
    private final ContractLiquidationDocument liquidation;
    private final Clock clock;

    ContractPaperService(SalesContracts contracts, DeliveryBatchService batches, OrderRepository orders,
                         ReturnRequestRepository returns, ContractLedger ledger, SalesDocumentFacts shared,
                         ContractAppendixDocument appendix, ContractLiquidationDocument liquidation, Clock clock) {
        this.contracts = contracts;
        this.batches = batches;
        this.orders = orders;
        this.returns = returns;
        this.ledger = ledger;
        this.shared = shared;
        this.appendix = appendix;
        this.liquidation = liquidation;
        this.clock = clock;
    }

    /**
     * The appendix of an order's contract, for the back office.
     *
     * @throws BusinessException {@code NOT_FOUND} (no such order in the caller's scope, or nothing changed since
     *         signing), {@code SALES_CONTRACT_NOT_FOUND}, {@code SALES_CONTRACT_STATUS_INVALID} (not signed yet)
     */
    @Transactional(readOnly = true)
    public Pdf appendix(UUID orderId) {
        return appendix(contracts.get(orderId), batches.schedule(orderId));
    }

    /** The appendix of the contract of one of the customer's own orders; anyone else's is not found. */
    @Transactional(readOnly = true)
    public Pdf appendixOfCustomer(UUID customerId, UUID orderId) {
        return appendix(contracts.customerContract(customerId, orderId),
                batches.scheduleOfCustomer(customerId, orderId));
    }

    /**
     * The record liquidating an order's contract, for the back office.
     *
     * @throws BusinessException {@code NOT_FOUND}, {@code SALES_CONTRACT_NOT_FOUND},
     *         {@code SALES_CONTRACT_STATUS_INVALID} (not signed, still being performed, or its money not settled)
     */
    @Transactional(readOnly = true)
    public Pdf liquidation(UUID orderId) {
        return liquidation(contracts.get(orderId));
    }

    /** The record liquidating the contract of one of the customer's own orders; anyone else's is not found. */
    @Transactional(readOnly = true)
    public Pdf liquidationOfCustomer(UUID customerId, UUID orderId) {
        return liquidation(contracts.customerContract(customerId, orderId));
    }

    private Pdf appendix(SalesContracts.ContractView contract, DeliveryBatchService.Schedule schedule) {
        requireSigned(contract);
        ResourceBundle t = DocumentParts.bundle();
        List<ContractAppendixDocument.Change> changes = new ArrayList<>();
        List<DeliveryBatchService.WindowChangeView> moves = new ArrayList<>();
        for (DeliveryBatchService.BatchView batch : schedule.batches()) {
            if (batch.contractWindowStart() == null || batch.contractWindowStart().equals(batch.windowStart())
                    && batch.contractWindowEnd().equals(batch.windowEnd())) {
                continue;
            }
            changes.add(new ContractAppendixDocument.Change(
                    t.getString("contract-papers.appendix.window").formatted(batch.seq()),
                    window(t, batch.contractWindowStart(), batch.contractWindowEnd()),
                    window(t, batch.windowStart(), batch.windowEnd())));
            moves.addAll(batch.windowChanges());
        }
        if (changes.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, ("Contract %s was not changed since it was signed: it "
                    + "has no appendix").formatted(contract.contractNumber()));
        }
        String reason = moves.stream().map(move -> move.reason().name()).distinct()
                .map(code -> t.getString("contract-papers.appendix.reason." + code))
                .collect(Collectors.joining("; "));
        LocalDate dated = moves.stream().map(DeliveryBatchService.WindowChangeView::changedAt)
                .max(Instant::compareTo).map(at -> LocalDate.ofInstant(at, BUSINESS_ZONE)).orElseGet(this::today);
        String number = "PL01-" + contract.contractNumber();
        byte[] pdf = appendix.render(new ContractAppendixDocument.Facts(1, number, contract.contractNumber(),
                contract.signedOn(), contract.seller(), contract.buyer(), dated, reason.isEmpty() ? null : reason,
                changes, List.of(), null, false));
        return new Pdf(t.getString("document.file.contractAppendix").formatted(number), pdf);
    }

    private Pdf liquidation(SalesContracts.ContractView contract) {
        requireSigned(contract);
        Order order = orders.findById(new OrderId(contract.orderId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + contract.orderId()));
        boolean ended = SalesContractStatus.COMPLETED.name().equals(contract.status())
                || SalesContractStatus.LIQUIDATED.name().equals(contract.status());
        if (!ended && !ended(order.status())) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is still being "
                    + "performed (order %s is %s): it is liquidated once the order is completed, cancelled, refunded "
                    + "or closed").formatted(contract.contractNumber(), order.orderNumber().value(), order.status()),
                    List.of(), Map.of("reason", "STILL_PERFORMED"))
                    .localized("refusal.contract.liquidationStillPerformed", contract.contractNumber(),
                            order.orderNumber().value());
        }
        ContractLedger.Standing money = ledger.of(contract.orderId());
        if (money.outstanding().signum() != 0 || money.toRefund().signum() != 0) {
            // No error code of its own (owner 2026-11-23, "Còn lại phải = 0"): the contract is not in a state that can
            // be liquidated, which SALES_CONTRACT_STATUS_INVALID already says. details.reason tells it apart from the
            // contract still being performed, and the two amounts say what is left on each side.
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is not settled: "
                    + "the customer still owes %s and is still owed %s back. A liquidation states that nothing is left; "
                    + "it is drawn once the money is settled").formatted(contract.contractNumber(),
                    money.outstanding().toPlainString(), money.toRefund().toPlainString()), List.of(),
                    Map.of("reason", "NOT_SETTLED", "outstanding", money.outstanding(), "toRefund", money.toRefund()))
                    .localized("refusal.contract.liquidationNotSettled", contract.contractNumber(),
                            DocumentFormats.money(money.outstanding()), DocumentFormats.money(money.toRefund()));
        }
        ResourceBundle t = DocumentParts.bundle();
        boolean deliveredSomething = order.deliveredSomething();
        String reason = order.status() == OrderStatus.CANCELLED
                ? t.getString("contract-papers.liquidation.cancelled").formatted(order.orderNumber().value())
                : deliveredSomething ? null
                : t.getString("contract-papers.liquidation.undelivered").formatted(order.orderNumber().value());

        Map<UUID, Long> delivered = delivered(order, deliveredSomething);
        Map<UUID, Long> returned = new HashMap<>();
        for (ReturnRequest request : returns.findByOrder(contract.orderId())) {
            if (request.status() == ReturnStatus.RESOLVED) {
                for (ReturnLine line : request.lines()) {
                    returned.merge(line.orderLineId(), (long) line.refundQuantity(), Long::sum);
                }
            }
        }
        Map<String, String> names = shared.names(order.lines().stream().map(line -> line.sku().code()).toList());
        String piece = t.getString("document.unit.piece");
        List<ContractLiquidationDocument.GoodsLine> goods = new ArrayList<>();
        BigDecimal performed = BigDecimal.ZERO;
        for (OrderLine line : order.lines()) {
            long out = delivered.getOrDefault(line.id(), 0L);
            long back = returned.getOrDefault(line.id(), 0L);
            goods.add(new ContractLiquidationDocument.GoodsLine(line.sku().code(), names.get(line.sku().code()),
                    piece, line.quantity(), out, back, line.unitPrice().amount()));
            int kept = (int) Math.max(0, Math.min(line.quantity(), out - back));
            performed = performed.add(line.grossOf(kept).amount());
        }

        List<ContractLiquidationDocument.Payment> payments = money.payments().stream()
                .map(payment -> new ContractLiquidationDocument.Payment(payment.receivedOn(), payment.reference(),
                        payment.amount())).toList();
        // The books settled, what the customer keeps paying for is what it paid less what came back (owed); the goods
        // kept are part of it, and the rest is what the contract charged besides them.
        BigDecimal otherCharges = money.owed().subtract(performed);
        String number = "TL-" + contract.contractNumber();
        byte[] pdf = liquidation.render(new ContractLiquidationDocument.Facts(number, contract.contractNumber(),
                contract.signedOn(), today(), contract.seller(), contract.buyer(), reason, goods,
                contract.totals().totalAmount(), performed, otherCharges,
                charges(t, order, otherCharges, deliveredSomething), payments, money.refunded(), List.of()));
        return new Pdf(t.getString("document.file.contractLiquidation").formatted(number), pdf);
    }

    /**
     * The names of the fees inside {@code otherCharges}, for the note under "Giá trị thực hiện": the plate fees, which
     * are kept once the plate is made; the shipping fee while some of it is kept - a delivery that came back or was
     * lost waived its share (Q-19), and an order that delivered nothing waived all of it; and "other fees" for what is
     * left beyond those two, a cancellation or failed-trip fee payment charged. The amount is the books' and is printed
     * whole; this only says what it is made of.
     */
    private static List<String> charges(ResourceBundle t, Order order, BigDecimal otherCharges,
                                        boolean deliveredSomething) {
        if (otherCharges.signum() <= 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        BigDecimal named = BigDecimal.ZERO;
        BigDecimal plates = order.fees().stream().filter(fee -> fee.kind() == FeeKind.PLATE_FEE)
                .map(fee -> fee.gross().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (plates.signum() > 0) {
            names.add(t.getString("contract.pdf.fee." + FeeKind.PLATE_FEE.name()));
            named = named.add(plates);
        }
        BigDecimal shipping = order.fees().stream().filter(fee -> fee.kind() == FeeKind.SHIPPING_FEE)
                .map(fee -> fee.gross().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal waived = order.shipments().stream().map(OrderShipment::shippingFeeWaived)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shippingKept = deliveredSomething ? shipping.subtract(waived).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        if (shippingKept.signum() > 0) {
            names.add(t.getString("contract.pdf.fee." + FeeKind.SHIPPING_FEE.name()));
            named = named.add(shippingKept);
        }
        if (otherCharges.compareTo(named) > 0) {
            names.add(t.getString("liquidation.pdf.settle.otherFees"));
        }
        return names;
    }

    /**
     * The units of each line delivered successfully: what the order's DELIVERED shipments carried - a shipment the
     * carrier brought back, lost or still out delivered nothing. An order delivered whole before shipments were
     * recorded (historical, {@link Order#deliveredSomething}) delivered every unit it did not cancel.
     */
    private static Map<UUID, Long> delivered(Order order, boolean deliveredSomething) {
        Map<UUID, Long> delivered = new HashMap<>();
        if (!deliveredSomething) {
            return delivered;
        }
        if (order.shipments().isEmpty()) {
            order.lines().forEach(line -> delivered.put(line.id(), (long) line.quantity() - line.cancelledQuantity()));
            return delivered;
        }
        order.shipments().stream().filter(shipment -> shipment.status() == OrderShipmentStatus.DELIVERED)
                .forEach(shipment -> shipment.quantities()
                        .forEach((line, units) -> delivered.merge(line, (long) units, Long::sum)));
        return delivered;
    }

    /** A paper that follows a contract follows one both parties signed. */
    private static void requireSigned(SalesContracts.ContractView contract) {
        if (contract.signedOn() == null) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID,
                    "Contract %s is not signed yet".formatted(contract.contractNumber()));
        }
    }

    /** Whether nothing more will be delivered or charged on the order. */
    private static boolean ended(OrderStatus status) {
        return switch (status) {
            case COMPLETED, CANCELLED, CLOSED, REFUNDED, PARTIALLY_REFUNDED -> true;
            default -> false;
        };
    }

    private static String window(ResourceBundle t, LocalDate start, LocalDate end) {
        return t.getString("contract-papers.appendix.windowValue").formatted(DocumentFormats.date(start),
                DocumentFormats.date(end));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }
}
