package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One line of an imported waybill file and what it came to (table {@code fulfillment.waybill_import_row}; kltn-docs 09
 * §4.4 step 3: "dòng lỗi giữ trong kết quả nhập kèm lý do"). The cells are kept as written, cut to the column's length:
 * a line refused for being too long still shows what it said. Append-only.
 */
@Entity
@Table(name = "waybill_import_row", schema = "fulfillment")
public class WaybillImportRowJpaEntity extends BaseEntity {

    @Column(name = "import_id", nullable = false, updatable = false) private UUID importId;
    @Column(name = "line_no", nullable = false, updatable = false) private int lineNo;
    @Column(name = "load_code", length = 64, updatable = false) private String loadCode;
    @Column(name = "waybill_no", length = 255, updatable = false) private String waybillNo;
    @Column(name = "vehicle_plate", length = 255, updatable = false) private String vehiclePlate;
    @Column(name = "driver_name", length = 255, updatable = false) private String driverName;
    @Column(name = "driver_phone", length = 255, updatable = false) private String driverPhone;
    @Column(name = "load_id", updatable = false) private UUID loadId;
    @Column(name = "outcome", nullable = false, length = 16, updatable = false) private String outcome;
    @Column(name = "problem", length = 32, updatable = false) private String problem;

    protected WaybillImportRowJpaEntity() {
    }

    public WaybillImportRowJpaEntity(UUID id, UUID importId, int lineNo, String loadCode, String waybillNo,
                                     String vehiclePlate, String driverName, String driverPhone, UUID loadId,
                                     String outcome, String problem) {
        super(id);
        this.importId = importId;
        this.lineNo = lineNo;
        this.loadCode = cut(loadCode, 64);
        this.waybillNo = cut(waybillNo, 255);
        this.vehiclePlate = cut(vehiclePlate, 255);
        this.driverName = cut(driverName, 255);
        this.driverPhone = cut(driverPhone, 255);
        this.loadId = loadId;
        this.outcome = outcome;
        this.problem = problem;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getImportId() { return importId; }
    public int getLineNo() { return lineNo; }
    public String getLoadCode() { return loadCode; }
    public String getWaybillNo() { return waybillNo; }
    public String getVehiclePlate() { return vehiclePlate; }
    public String getDriverName() { return driverName; }
    public String getDriverPhone() { return driverPhone; }
    public UUID getLoadId() { return loadId; }
    public String getOutcome() { return outcome; }
    public String getProblem() { return problem; }
}
