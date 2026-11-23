package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderShippingFee;
import com.stockflow.order.api.ShippingFeeWaiver;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.TaxRates;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Answers the e-invoice's question about a delivered shipment (WF-05 E2, {@link DeliveredShipments}): the goods at the
 * order's prices, the order's fees, and the VAT rate of the day of delivery.
 *
 * <p><b>The rate is looked up, the price is not.</b> A line's price is what the customer agreed to and stays the
 * order's (14 BR-06). Its VAT rate is the law's on the day the goods change hands: an order placed at 10% and delivered
 * during a reduction to 8% is invoiced at 8%. So the rate comes from {@code ordering.tax_rate} for that day
 * ({@link TaxRates#onDay}) by the SKU's product tax class, and the rate the order fixed is handed over beside it for
 * the invoice to flag a difference. A SKU whose product has no tax class keeps the order's rate; a fee without one
 * takes STANDARD, as on a quote (D31).</p>
 *
 * <p>Nothing here ranks the order's deliveries. A shipment's delivery time is the carrier's, and a delivery that
 * happened earlier can be recorded later, so "delivered before this one" read from those times would change after an
 * invoice was drawn up from it. Which invoice is the order's first is the invoicing module's to say, from its own
 * invoices.</p>
 */
@Service
class DeliveredShipmentsService implements DeliveredShipments {

    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final TaxRates taxRates;
    private final ProductService products;

    DeliveredShipmentsService(OrderRepository orders, SalesContractRepository contracts, TaxRates taxRates,
                              ProductService products) {
        this.orders = orders;
        this.contracts = contracts;
        this.taxRates = taxRates;
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeliveredShipment> find(UUID orderId, UUID shipmentId) {
        Optional<Order> found = orders.findById(new OrderId(orderId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Order order = found.get();
        Optional<OrderShipment> delivered = order.shipments().stream()
                .filter(shipment -> shipment.id().equals(shipmentId)
                        && shipment.status() == OrderShipmentStatus.DELIVERED)
                .findFirst();
        if (delivered.isEmpty()) {
            return Optional.empty();
        }
        OrderShipment shipment = delivered.get();
        Instant deliveredAt = shipment.outcomeAt();

        Set<String> skus = new LinkedHashSet<>();
        order.lines().forEach(line -> skus.add(line.sku().code()));
        order.fees().stream().filter(fee -> fee.sku() != null).forEach(fee -> skus.add(fee.sku().code()));
        Map<String, TaxClass> classes = products.taxClassesOf(skus);
        Map<String, BigDecimal> rates = taxRates.onDay(BusinessCalendar.date(deliveredAt));

        List<DeliveredShipment.Line> lines = new ArrayList<>();
        for (OrderLine line : order.lines()) {
            int units = shipment.quantities().getOrDefault(line.id(), 0);
            if (units <= 0) {
                continue;
            }
            // Whole cartons leave the warehouse; a count that is not whole cartons is invoiced in pieces as it stands.
            boolean cartons = line.unitsPerCarton() != null && units % line.unitsPerCarton() == 0;
            TaxClass taxClass = classes.get(line.sku().code());
            lines.add(new DeliveredShipment.Line(line.id(), line.sku().code(),
                    products.nameForSku(line.sku().code()).orElse(line.sku().code()),
                    cartons ? units / line.unitsPerCarton() : null, cartons ? line.unitsPerCarton() : null, units,
                    line.unitPrice().amount(), rateOf(taxClass, rates, line.taxRate()), line.taxRate(),
                    taxClass == null));
        }
        List<DeliveredShipment.Fee> fees = new ArrayList<>();
        for (OrderFee fee : order.fees()) {
            TaxClass taxClass = fee.sku() == null ? TaxClass.valueOf(TaxRates.STANDARD) : classes.get(fee.sku().code());
            fees.add(new DeliveredShipment.Fee(fee.kind(), fee.sku() == null ? null : fee.sku().code(),
                    fee.amount().amount(), rateOf(taxClass, rates, fee.taxRate()), fee.taxRate(), taxClass == null));
        }

        String contractNumber = contracts.findLiveByOrderId(orderId).map(SalesContract::contractNumber).orElse(null);
        return Optional.of(new DeliveredShipment(orderId, order.orderNumber().value(), order.customerId(),
                contractNumber, shipmentId, shipment.carriage() == null ? null : shipment.carriage().shipmentCode(),
                shipment.deliveryBatchId(), deliveredAt, currency(order), lines, fees, orderUnits(order),
                units(shipment), orderGoodsValue(order), shippingFeeWaiver(order)));
    }

    /**
     * The order's shipping fee waived because shipments of it ended undelivered, with VAT, and the fee line with VAT it
     * is a share of (WF-05 decision Q-19, owner 2026-11-23: a shipment the carrier brought back waives the fee in
     * proportion to its goods' share of the order's goods value; a whole return waives all of it). Invoicing invoices
     * the fee less this share ({@code FeeAllocation.shippingFeeOwed}), so that it invoices what payment collects.
     *
     * <p><b>The one place the figure comes from.</b> Payment works the share out as each shipment's money is settled
     * ({@code OrderSettlement.shippingFeeShare}: the fee with VAT times the goods closed undelivered so far over the
     * order's goods as signed, half up, cumulative) and hands it back to the order ({@code
     * OrderSettlementResult.shippingFeeWaived}), which keeps it on the closed shipment. Until the order keeps it, this
     * works out the same total by the same rule from the order's shipments: the goods of every shipment that ended
     * undelivered ({@link #endedUndelivered}), each line's units valued with VAT ({@link OrderLine#grossOf}), over the
     * order's goods as signed. Cumulative half-up shares add up to the share of their total, so the two agree to the
     * dong while no bound of payment's clips a share. An order that ends with nothing delivered after a carrier return
     * waives the whole fee (owner, 23/11/2026), its rest closed before shipping or not.</p>
     */
    static ShippingFeeWaiver shippingFeeWaiver(Order order) {
        BigDecimal fee = order.fees().stream().filter(f -> f.kind() == FeeKind.SHIPPING_FEE)
                .map(f -> f.gross().amount()).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
        if (fee.signum() <= 0) {
            return ShippingFeeWaiver.NONE;
        }
        BigDecimal goods = order.lines().stream().map(line -> line.grossTotal().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal undelivered = BigDecimal.ZERO;
        for (OrderShipment shipment : order.shipments()) {
            if (!endedUndelivered(shipment)) {
                continue;
            }
            for (OrderLine line : order.lines()) {
                int units = Math.min(line.quantity(), shipment.quantities().getOrDefault(line.id(), 0));
                if (units > 0) {
                    undelivered = undelivered.add(line.grossOf(units).amount());
                }
            }
        }
        if (undelivered.signum() <= 0 || goods.signum() <= 0) {
            return new ShippingFeeWaiver(BigDecimal.ZERO, fee);
        }
        // Owner, 23/11/2026: an order that ends with nothing delivered after a carrier return waives the whole fee -
        // even when its rest was closed before it shipped, which the share alone would leave owing.
        boolean nothingDelivered = order.shipments().stream()
                .noneMatch(shipment -> shipment.status() == OrderShipmentStatus.DELIVERED);
        boolean nothingPending = order.shipments().stream().noneMatch(shipment ->
                shipment.status() == OrderShipmentStatus.HANDED_OVER
                        || shipment.status() == OrderShipmentStatus.DELIVERY_FAILED);
        boolean nothingToShip = order.lines().stream().allMatch(line -> order.unshippedUnits(line.id()) <= 0);
        if (nothingDelivered && nothingPending && nothingToShip) {
            return new ShippingFeeWaiver(fee, fee);
        }
        BigDecimal waived = undelivered.compareTo(goods) >= 0 ? fee
                : fee.multiply(undelivered).divide(goods, 0, RoundingMode.HALF_UP).min(fee);
        return new ShippingFeeWaiver(waived, fee);
    }

    /**
     * Whether a shipment's goods ended undelivered on this order, so that its share of the shipping fee is waived
     * (Q-19): it came back from the carrier (RETURNED - its units are closed as it is recorded). The one place that
     * says so: a shipment closed as lost (Q-12) and a RETURNED one still waiting for its return receipt (Q-7) are told
     * apart here once the order records when a shipment's units were closed.
     */
    private static boolean endedUndelivered(OrderShipment shipment) {
        return shipment.status() == OrderShipmentStatus.RETURNED;
    }

    /**
     * What the goods the order means to deliver as it stands now are worth before tax - its lines less what was
     * closed, at their prices: what invoicing shares the shipping fee by (Q-19), as {@code orderUnits} counts them.
     */
    private static BigDecimal orderGoodsValue(Order order) {
        return order.lines().stream().map(line -> line.unitPrice().amount()
                .multiply(BigDecimal.valueOf(line.quantity() - line.cancelledQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The shipping fee carries no SKU, so its rate is the day's STANDARD one - as on a delivery's invoice - and the
     * rate the order fixed is handed over beside it. The day is the one asked about - the day the order's rest was
     * closed, which is the date of the fee's invoice ({@code EInvoiceDrafts.drawUpShippingFeeRest}; owner's decision
     * on E0 M5) - not the last delivery's, which is handed over only as a fact about the order.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<OrderShippingFee> shippingFee(UUID orderId, Instant on) {
        Optional<Order> found = orders.findById(new OrderId(orderId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Order order = found.get();
        Optional<OrderFee> shipping = order.fees().stream()
                .filter(fee -> fee.kind() == FeeKind.SHIPPING_FEE && fee.amount().amount().signum() > 0)
                .findFirst();
        if (shipping.isEmpty()) {
            return Optional.empty();
        }
        OrderFee fee = shipping.get();
        Instant lastDeliveredAt = order.shipments().stream()
                .filter(shipment -> shipment.status() == OrderShipmentStatus.DELIVERED && shipment.outcomeAt() != null)
                .map(OrderShipment::outcomeAt)
                .max(Instant::compareTo)
                .orElse(null);
        Map<String, BigDecimal> rates = taxRates.onDay(BusinessCalendar.date(on));
        DeliveredShipment.Fee whole = new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null, fee.amount().amount(),
                rateOf(TaxClass.valueOf(TaxRates.STANDARD), rates, fee.taxRate()), fee.taxRate());
        String contractNumber = contracts.findLiveByOrderId(orderId).map(SalesContract::contractNumber).orElse(null);
        return Optional.of(new OrderShippingFee(orderId, order.orderNumber().value(), order.customerId(),
                contractNumber, currency(order), whole, orderUnits(order), lastDeliveredAt, List.of(),
                shippingFeeWaiver(order)));
    }

    /**
     * The shipping fee as {@link #shippingFee} gives it, and every plate fee above zero at the day's rate of its SKU's
     * class (the highest rate, flagged, for a product without one) - the fees the invoice of the rest of an order
     * carries when no delivery did (WF-05 BE-62).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<OrderShippingFee> orderFees(UUID orderId, Instant on) {
        Optional<Order> found = orders.findById(new OrderId(orderId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Order order = found.get();
        Optional<OrderShippingFee> shipping = shippingFee(orderId, on);
        Set<String> skus = new LinkedHashSet<>();
        order.fees().stream().filter(fee -> fee.kind() == FeeKind.PLATE_FEE && fee.sku() != null)
                .forEach(fee -> skus.add(fee.sku().code()));
        Map<String, TaxClass> classes = skus.isEmpty() ? Map.of() : products.taxClassesOf(skus);
        Map<String, BigDecimal> rates = taxRates.onDay(BusinessCalendar.date(on));
        List<DeliveredShipment.Fee> plates = new ArrayList<>();
        for (OrderFee fee : order.fees()) {
            if (fee.kind() != FeeKind.PLATE_FEE || fee.sku() == null || fee.amount().amount().signum() <= 0) {
                continue;
            }
            TaxClass taxClass = classes.get(fee.sku().code());
            plates.add(new DeliveredShipment.Fee(fee.kind(), fee.sku().code(), fee.amount().amount(),
                    rateOf(taxClass, rates, fee.taxRate()), fee.taxRate(), taxClass == null));
        }
        if (shipping.isEmpty() && plates.isEmpty()) {
            return Optional.empty();
        }
        if (shipping.isPresent()) {
            OrderShippingFee fee = shipping.get();
            return Optional.of(new OrderShippingFee(fee.orderId(), fee.orderNumber(), fee.customerId(),
                    fee.contractNumber(), fee.currency(), fee.fee(), fee.orderUnits(), fee.lastDeliveredAt(), plates,
                    fee.shippingFeeWaiver()));
        }
        Instant lastDeliveredAt = order.shipments().stream()
                .filter(shipment -> shipment.status() == OrderShipmentStatus.DELIVERED && shipment.outcomeAt() != null)
                .map(OrderShipment::outcomeAt)
                .max(Instant::compareTo)
                .orElse(null);
        String contractNumber = contracts.findLiveByOrderId(orderId).map(SalesContract::contractNumber).orElse(null);
        return Optional.of(new OrderShippingFee(orderId, order.orderNumber().value(), order.customerId(),
                contractNumber, currency(order), null, orderUnits(order), lastDeliveredAt, plates));
    }

    private static long orderUnits(Order order) {
        return order.lines().stream().mapToLong(line -> line.quantity() - line.cancelledQuantity()).sum();
    }

    private static String currency(Order order) {
        return order.lines().isEmpty() ? "VND" : order.lines().getFirst().unitPrice().currency().getCurrencyCode();
    }

    private static long units(OrderShipment shipment) {
        return shipment.quantities().values().stream().mapToLong(Integer::longValue).sum();
    }

    /**
     * The rate a product whose tax class is not known is taxed at: the highest rate, 10% (Luật Thuế giá trị gia tăng
     * 48/2024/QH15 Điều 9 khoản 4: goods whose rate cannot be determined take the highest). WF-05 Q-21.
     */
    static final BigDecimal HIGHEST_RATE = new BigDecimal("10.00");

    /**
     * The day's rate of the class.
     *
     * <p><b>No class</b> (the product was never given one, WF-05 BE-31, Q-21): the highest rate, {@link #HIGHEST_RATE},
     * and the line says its class is unknown ({@code taxClassUnknown}), which holds the draft back from the automatic
     * issue until the accountant looks. It used to take the order's own rate, silently: an order quoted at the 8% of
     * NĐ 174/2025 and delivered once the reduction ended went out at 8% without a flag. The law, not the quote,
     * decides a rate nobody can determine; a draft's rate never changes afterwards, so it is the right one from the
     * start (the accountant gives the product its class, voids the draft and draws it up again). Within a reduction
     * period the contract's lower rate then differs and is flagged as well.</p>
     *
     * <p><b>A known class without a rate that day</b>: the order's own rate; where the order fixed none either, the
     * day's STANDARD rate stands in - 0% is a rate of its own in law, not "no VAT", and an invoice must not go out at it
     * by default. The invoice flags every line whose order fixed no rate, so this guess is never issued without the
     * accountant.</p>
     */
    private static BigDecimal rateOf(TaxClass taxClass, Map<String, BigDecimal> rates, BigDecimal contractRate) {
        if (taxClass == null) {
            return HIGHEST_RATE;
        }
        BigDecimal ofDay = rates.get(taxClass.name());
        if (ofDay != null) {
            return ofDay;
        }
        if (contractRate != null) {
            return contractRate;
        }
        BigDecimal standard = rates.get(TaxRates.STANDARD);
        if (standard == null) {
            throw new IllegalStateException("ordering.tax_rate has no STANDARD rate");
        }
        return standard;
    }
}
