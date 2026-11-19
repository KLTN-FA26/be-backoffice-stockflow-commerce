package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PrePicking;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * {@link PrePicking}: the part of an AWAITING_STOCK order already held is allocated and picked before the order is
 * released (kltn-docs 07 §4.1 step 2, 17 §4.4 step 15), and the rest joins the same task as it arrives (07 §4.3 step
 * 7, 17 §4.4 step 16).
 *
 * <p>The batch is deliberately not recorded as released while it is short: holding goes on for the batches not
 * released (17 §4.2 step 3 "hàng nhập về sau ... bù vào đợt sớm nhất còn thiếu"), so the goods that arrive later are
 * held for this batch, not the next one. The release happens, as for any order, once the batch is held in full
 * ({@link OrderReleases}). The installment before delivery falls due at the first delivery ready (owner decision
 * SCOPE-PARTIAL-1105; 15 §4.1 step 2): at that release, or earlier when Sales allowed a partial shipment and the part
 * held goes towards dispatch ({@link #partGoesFirst}) - never when cartons are only picked to the staging area.</p>
 *
 * <p>Lock order: the caller holds the pick's lock; the order row next, then inventory's (SKU locks, reservations,
 * stock rows) - as every write of a task.</p>
 */
@Service
@Transactional
class PrePickingService implements PrePicking {

    private static final Logger log = LoggerFactory.getLogger(PrePickingService.class);

    private final OrderRepository repository;
    private final ShippingWarehouseService shippingWarehouses;
    private final OrderStockAllocation allocation;
    private final WarehouseDirectory warehouses;
    private final OrderReleases releases;
    private final SalesContractRepository contracts;
    private final DeliveryBatchRecords batchRecords;
    private final OrderEventPublisher events;
    private final Clock clock;

    PrePickingService(OrderRepository repository, ShippingWarehouseService shippingWarehouses,
                      OrderStockAllocation allocation, WarehouseDirectory warehouses, OrderReleases releases,
                      SalesContractRepository contracts, DeliveryBatchRecords batchRecords,
                      OrderEventPublisher events, Clock clock) {
        this.events = events;
        this.repository = repository;
        this.shippingWarehouses = shippingWarehouses;
        this.allocation = allocation;
        this.warehouses = warehouses;
        this.releases = releases;
        this.contracts = contracts;
        this.batchRecords = batchRecords;
        this.clock = clock;
    }

    @Override
    public PrePick prePick(UUID orderId, UUID by) {
        Order order = lock(orderId);
        if (order.status() != OrderStatus.AWAITING_STOCK || order.releasedAt() != null) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, ("Order %s is %s: only an order awaiting "
                    + "stock is picked before its release").formatted(order.orderNumber(), order.status()));
        }
        ShippingWarehouseService.NextBatch next = shippingWarehouses.nextBatch(order).orElseThrow(() ->
                new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "Order %s has no delivery batch to pick"
                        .formatted(order.orderNumber())));
        if (next.fullyHeld()) {
            // 17 §4.4 step 16: held in full, the order is released and picked as any order - not picked ahead.
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, ("Delivery batch %d of order %s is held "
                    + "in full: release the order first").formatted(next.seq(), order.orderNumber()));
        }
        DeliveryBatch batch = batchOf(order, next.batchId());
        Set<UUID> held = shippingWarehouses.heldFor(order, batch);
        if (held.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("Nothing is held yet for delivery batch %d of "
                    + "order %s at its shipping warehouse").formatted(batch.seq(), order.orderNumber()));
        }
        int allocated = allocation.allocate(order, warehouseCode(order), held);
        order.startPrePicking(clock.instant());
        repository.save(order);
        log.info("Order {} (awaiting stock): {} hold(s) of delivery batch {} allocated to be picked before its release",
                order.orderNumber(), allocated, batch.seq());
        return new PrePick(batch.id(), batch.seq(), order.shippingWarehouseId());
    }

    @Override
    public boolean topUp(UUID orderId, UUID deliveryBatchId, UUID by) {
        Order order = lock(orderId);
        if (deliveryBatchId == null || !order.prePicked() || released(order, deliveryBatchId)) {
            return false;
        }
        boolean fullyHeld = shippingWarehouses.nextBatch(order)
                .filter(next -> next.batchId().equals(deliveryBatchId))
                .map(ShippingWarehouseService.NextBatch::fullyHeld).orElse(false);
        if (fullyHeld && order.status() != OrderStatus.ON_HOLD) {
            // 17 §4.4 step 16, §5.1 "nhận đủ hàng": the batch is released as any order's is - its holds allocated, the
            // release recorded, payment told (OrderReleased).
            releases.release(orderId, order.shippingWarehouseId(), deliveryBatchId, by);
            log.info("Order {}: delivery batch picked before its release is held in full and released",
                    order.orderNumber());
            return true;
        }
        // Still short, or on hold (the hold resolves back to where it was; the release waits for it): what arrived is
        // allocated and joins the task (07 §6 edge "phân bổ bổ sung → thêm dòng vào cùng task").
        int allocated = allocation.allocate(order, warehouseCode(order), shippingWarehouses.heldFor(order,
                batchOf(order, deliveryBatchId)));
        if (allocated > 0) {
            repository.save(order);
            log.info("Order {}: {} hold(s) allocated to the task picked before its release", order.orderNumber(),
                    allocated);
        }
        return false;
    }

    @Override
    public void partGoesFirst(UUID orderId, UUID deliveryBatchId) {
        Order order = lock(orderId);
        order.releasePartFirst(warehouseCode(order), deliveryBatchId, clock.instant());
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} (awaiting stock): its part held goes first; the installments before delivery fall due",
                order.orderNumber());
    }

    private Order lock(UUID orderId) {
        return repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private boolean released(Order order, UUID batchId) {
        return batchRecords.releasesOf(order.id().value()).stream().anyMatch(r -> r.batchId().equals(batchId));
    }

    private DeliveryBatch batchOf(Order order, UUID batchId) {
        return contracts.findLiveByOrderId(order.id().value()).stream().flatMap(c -> c.schedule().stream())
                .filter(b -> b.id().equals(batchId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_BATCH_NOT_FOUND, ("No delivery batch %s "
                        + "of order %s").formatted(batchId, order.orderNumber())));
    }

    private String warehouseCode(Order order) {
        return warehouses.codeOf(order.shippingWarehouseId()).orElseThrow(() -> new BusinessException(
                ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + order.shippingWarehouseId()));
    }
}
