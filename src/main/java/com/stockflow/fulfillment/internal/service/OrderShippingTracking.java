package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.TrackingMilestoneJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How the goods of a customer's own order travel, vehicle by vehicle (3-6; kltn-docs 17 BR-27, §5.2 "theo dõi giao
 * hàng"; 09 §4.5 step 9): for each vehicle that left with goods of the order - one carrying only part of a shipment too
 * (LP-3: a shipment's parcels may span vehicles), as soon as it leaves - the carrier's name, the waybill number,
 * the plate, the journey's milestones as the carrier reported them (picked up, on its way, returning, returned) with
 * their times, and each of the order's shipments on it with its outcome and when (delivered, not delivered, back). A
 * carrier without webhooks reports no milestone: the shipments' outcomes, recorded by the warehouse, are the journey.
 *
 * <p>The customer is the one signed in, resolved here; another customer's order, or none, is 404 either way. Never the
 * driver, the waybill files or the proof of delivery (17 BR-27: asked of Sales through the chat).</p>
 */
@Service
@Transactional(readOnly = true)
public class OrderShippingTracking {

    /** A milestone of the journey, at the carrier's time. */
    public record Milestone(String milestone, Instant at) { }

    /** One of the order's shipments on the vehicle and where it stands. */
    public record ShipmentProgress(String shipmentCode, String status, Instant outcomeAt) { }

    /** One vehicle with goods of the order. */
    public record Vehicle(String loadCode, String carrierName, String waybillNo, String vehiclePlate,
                          Instant handedOverAt, List<Milestone> milestones, List<ShipmentProgress> shipments) { }

    private final CustomerService customers;
    private final OrderService orders;
    private final ShipmentJpaRepository shipments;
    private final DispatchLoadJpaRepository loads;
    private final CarrierJpaRepository carriers;
    private final TrackingMilestoneJpaRepository milestones;
    private final com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository loaded;

    public OrderShippingTracking(CustomerService customers, OrderService orders, ShipmentJpaRepository shipments,
                                 DispatchLoadJpaRepository loads, CarrierJpaRepository carriers,
                                 TrackingMilestoneJpaRepository milestones,
                                 com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository loaded) {
        this.customers = customers;
        this.orders = orders;
        this.shipments = shipments;
        this.loads = loads;
        this.carriers = carriers;
        this.milestones = milestones;
        this.loaded = loaded;
    }

    /**
     * The vehicles of one of the signed-in customer's own orders, in the order they left.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND} for a sign-in without a customer; {@code NOT_FOUND} for
     *         another customer's order or none
     */
    public List<Vehicle> vehicles(UUID orderId, CurrentUser user) {
        CustomerSummary customer = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        OrderSummary order = orders.findById(orderId).filter(o -> customer.customerId().equals(o.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        Map<UUID, ShipmentJpaEntity> ofOrder = shipments.findByOrderIdOrderByReadyAtAscIdAsc(order.orderId()).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        if (ofOrder.isEmpty()) {
            return List.of();
        }
        // The vehicles the order's parcels are on (a shipment may span several), and the one each shipment that left
        // records (a shipment of the parcel era has no parcel rows on a load).
        Map<UUID, Set<UUID>> shipmentsByLoad = new LinkedHashMap<>();
        loaded.findByShipmentIdInAndActiveTrue(ofOrder.keySet()).forEach(p -> shipmentsByLoad
                .computeIfAbsent(p.getLoadId(), k -> new java.util.LinkedHashSet<>()).add(p.getShipmentId()));
        ofOrder.values().stream().filter(s -> s.getLoadId() != null).forEach(s -> shipmentsByLoad
                .computeIfAbsent(s.getLoadId(), k -> new java.util.LinkedHashSet<>()).add(s.getId()));
        List<DispatchLoadJpaEntity> left = loads.findByIdIn(shipmentsByLoad.keySet()).stream()
                .filter(l -> l.getStatus() == LoadStatus.DISPATCHED && l.getDispatchedAt() != null)
                .sorted(Comparator.comparing(DispatchLoadJpaEntity::getDispatchedAt)
                        .thenComparing(DispatchLoadJpaEntity::getId)).toList();
        if (left.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> carrierNames = carriers.findAllById(left.stream().map(DispatchLoadJpaEntity::getCarrierId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(CarrierJpaEntity::getId, CarrierJpaEntity::getName));
        return left.stream().map(load -> new Vehicle(load.getCode(), carrierNames.get(load.getCarrierId()),
                load.getWaybillNo(), load.getVehiclePlate(), load.getDispatchedAt(),
                milestones.findByLoadIdOrderByCarrierReportedAtAsc(load.getId()).stream()
                        .map(m -> new Milestone(m.getMilestone(), m.getCarrierReportedAt())).toList(),
                shipmentsByLoad.get(load.getId()).stream().map(ofOrder::get).filter(Objects::nonNull)
                        .map(s -> new ShipmentProgress(s.getCode(), s.getStatus().name(), s.getOutcomeAt())).toList()))
                .toList();
    }
}
