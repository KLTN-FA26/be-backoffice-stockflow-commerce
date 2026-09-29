package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.security.ScopedEntity;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="cycle_count",schema="inventory")
public class CycleCountJpaEntity extends BaseEntity implements ScopedEntity {
    public static final CycleCountJpaEntity SCOPE_PROTOTYPE=new CycleCountJpaEntity();
    @Column(name="request_id",nullable=false) private UUID requestId;
    @Column(name="warehouse_code",nullable=false,length=64) private String warehouseCode;
    @Column(name="assigned_to",nullable=false) private UUID assignedTo;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private CycleCountStatus status;
    @Column(name="approved_by") private UUID approvedBy;
    @Column(name="approved_at") private java.time.Instant approvedAt;
    @Column(name="posted_at") private java.time.Instant postedAt;
    @Column(length=1000) private String note;
    @Column(nullable=false) private long mutation;
    protected CycleCountJpaEntity() {}
    public CycleCountJpaEntity(UUID id,UUID requestId,String warehouse,UUID assignedTo,String note) {
        super(id); this.requestId=requestId; this.warehouseCode=warehouse; this.assignedTo=assignedTo;
        this.status=CycleCountStatus.PLANNED; this.note=note;
    }
    public CycleCount state() { return new CycleCount(status,assignedTo,approvedBy); }
    public void apply(CycleCount state,String note) { this.status=state.status(); this.assignedTo=state.counter(); this.approvedBy=state.approver(); this.note=note; }
    public void touch(){mutation++;}
    public void stamp(java.time.Instant now){
        if(approvedBy==null)approvedAt=null;
        else if(approvedAt==null)approvedAt=now;
        if(status==CycleCountStatus.POSTED && postedAt==null)postedAt=now;
    }
    public java.time.Instant getApprovedAt(){return approvedAt;}
    public java.time.Instant getPostedAt(){return postedAt;}
    public UUID getRequestId(){return requestId;}
    public String getWarehouseCode(){return warehouseCode;}
    public UUID getAssignedTo(){return assignedTo;}
    public CycleCountStatus getStatus(){return status;}
    public UUID getApprovedBy(){return approvedBy;}
    public String getNote(){return note;}
    @Override public String ownerAttribute(){return "assignedTo";}
    @Override public String warehouseAttribute(){return "warehouseCode";}
}
