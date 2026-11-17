package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * An API_BOOKING carrier's service code for one vehicle type in one province (table
 * {@code fulfillment.carrier_service_code}; kltn-docs 09 §3 "mã dịch vụ của hãng theo loại xe", E24). Booking a load
 * with such a carrier picks the code of the load's vehicle type in the warehouse's province.
 *
 * <p>{@code provinceCode} is ours - the code an order's shipping address carries; {@code carrierCityCode} is the
 * carrier's id for that city (Ahamove {@code city_id}), which its list of services is asked by.</p>
 */
@Entity
@Table(name = "carrier_service_code", schema = "fulfillment")
public class CarrierServiceCodeJpaEntity extends BaseEntity {

    @Column(name = "carrier_id", nullable = false, updatable = false) private UUID carrierId;
    @Column(name = "vehicle_type_id", nullable = false, updatable = false) private UUID vehicleTypeId;
    @Column(name = "province_code", nullable = false, length = 20, updatable = false) private String provinceCode;
    @Column(name = "carrier_city_code", nullable = false, length = 32) private String carrierCityCode;
    @Column(name = "service_code", nullable = false, length = 64) private String serviceCode;

    protected CarrierServiceCodeJpaEntity() {
    }

    public CarrierServiceCodeJpaEntity(UUID id, UUID carrierId, UUID vehicleTypeId, String provinceCode,
                                       String carrierCityCode, String serviceCode) {
        super(id);
        this.carrierId = carrierId;
        this.vehicleTypeId = vehicleTypeId;
        this.provinceCode = provinceCode;
        this.carrierCityCode = carrierCityCode;
        this.serviceCode = serviceCode;
    }

    public UUID getCarrierId() { return carrierId; }
    public UUID getVehicleTypeId() { return vehicleTypeId; }
    public String getProvinceCode() { return provinceCode; }
    public String getCarrierCityCode() { return carrierCityCode; }
    public String getServiceCode() { return serviceCode; }
}
