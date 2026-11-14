package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.fulfillment.api.ShipmentCartons;
import com.stockflow.fulfillment.api.ShippedCarton;
import com.stockflow.fulfillment.api.ShippedCartons;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.order.api.DeliveryBatchReferences;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.RedeliveryToPick;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * The delivery note of a shipment (kltn-docs 09 §4.2; glossary "POD"; {@link DeliveryNoteDocument}): read together
 * here from the shipment, the pick that made it, the vehicles it is on, and - through their own modules - the order,
 * its contract's delivery batch, the customer and the warehouse.
 *
 * <p>Printable from READY_TO_DISPATCH on - the dispatcher prints it for the driver before the vehicle leaves, and again
 * after if a copy is lost - but not for a CANCELLED shipment, which hands nothing over (409
 * {@code SHIPMENT_STATE_CONFLICT}). Limited to the caller's warehouses, as every shipment read is.</p>
 *
 * <p>The delivery batch is the pick's (4B-8: one pick per released batch); a redelivery's pick names its delivery
 * failure case instead, whose batch it is. The vehicles are the loads holding the shipment now, plus the one it left
 * on: a shipment may span several vehicles of a plan (LP-3), and each is a line of the note.</p>
 */
@Service
@Transactional(readOnly = true)
public class DeliveryNoteService {

    /** The PDF and the name it is offered under. */
    public record Pdf(String fileName, byte[] content) {
    }

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ShipmentJpaRepository shipments;
    private final ShipmentContext context;
    private final PickJpaRepository picks;
    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadShipmentJpaRepository members;
    private final CarrierJpaRepository carriers;
    private final ShipmentCartons cartons;
    private final OrderService orders;
    private final DeliveryBatchReferences batches;
    private final CustomerLegalProfiles legalProfiles;
    private final CustomerService customers;
    private final WarehouseService warehouses;
    private final DeliveryNoteDocument document;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    DeliveryNoteService(ShipmentJpaRepository shipments, ShipmentContext context, PickJpaRepository picks,
                        DispatchLoadJpaRepository loads, DispatchLoadShipmentJpaRepository members,
                        CarrierJpaRepository carriers, ShipmentCartons cartons, OrderService orders,
                        DeliveryBatchReferences batches, CustomerLegalProfiles legalProfiles,
                        CustomerService customers, WarehouseService warehouses, DeliveryNoteDocument document,
                        Clock clock) {
        this.shipments = shipments;
        this.context = context;
        this.picks = picks;
        this.loads = loads;
        this.members = members;
        this.carriers = carriers;
        this.cartons = cartons;
        this.orders = orders;
        this.batches = batches;
        this.legalProfiles = legalProfiles;
        this.customers = customers;
        this.warehouses = warehouses;
        this.document = document;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} for a shipment of another
     *         warehouse; {@code SHIPMENT_STATE_CONFLICT} for a cancelled one
     */
    public Pdf deliveryNote(UUID shipmentId) {
        ShipmentJpaEntity shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND));
        WarehouseScope.requireWarehouse(shipment.getWarehouseId());
        if (shipment.getStatus() == ShipmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s was cancelled: it hands "
                    + "nothing over, so it has no delivery note").formatted(shipment.getCode()));
        }
        OrderSummary order = orders.findById(shipment.getOrderId()).orElseThrow(() -> new IllegalStateException(
                "Shipment " + shipment.getCode() + " names an order that does not exist"));
        Optional<PickJpaEntity> pick = Optional.ofNullable(context.tasks(List.of(shipment)).get(shipment.getId()))
                .flatMap(picks::findById);
        UUID caseId = pick.map(PickJpaEntity::getRedeliveryCaseId).orElse(null);
        UUID batchId = caseId != null
                ? orders.redelivery(caseId).map(RedeliveryToPick::deliveryBatchId).orElse(null)
                : pick.map(PickJpaEntity::getDeliveryBatchId).orElse(null);
        Optional<DeliveryBatchReferences.Reference> batch = batches.of(order.orderId(), batchId);
        Optional<WarehouseView> warehouse = warehouses.findWarehouse(shipment.getWarehouseId());
        OrderSummary.AddressSummary address = order.shippingAddress();
        List<ShippedCarton> carried = cartons.ofShipment(shipment.getId()).map(ShippedCartons::cartons)
                .orElse(List.of());

        DeliveryNoteDocument.Facts facts = new DeliveryNoteDocument.Facts(shipment.getCode(), order.orderNumber(),
                batch.map(DeliveryBatchReferences.Reference::contractNumber).orElse(null),
                batch.map(DeliveryBatchReferences.Reference::seq).orElse(null), caseId != null,
                warehouse.map(WarehouseView::name).orElse(null), warehouse.map(WarehouseView::address).orElse(null),
                customerName(order.customerId()), address == null ? null : address(address),
                address == null ? null : recipient(address), vehicles(shipment), shipment.getHandedOverAt(),
                DeliveryNoteDocument.bySku(carried),
                LocalDate.ofInstant(clock.instant(), VIETNAM));
        return new Pdf(shipment.getCode() + "-delivery-note.pdf", document.render(facts));
    }

    /** The company the contract names as the buyer, or the customer's name while its legal profile is not recorded. */
    private String customerName(UUID customerId) {
        return legalProfiles.legalProfile(customerId).map(CustomerLegalProfile::companyName)
                .orElseGet(() -> customers.fullNames(List.of(customerId)).get(customerId));
    }

    /** The loads holding the shipment now and the one it left on, but none called off; by load code. */
    private List<DeliveryNoteDocument.Vehicle> vehicles(ShipmentJpaEntity shipment) {
        Set<UUID> ids = new HashSet<>();
        members.findByShipmentIdInAndActiveTrue(List.of(shipment.getId())).forEach(m -> ids.add(m.getLoadId()));
        if (shipment.getLoadId() != null) {
            ids.add(shipment.getLoadId());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<DispatchLoadJpaEntity> found = loads.findByIdIn(ids).stream()
                .filter(load -> load.getStatus() != LoadStatus.CANCELLED)
                .sorted(Comparator.comparing(DispatchLoadJpaEntity::getCode))
                .toList();
        Map<UUID, String> carrierNames = new HashMap<>();
        carriers.findAllById(found.stream().map(DispatchLoadJpaEntity::getCarrierId)
                .filter(Objects::nonNull).distinct().toList())
                .forEach(carrier -> carrierNames.put(carrier.getId(), carrier.getName()));
        return found.stream().map(load -> new DeliveryNoteDocument.Vehicle(load.getCode(),
                carrierNames.get(load.getCarrierId()), load.getVehiclePlate(), load.getDriverName(),
                load.getDriverPhone(), load.getWaybillNo())).toList();
    }

    private static String address(OrderSummary.AddressSummary a) {
        StringJoiner joined = new StringJoiner(", ");
        for (String part : new String[] {a.line1(), a.line2(), a.wardName(), a.provinceName()}) {
            if (part != null && !part.isBlank()) {
                joined.add(part);
            }
        }
        return joined.toString();
    }

    private static String recipient(OrderSummary.AddressSummary a) {
        StringJoiner joined = new StringJoiner(" - ");
        for (String part : new String[] {a.recipientName(), a.phone()}) {
            if (part != null && !part.isBlank()) {
                joined.add(part);
            }
        }
        return joined.toString();
    }
}
