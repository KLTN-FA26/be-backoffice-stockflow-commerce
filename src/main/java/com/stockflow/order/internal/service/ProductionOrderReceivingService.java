package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ProductionOrderReceiving;
import com.stockflow.order.api.ReceivableProductionOrder;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderReceiptRepository;
import com.stockflow.order.internal.domain.ProductionOrderReceiptRepository.Registered;
import com.stockflow.order.internal.domain.ProductionOrderReceiptRepository.Status;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProductionOrderReceiving}: receiving against a production order, from the order module's side (4A-5,
 * kltn-docs 03 §4.1, 17 §4.3 step 11). Runs in the caller's transaction - the goods receipt's - so the receipt, the
 * stock it counts in and the production order's progress commit together or not at all.
 *
 * <p>Every write takes the locks itself, the sales order first and the production order after it, as every write to
 * a production order does ({@link ProductionOrderService}): taken again within one transaction they cost a round
 * trip each and decide nothing new, while a caller that forgot {@link #lock} would otherwise decide on a production
 * order another transaction is moving. The registration of each receipt is what keeps a production order with a draft
 * from being cancelled or closed (03 edge cases), and what makes {@link #recordReceipt} count a receipt once.</p>
 */
@Service
@Transactional
public class ProductionOrderReceivingService implements ProductionOrderReceiving {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderReceivingService.class);

    private final ProductionOrderRepository productionOrders;
    private final ProductionOrderReceiptRepository receipts;
    private final OrderRepository orders;
    private final ProducerRepository producers;
    private final ProductionOrderDispatch dispatch;
    private final Clock clock;

    ProductionOrderReceivingService(ProductionOrderRepository productionOrders,
                                    ProductionOrderReceiptRepository receipts, OrderRepository orders,
                                    ProducerRepository producers, ProductionOrderDispatch dispatch, Clock clock) {
        this.productionOrders = productionOrders;
        this.receipts = receipts;
        this.orders = orders;
        this.producers = producers;
        this.dispatch = dispatch;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceivableProductionOrder> find(UUID productionOrderId) {
        return productionOrders.findById(productionOrderId).map(production -> view(production, new HashMap<>()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReceivableProductionOrder> findAll(Collection<UUID> productionOrderIds) {
        Map<UUID, String> producerNames = new HashMap<>();
        Map<UUID, ReceivableProductionOrder> found = new LinkedHashMap<>();
        productionOrders.findAllById(productionOrderIds)
                .forEach(production -> found.put(production.id(), view(production, producerNames)));
        return found;
    }

    @Override
    public Optional<ReceivableProductionOrder> lock(UUID productionOrderId) {
        return locked(productionOrderId).map(production -> view(production, new HashMap<>()));
    }

    @Override
    public void openReceipt(UUID productionOrderId, UUID receiptId, String receiptNumber) {
        ProductionOrder production = locked(productionOrderId).orElseThrow(() -> notFound(productionOrderId));
        Optional<String> refused = production.whyNotReceivable();
        if (refused.isPresent()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE, refused.get());
        }
        if (receipts.find(productionOrderId, receiptId).isPresent()) {
            return;
        }
        receipts.open(productionOrderId, receiptId, receiptNumber, clock.instant());
        log.info("Production order {}: draft receipt {} opened", production.code(), receiptNumber);
    }

    @Override
    public void dropReceipt(UUID productionOrderId, UUID receiptId, UUID actorId) {
        ProductionOrder production = locked(productionOrderId).orElseThrow(() -> notFound(productionOrderId));
        Registered registered = registered(production, receiptId);
        if (registered.status() == Status.CANCELLED) {
            return;
        }
        if (registered.status() == Status.CONFIRMED) {
            throw new IllegalStateException("Receipt %s of production order %s was counted in: it is not cancelled"
                    .formatted(registered.receiptNumber(), production.code()));
        }
        Instant at = clock.instant();
        receipts.settle(productionOrderId, receiptId, Status.CANCELLED, at);
        log.info("Production order {}: draft receipt {} cancelled", production.code(), registered.receiptNumber());
        stopIfItsOrderWasCancelled(production, actorId, at);
    }

    @Override
    public ReceivableProductionOrder recordReceipt(UUID productionOrderId, UUID receiptId,
                                                   Map<UUID, Integer> cartonsByProductionOrderLine, UUID actorId,
                                                   Instant at) {
        ProductionOrder production = locked(productionOrderId).orElseThrow(() -> notFound(productionOrderId));
        Registered registered = registered(production, receiptId);
        if (registered.status() == Status.CONFIRMED) {
            // Counted in already (a retried confirmation): the production order is answered as it stands.
            return view(production, new HashMap<>());
        }
        if (registered.status() == Status.CANCELLED) {
            throw new IllegalStateException("Receipt %s of production order %s was cancelled: nothing is counted in"
                    .formatted(registered.receiptNumber(), production.code()));
        }
        production.receive(cartonsByProductionOrderLine, registered.receiptNumber(), actorId, at);
        ProductionOrder saved = productionOrders.save(production);
        receipts.settle(productionOrderId, receiptId, Status.CONFIRMED, at);
        log.info("Production order {}: receipt {} counted in {} carton(s), now {}", saved.code(),
                registered.receiptNumber(), cartonsByProductionOrderLine.values().stream().mapToInt(Integer::intValue)
                        .sum(), saved.status());
        return view(stopIfItsOrderWasCancelled(saved, actorId, at), new HashMap<>());
    }

    /**
     * A sales order cancelled while a goods receipt against one of its production orders was a draft left that
     * production order open: a draft blocks cancelling and closing it (03 edge cases), so
     * {@code SalesOrderCancellationListener} passed it by. The last draft settled, it is stopped here, in the
     * receipt's transaction and under the locks already taken (sales order, then production order): cancelled if mass
     * printing had not started, closed otherwise - what was just received stays as the customer's surplus (17 BR-07,
     * BR-09). Without someone to name, it is left to the coordinator, as the listener leaves it.
     *
     * <p>A production order already sent then tells its producer to stop ({@link ProductionOrderDispatch#stopped}, 17
     * §4.3 steps 12-13), in this transaction, as {@code ProductionOrderService.cancel} and {@code close} do on the
     * listener's path: stopped here without it, the producer would go on making goods for a cancelled order.</p>
     */
    private ProductionOrder stopIfItsOrderWasCancelled(ProductionOrder production, UUID actorId, Instant at) {
        if (!production.status().open() || !receipts.drafts(production.id()).isEmpty()) {
            return production;
        }
        Order order = orders.findById(new OrderId(production.salesOrderId())).orElseThrow();
        if (order.status() != OrderStatus.CANCELLED) {
            return production;
        }
        if (actorId == null) {
            log.warn("Production order {} of cancelled order {} has no draft receipt left but no one to stop it: left "
                    + "to the coordinator", production.code(), order.orderNumber());
            return production;
        }
        String reason = "Sales order %s was cancelled: %s".formatted(order.orderNumber(),
                order.cancellationReasonCode());
        if (production.status().cancellable()) {
            production.cancel(reason, actorId, at);
        } else if (production.status().canTransitionTo(ProductionOrderStatus.CLOSED)) {
            production.close(reason, actorId, at);
        } else {
            return production;
        }
        ProductionOrder stopped = productionOrders.save(production);
        dispatch.stopped(stopped);
        log.info("Production order {} stopped with its cancelled order {}: its last draft receipt is settled",
                stopped.code(), order.orderNumber());
        return stopped;
    }

    /** The receipt's registration with the production order; a receipt never opened is a fault of the caller. */
    private Registered registered(ProductionOrder production, UUID receiptId) {
        return receipts.find(production.id(), receiptId).orElseThrow(() -> new IllegalStateException(
                "Receipt %s was never opened against production order %s".formatted(receiptId, production.code())));
    }

    /** The sales order locked, then the production order locked and read fresh: the order every write locks in. */
    private Optional<ProductionOrder> locked(UUID productionOrderId) {
        Optional<ProductionOrder> read = productionOrders.findById(productionOrderId);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        orders.findByIdForUpdate(new OrderId(read.get().salesOrderId()));
        return productionOrders.findByIdForUpdate(productionOrderId);
    }

    private ReceivableProductionOrder view(ProductionOrder production, Map<UUID, String> producerNames) {
        String producerName = producerNames.computeIfAbsent(production.producerId(), id -> producers.findById(id)
                .map(Producer::name).orElse(null));
        List<ReceivableProductionOrder.Line> lines = production.lines().stream()
                .map(line -> new ReceivableProductionOrder.Line(line.id(), line.orderLineId(), line.sku(),
                        line.cartons(), line.unitsPerCarton(), line.receivedCartons()))
                .toList();
        return new ReceivableProductionOrder(production.id(), production.code(), production.status().name(),
                production.receivable(), production.whyNotReceivable().orElse(null),
                production.receivingWarehouseId(), production.producerId(), producerName, lines);
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND, "No production order " + id);
    }
}
