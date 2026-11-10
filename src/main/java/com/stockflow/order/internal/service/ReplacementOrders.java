package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The replacement of returned goods (SCRUM-260, "in lại / giao bù"): a new order of the same customer, at no charge,
 * for the accepted units a return makes good with new goods.
 *
 * <p><b>Why an order.</b> New goods have to be picked, packed, handed to a carrier and delivered, and every one of
 * those steps runs from an order: release chooses the warehouse and fulfilment picks, packs and ships released orders.
 * It is the "subsequent delivery free of charge" of an ERP: the replacement order names the return
 * ({@code replaces_return_request_id}), costs nothing ({@code ck_order_replacement_free}), and each return line names
 * the replacement line it was made good by.</p>
 *
 * <p>Joins the resolving transaction: a replacement that cannot be placed (no stock, a customer no longer ACTIVE)
 * refuses the resolution, and Sales credit the units instead.</p>
 */
@Component
class ReplacementOrders {

    private static final Logger log = LoggerFactory.getLogger(ReplacementOrders.class);

    /** The order placed, and per return line the order line it ships the replacements on. */
    record Placed(UUID orderId, String orderNumber, Map<UUID, UUID> lineByReturnLine) {
    }

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final CustomerService customers;
    private final OrderEventPublisher events;
    private final Clock clock;

    ReplacementOrders(OrderRepository orders, InventoryService inventory, CustomerService customers,
                      OrderEventPublisher events, Clock clock) {
        this.orders = orders;
        this.inventory = inventory;
        this.customers = customers;
        this.events = events;
        this.clock = clock;
    }

    /**
     * What a replacement needs before anything is placed: an ACTIVE customer (BR-CUS-003: no new order for a blocked
     * one).
     *
     * @throws BusinessException {@code RETURN_CUSTOMER_NOT_ACTIVE}
     */
    void requirePlaceable(Order original, ReturnRequest request) {
        if (request.replacementUnits() == 0) {
            return;
        }
        String status = customers.findById(original.customerId()).map(CustomerSummary::status).orElse(null);
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.RETURN_CUSTOMER_NOT_ACTIVE,
                    "Customer of order %s is %s: no replacement order can be placed; credit the units instead"
                            .formatted(original.orderNumber(), status));
        }
    }

    /**
     * Places the replacement order for the units {@code request} replaces: the same SKUs and locked designs, at a price
     * of zero, delivered to the original order's address; their stock (or blanks) held now and pinned, since nothing is
     * waiting for payment. Idempotent on the return: placed again, the same order is found. {@code by}, who settled the
     * return, is named on the order's confirmation.
     */
    Placed place(Order original, ReturnRequest request, UUID by) {
        requirePlaceable(original, request);
        UUID requestId = UUID.nameUUIDFromBytes(("replacement:" + request.id()).getBytes(StandardCharsets.UTF_8));
        Map<UUID, UUID> lineByReturnLine = new LinkedHashMap<>();
        List<OrderLine> lines = new ArrayList<>();
        int index = 0;
        for (ReturnLine line : request.lines()) {
            if (line.replacementQuantity() == 0) {
                continue;
            }
            OrderLine sold = original.findLine(line.orderLineId()).orElseThrow();
            UUID lineId = UUID.nameUUIDFromBytes((requestId + "#line#" + index++).getBytes(StandardCharsets.UTF_8));
            OrderLine replacement = Order.line(lineId, sold.sku(), line.replacementQuantity(),
                    Money.zero(sold.unitPrice().currency()), sold.designSnapshotId());
            if (sold.designChecksum() != null) {
                replacement.recordDesignChecksum(sold.designChecksum());
            }
            lines.add(replacement);
            lineByReturnLine.put(line.id(), lineId);
        }
        Optional<Order> placed = orders.findByRequestId(requestId);
        if (placed.isPresent()) {
            return new Placed(placed.get().id().value(), placed.get().orderNumber().value(), lineByReturnLine);
        }
        Instant now = clock.instant();
        Order order = Order.customerDraft(orders.nextOrderNumber(LocalDate.ofInstant(now, ZoneOffset.UTC)),
                original.customerId(), requestId, lines, now, original.contactName(), original.contactEmail(),
                original.contactPhone(), original.shippingAddress(), original.billingAddress());
        order.replacesReturn(request.id());
        // The same steps as a checkout (OrderServiceImpl.place): the SKU locks first, then each line held, all in this
        // transaction - so a resolution refused below leaves no hold behind.
        inventory.prepareReservation(order.lines().stream().map(OrderLine::sku).collect(Collectors.toSet()));
        for (OrderLine line : order.lines()) {
            ReserveStockResult held = inventory.reserve(new ReserveStockCommand(
                    UUID.nameUUIDFromBytes((requestId + ":" + line.id()).getBytes(StandardCharsets.UTF_8)),
                    line.sku(), line.quantity(), order.id().value()));
            order.attachReservations(line.id(), held.reservationIds());
        }
        order.submitReplacement(by, now);
        Order saved = orders.save(order);
        // Confirmed at once, nothing to pay: the holds must not run out as a checkout's would (kltn-docs 14 BR-07).
        inventory.pinReservations(saved.id().value());
        events.publishEventsOf(order);
        log.info("Return {} replaces {} unit(s) of order {} with order {}", request.rmaNumber(),
                request.replacementUnits(), original.orderNumber(), saved.orderNumber());
        return new Placed(saved.id().value(), saved.orderNumber().value(), lineByReturnLine);
    }
}
