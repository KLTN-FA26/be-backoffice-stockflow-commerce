package com.stockflow.fulfillment.internal.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * An advisory load estimate (table {@code fulfillment.load_estimate}; SCRUM-287 PR EST, LP2-1). Written once and then
 * only superseded: what was estimated - lines, layout, warnings - never changes, so a superseded estimate still shows
 * what the coordinator saw. The JSON columns hold the API's own shapes; the service reads and writes them.
 */
@Entity
@Table(name = "load_estimate", schema = "fulfillment")
public class LoadEstimateJpaEntity extends BaseEntity {

    public static final String CURRENT = "CURRENT";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Column(name = "source_type", nullable = false, updatable = false, length = 16) private String sourceType;
    @Column(name = "source_id", nullable = false, updatable = false) private UUID sourceId;
    @Column(name = "warehouse_id", updatable = false) private UUID warehouseId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vehicle_type_ids", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode vehicleTypeIds;
    @Column(name = "mode", nullable = false, updatable = false, length = 20) private String mode;
    @Column(name = "status", nullable = false, length = 16) private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode lines;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode layout;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode warnings;

    protected LoadEstimateJpaEntity() {
    }

    public LoadEstimateJpaEntity(UUID id, String sourceType, UUID sourceId, UUID warehouseId, JsonNode vehicleTypeIds,
                                 String mode, JsonNode lines, JsonNode layout, JsonNode warnings) {
        super(id);
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.warehouseId = warehouseId;
        this.vehicleTypeIds = vehicleTypeIds;
        this.mode = mode;
        this.status = CURRENT;
        this.lines = lines;
        this.layout = layout;
        this.warnings = warnings;
    }

    /** A newer estimate of the same source replaced this one. */
    public void supersede() {
        this.status = SUPERSEDED;
    }

    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public UUID getWarehouseId() { return warehouseId; }
    public JsonNode getVehicleTypeIds() { return vehicleTypeIds; }
    public String getMode() { return mode; }
    public String getStatus() { return status; }
    public JsonNode getLines() { return lines; }
    public JsonNode getLayout() { return layout; }
    public JsonNode getWarnings() { return warnings; }
}
