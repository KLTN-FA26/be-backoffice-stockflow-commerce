package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Where an API_BOOKING carrier picks up at one warehouse (table {@code fulfillment.carrier_pickup_point}; kltn-docs 09
 * BR-29 "điểm lấy hàng là kho xuất", E24). The address is written the way the carrier reads it, which a warehouse's own
 * free-text address need not be; the province picks the service code and bounds the drop-offs.
 */
@Entity
@Table(name = "carrier_pickup_point", schema = "fulfillment")
public class CarrierPickupPointJpaEntity extends BaseEntity {

    @Column(name = "carrier_id", nullable = false, updatable = false) private UUID carrierId;
    @Column(name = "warehouse_id", nullable = false, updatable = false) private UUID warehouseId;
    @Column(name = "province_code", nullable = false, length = 20) private String provinceCode;
    @Column(name = "address", nullable = false, length = 500) private String address;
    @Column(name = "contact_name", length = 120) private String contactName;
    @Column(name = "contact_phone", length = 32) private String contactPhone;

    protected CarrierPickupPointJpaEntity() {
    }

    public CarrierPickupPointJpaEntity(UUID id, UUID carrierId, UUID warehouseId, String provinceCode, String address,
                                       String contactName, String contactPhone) {
        super(id);
        this.carrierId = carrierId;
        this.warehouseId = warehouseId;
        this.provinceCode = provinceCode;
        this.address = address;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
    }

    public UUID getCarrierId() { return carrierId; }
    public UUID getWarehouseId() { return warehouseId; }
    public String getProvinceCode() { return provinceCode; }
    public String getAddress() { return address; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
}
