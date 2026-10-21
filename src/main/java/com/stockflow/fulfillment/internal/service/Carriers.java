package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;

import java.util.UUID;

/**
 * The carrier catalogue (G4-2a; kltn-docs 09 §3: a shipment is handed to a carrier "chọn từ danh sách hãng đã khai
 * báo: tên, liên hệ"). Read by dispatch staff and coordinators; kept by the warehouse manager.
 */
public interface Carriers {

    record CarrierView(UUID id, String code, String name, String contactName, String contactPhone, String status,
                       long version) { }

    /**
     * @param version required to change an existing carrier: the version read (optimistic lock); ignored on create
     */
    record SaveCarrier(String code, String name, String contactName, String contactPhone, String status,
                       Long version) { }

    /** Paged, by name; {@code status} ACTIVE / INACTIVE or null for both; {@code search} in code or name. */
    PageResponse<CarrierView> list(String status, String search, int page, int size);

    CarrierView get(UUID id);

    /** @throws com.stockflow.common.error.BusinessException {@code CARRIER_CODE_EXISTS}; {@code VALIDATION_FAILED} */
    CarrierView create(SaveCarrier carrier);

    /**
     * Name, contact, status and code. An INACTIVE carrier keeps its shipments and loads and takes no new load.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_NOT_FOUND}; {@code CARRIER_CODE_EXISTS};
     *         {@code OPTIMISTIC_LOCK} when {@code version} is not the current one
     */
    CarrierView update(UUID id, SaveCarrier carrier);
}
