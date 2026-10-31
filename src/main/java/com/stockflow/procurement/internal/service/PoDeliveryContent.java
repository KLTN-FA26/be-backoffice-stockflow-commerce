package com.stockflow.procurement.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PoLineStatus;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.product.api.ProductService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What every {@link PurchaseOrderSent} that carries an order to its supplier is made of: where it goes, who the buyer
 * is, and the lines. Shared by the order's first send and its recovery ({@code ProcurementServiceImpl}) and by the
 * notice of an approved amendment ({@link SupplierAmendmentNotice}), so the three cannot drift apart on the rules for
 * a deliverable contact, a described line or a line that is no longer ordered.
 */
@Component
class PoDeliveryContent {

    /** The supplier's channel ({@code EMAIL} or {@code API}) and the address on it, already validated. */
    record Target(String channel, String recipient) {
    }

    private final SupplierRepository suppliers;
    private final NotificationService notifications;
    private final PoCommunicationProfile communication;
    private final ProductService products;

    PoDeliveryContent(SupplierRepository suppliers, NotificationService notifications,
                      PoCommunicationProfile communication, ProductService products) {
        this.suppliers = suppliers;
        this.notifications = notifications;
        this.communication = communication;
        this.products = products;
    }

    /**
     * The supplier's current delivery contact.
     *
     * @throws BusinessException {@code SUPPLIER_NOT_FOUND}; {@code CONFLICT} when the supplier is not active or has no
     *                           contact on its channel; the notification module's refusal of an unusable contact
     */
    Target target(UUID supplierId) {
        var supplier = suppliers.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                        "No supplier with id " + supplierId))
                .details();
        String recipient = supplier.communicationChannel() == SupplierCommunicationChannel.EMAIL
                ? supplier.email() : supplier.apiEndpoint();
        if (supplier.status() != SupplierStatus.ACTIVE || recipient == null || recipient.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "An active supplier with a delivery contact is required");
        }
        notifications.validateSupplierDelivery(supplier.communicationChannel().name(), recipient);
        return new Target(supplier.communicationChannel().name(), recipient);
    }

    /** The buyer the order was first sent under (the latest snapshot), else the deployment's own identity. */
    PurchaseOrderSent.Buyer buyer(Optional<PurchaseOrderSent> latestSnapshot) {
        return latestSnapshot.map(PurchaseOrderSent::buyer).orElseGet(communication::requireBuyer);
    }

    /** The lines still ordered: a CANCELLED or WITHDRAWN line is not part of what the supplier is asked for. */
    List<PurchaseOrderSent.Line> lines(PurchaseOrder order) {
        return order.lines().stream()
                .filter(line -> line.status() != PoLineStatus.CANCELLED && line.status() != PoLineStatus.WITHDRAWN)
                .map(line -> new PurchaseOrderSent.Line(line.sku().code(), description(line), line.quantityOrdered(),
                        line.unitPrice().amount()))
                .toList();
    }

    private String description(PoLine line) {
        if (line.description() != null && !line.description().isBlank()) return line.description().trim();
        return products.nameForSku(line.sku().code())
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.PO_LINE_DESCRIPTION_REQUIRED,
                        "Missing product description for SKU " + line.sku().code()));
    }
}
