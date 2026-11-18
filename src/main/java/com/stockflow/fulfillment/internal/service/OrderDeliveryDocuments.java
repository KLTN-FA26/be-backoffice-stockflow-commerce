package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadWaybillFileJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentPodFileJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadWaybillFileJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentPodFileJpaRepository;
import com.stockflow.order.api.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The delivery documents of one order, as Sales sends them to a customer who asks in the chat (kltn-docs 09 §2 "gửi
 * khách file vận đơn / POD khi khách xin qua chat", §7 row 16, BR-28): the proofs of delivery kept with its shipments
 * and the waybill files of the vehicles that carried them. Read with {@code fulfillment-delivery-documents:READ}, which
 * the customer never holds (BR-05, 17 BR-27: the customer does not see them); warehouse staff read the same files
 * through the shipment and the load.
 */
@Service
@Transactional(readOnly = true)
public class OrderDeliveryDocuments {

    /** A file as the order lists it; the bytes come from its download link. */
    public record Document(UUID fileId, String fileName, String contentType, long sizeBytes, Instant uploadedAt) { }

    /** A vehicle that carried goods of the order, with its waybill files. */
    public record Vehicle(UUID loadId, String loadCode, String waybillNo, List<Document> waybillFiles) { }

    /** One shipment of the order, with its proofs of delivery and the vehicles it left on. */
    public record ShipmentDocuments(UUID shipmentId, String shipmentCode, String status, List<Document> podFiles,
                                    List<Vehicle> vehicles) { }

    private final OrderService orders;
    private final ShipmentJpaRepository shipments;
    private final ShipmentPodFileJpaRepository podFiles;
    private final DispatchLoadShipmentJpaRepository manifests;
    private final DispatchLoadJpaRepository loads;
    private final LoadWaybillFileJpaRepository waybillFiles;
    private final FileTransfers transfers;

    @SuppressWarnings("java:S107")
    OrderDeliveryDocuments(OrderService orders, ShipmentJpaRepository shipments, ShipmentPodFileJpaRepository podFiles,
                           DispatchLoadShipmentJpaRepository manifests, DispatchLoadJpaRepository loads,
                           LoadWaybillFileJpaRepository waybillFiles, FileTransfers transfers) {
        this.orders = orders;
        this.shipments = shipments;
        this.podFiles = podFiles;
        this.manifests = manifests;
        this.loads = loads;
        this.waybillFiles = waybillFiles;
        this.transfers = transfers;
    }

    /**
     * Every shipment of the order handed to a carrier, in the order they were made.
     *
     * @throws BusinessException {@code NOT_FOUND} for no such order
     */
    public List<ShipmentDocuments> list(UUID orderId) {
        requireOrder(orderId);
        List<ShipmentJpaEntity> left = shipments.findByOrderIdOrderByReadyAtAscIdAsc(orderId).stream()
                .filter(s -> s.getHandedOverAt() != null).toList();
        if (left.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DispatchLoadJpaEntity>> vehiclesOf = vehiclesOf(left.stream().map(ShipmentJpaEntity::getId)
                .collect(Collectors.toSet()));
        return left.stream().map(s -> new ShipmentDocuments(s.getId(), s.getCode(), s.getStatus().name(),
                podFiles.findByShipmentIdOrderByUploadedAtAscIdAsc(s.getId()).stream()
                        .map(OrderDeliveryDocuments::document).toList(),
                vehiclesOf.getOrDefault(s.getId(), List.of()).stream().map(l -> new Vehicle(l.getId(), l.getCode(),
                        l.getWaybillNo(), waybillFiles.findByLoadIdOrderByUploadedAtAscIdAsc(l.getId()).stream()
                        .map(OrderDeliveryDocuments::document).toList())).toList())).toList();
    }

    /**
     * A link saving a proof of delivery of one of the order's shipments; audited, as the file leaves the system.
     *
     * @throws BusinessException {@code NOT_FOUND} for no such order, or a file of no shipment of it
     */
    @Auditable(action = AuditAction.EXPORT, resourceType = "shipment-pod-file", resourceId = "#fileId",
            details = "{orderId: #orderId}")
    public DownloadLink podDownloadLink(UUID orderId, UUID fileId) {
        requireOrder(orderId);
        ShipmentPodFileJpaEntity file = podFiles.findById(fileId)
                .filter(f -> shipments.findById(f.getShipmentId()).map(ShipmentJpaEntity::getOrderId)
                        .filter(orderId::equals).isPresent())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No proof of delivery " + fileId
                        + " of order " + orderId));
        return transfers.downloadLink(file.getStorageKey(), file.getFileName());
    }

    /**
     * A link saving a waybill file of a vehicle that carried goods of the order; audited.
     *
     * @throws BusinessException {@code NOT_FOUND} for no such order, or a file of no vehicle that carried it
     */
    @Auditable(action = AuditAction.EXPORT, resourceType = "load-waybill-file", resourceId = "#fileId",
            details = "{orderId: #orderId}")
    public DownloadLink waybillDownloadLink(UUID orderId, UUID fileId) {
        requireOrder(orderId);
        Set<UUID> carried = vehiclesOf(shipments.findByOrderIdOrderByReadyAtAscIdAsc(orderId).stream()
                .filter(s -> s.getHandedOverAt() != null).map(ShipmentJpaEntity::getId).collect(Collectors.toSet()))
                .values().stream().flatMap(List::stream).map(DispatchLoadJpaEntity::getId).collect(Collectors.toSet());
        LoadWaybillFileJpaEntity file = waybillFiles.findById(fileId).filter(f -> carried.contains(f.getLoadId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No waybill file " + fileId
                        + " of a vehicle that carried order " + orderId));
        return transfers.downloadLink(file.getStorageKey(), file.getFileName());
    }

    private void requireOrder(UUID orderId) {
        if (orders.findById(orderId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId);
        }
    }

    /** The vehicles that left with each shipment (a shipment may span vehicles, LP-3), by shipment. */
    private Map<UUID, List<DispatchLoadJpaEntity>> vehiclesOf(Set<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return Map.of();
        }
        List<DispatchLoadShipmentJpaEntity> rows = manifests.findByShipmentIdIn(shipmentIds);
        Map<UUID, DispatchLoadJpaEntity> left = loads.findByIdIn(rows.stream().map(DispatchLoadShipmentJpaEntity::getLoadId)
                        .collect(Collectors.toSet())).stream().filter(l -> l.getStatus() == LoadStatus.DISPATCHED)
                .collect(Collectors.toMap(DispatchLoadJpaEntity::getId, Function.identity()));
        return rows.stream().filter(r -> left.containsKey(r.getLoadId())).collect(Collectors.groupingBy(
                DispatchLoadShipmentJpaEntity::getShipmentId, Collectors.collectingAndThen(Collectors.toList(),
                        list -> list.stream().map(r -> left.get(r.getLoadId())).distinct()
                                .sorted(Comparator.comparing(DispatchLoadJpaEntity::getCode)).toList())));
    }

    private static Document document(ShipmentPodFileJpaEntity f) {
        return new Document(f.getId(), f.getFileName(), f.getContentType(), f.getSizeBytes(), f.getUploadedAt());
    }

    private static Document document(LoadWaybillFileJpaEntity f) {
        return new Document(f.getId(), f.getFileName(), f.getContentType(), f.getSizeBytes(), f.getUploadedAt());
    }
}
