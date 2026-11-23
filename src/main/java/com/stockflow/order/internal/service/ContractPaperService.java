package com.stockflow.order.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.payment.api.ContractLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><b>The liquidation</b> is for a contract that has ended: completed with its order, or signed and its order then
 * cancelled, refunded or closed. One still being performed is refused (409): its figures would be out of date the next
 * day. The goods are the order's lines against what its shipments delivered and its returns took back; the money is
 * the payment module's own ({@link ContractLedger}), so the balance the parties sign is the accountant's.</p>
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
     *         {@code SALES_CONTRACT_STATUS_INVALID} (not signed, or still being performed)
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
        boolean completed = SalesContractStatus.COMPLETED.name().equals(contract.status());
        if (!completed && !ended(order.status())) {
            throw new BusinessException(ErrorCode.SALES_CONTRACT_STATUS_INVALID, ("Contract %s is still being "
                    + "performed (order %s is %s): it is liquidated once the order is completed, cancelled, refunded "
                    + "or closed").formatted(contract.contractNumber(), order.orderNumber().value(), order.status()));
        }
        ResourceBundle t = DocumentParts.bundle();
        String reason = order.status() == OrderStatus.CANCELLED
                ? t.getString("contract-papers.liquidation.cancelled").formatted(order.orderNumber().value()) : null;

        Map<UUID, Long> delivered = new HashMap<>();
        order.shipments().stream().filter(shipment -> shipment.status() == OrderShipmentStatus.DELIVERED)
                .forEach(shipment -> shipment.quantities()
                        .forEach((line, units) -> delivered.merge(line, (long) units, Long::sum)));
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
        for (OrderLine line : order.lines()) {
            goods.add(new ContractLiquidationDocument.GoodsLine(line.sku().code(), names.get(line.sku().code()),
                    piece, line.quantity(), delivered.getOrDefault(line.id(), 0L),
                    returned.getOrDefault(line.id(), 0L), line.unitPrice().amount()));
        }

        ContractLedger.Standing money = ledger.of(contract.orderId());
        List<ContractLiquidationDocument.Payment> payments = money.payments().stream()
                .map(payment -> new ContractLiquidationDocument.Payment(payment.receivedOn(), payment.reference(),
                        payment.amount())).toList();
        String number = "TL-" + contract.contractNumber();
        byte[] pdf = liquidation.render(new ContractLiquidationDocument.Facts(number, contract.contractNumber(),
                contract.signedOn(), today(), contract.seller(), contract.buyer(), reason, goods,
                contract.totals().totalAmount(), money.owed().add(money.returned()), money.returned(), payments,
                money.refunded(), List.of(), null));
        return new Pdf(t.getString("document.file.contractLiquidation").formatted(number), pdf);
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
