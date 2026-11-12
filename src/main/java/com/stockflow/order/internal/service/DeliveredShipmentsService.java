package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderShippingFee;
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
            lines.add(new DeliveredShipment.Line(line.id(), line.sku().code(),
                    products.nameForSku(line.sku().code()).orElse(line.sku().code()),
                    cartons ? units / line.unitsPerCarton() : null, cartons ? line.unitsPerCarton() : null, units,
                    line.unitPrice().amount(), rateOf(classes.get(line.sku().code()), rates, line.taxRate()),
                    line.taxRate()));
        }
        List<DeliveredShipment.Fee> fees = new ArrayList<>();
        for (OrderFee fee : order.fees()) {
            TaxClass taxClass = fee.sku() == null ? TaxClass.valueOf(TaxRates.STANDARD) : classes.get(fee.sku().code());
            fees.add(new DeliveredShipment.Fee(fee.kind(), fee.sku() == null ? null : fee.sku().code(),
                    fee.amount().amount(), rateOf(taxClass, rates, fee.taxRate()), fee.taxRate()));
        }

        String contractNumber = contracts.findLiveByOrderId(orderId).map(SalesContract::contractNumber).orElse(null);
        return Optional.of(new DeliveredShipment(orderId, order.orderNumber().value(), order.customerId(),
                contractNumber, shipmentId, shipment.carriage() == null ? null : shipment.carriage().shipmentCode(),
                shipment.deliveryBatchId(), deliveredAt, currency(order), lines, fees, orderUnits(order),
                units(shipment)));
    }

    /**
     * The shipping fee carries no SKU, so its rate is the day's STANDARD one - as on a delivery's invoice - and the
     * rate the order fixed is handed over beside it.
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
        Map<String, BigDecimal> rates = taxRates.onDay(BusinessCalendar.date(on));
        DeliveredShipment.Fee whole = new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null, fee.amount().amount(),
                rateOf(TaxClass.valueOf(TaxRates.STANDARD), rates, fee.taxRate()), fee.taxRate());
        String contractNumber = contracts.findLiveByOrderId(orderId).map(SalesContract::contractNumber).orElse(null);
        return Optional.of(new OrderShippingFee(orderId, order.orderNumber().value(), order.customerId(),
                contractNumber, currency(order), whole, orderUnits(order)));
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
     * The day's rate of the class; the order's own rate where the class or its rate is unknown. Where neither is
     * known the day's STANDARD rate stands in: 0% is a rate of its own in law, not "no VAT", and an invoice must not
     * go out at it by default. The invoice flags every line whose order fixed no rate, so this guess is never issued
     * without the accountant.
     */
    private static BigDecimal rateOf(TaxClass taxClass, Map<String, BigDecimal> rates, BigDecimal contractRate) {
        BigDecimal ofDay = taxClass == null ? null : rates.get(taxClass.name());
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
