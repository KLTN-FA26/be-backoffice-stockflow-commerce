package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.fulfillment.internal.controller.dto.WaybillFileResponse;
import com.stockflow.fulfillment.internal.service.OrderDeliveryDocuments;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * An order's delivery documents for Sales (kltn-docs 09 §2: Sales sends the customer the waybill file or the POD when
 * they ask in the chat; §7 row 16): the proofs of delivery of its shipments and the waybill files of the vehicles that
 * carried them, read with {@code fulfillment-delivery-documents:READ}. The customer holds no such permission (BR-05).
 */
@RestController
@RequestMapping("/api/v1/fulfillment/orders/{orderId}/delivery-documents")
@Tag(name = "Fulfillment - dispatch")
class OrderDeliveryDocumentController {

    private final OrderDeliveryDocuments documents;

    OrderDeliveryDocumentController(OrderDeliveryDocuments documents) {
        this.documents = documents;
    }

    @GetMapping
    @Operation(summary = "The order's shipments handed to a carrier, each with its proofs of delivery and the waybill "
            + "files of the vehicles it left on; 404 NOT_FOUND for no such order")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_DOCUMENTS, action = Action.READ)
    public ApiResponse<List<OrderDeliveryDocuments.ShipmentDocuments>> list(@PathVariable UUID orderId) {
        return ApiResponse.ok(documents.list(orderId));
    }

    @GetMapping("/pod-files/{fileId}/download-url")
    @Operation(summary = "A short-lived link saving a proof of delivery of the order under its name; audited")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_DOCUMENTS, action = Action.READ)
    public ResponseEntity<ApiResponse<WaybillFileResponse.Link>> podLink(@PathVariable UUID orderId,
                                                                        @PathVariable UUID fileId) {
        return link(documents.podDownloadLink(orderId, fileId));
    }

    @GetMapping("/waybill-files/{fileId}/download-url")
    @Operation(summary = "A short-lived link saving a waybill file of a vehicle that carried the order; audited")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_DOCUMENTS, action = Action.READ)
    public ResponseEntity<ApiResponse<WaybillFileResponse.Link>> waybillLink(@PathVariable UUID orderId,
                                                                            @PathVariable UUID fileId) {
        return link(documents.waybillDownloadLink(orderId, fileId));
    }

    private static ResponseEntity<ApiResponse<WaybillFileResponse.Link>> link(DownloadLink link) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new WaybillFileResponse.Link(link.url(), link.expiresAt())));
    }
}
