package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.security.ScopedEntity;
import com.stockflow.order.internal.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="design_quote",schema="ordering")
public class DesignQuoteJpaEntity extends BaseEntity implements ScopedEntity {
    public static final DesignQuoteJpaEntity SCOPE_PROTOTYPE=new DesignQuoteJpaEntity();
    @Column(name="request_id",nullable=false,updatable=false) private UUID requestId;
    @Column(name="customer_id",nullable=false,updatable=false) private UUID customerId;
    @Column(name="customer_user_id",nullable=false,updatable=false) private UUID customerUserId;
    @Column(name="design_snapshot_id",nullable=false,updatable=false) private UUID designSnapshotId;
    @Column(nullable=false,length=64,updatable=false) private String sku;
    @Column(nullable=false) private int revision;
    @Column(name="current_revision_issued",nullable=false) private boolean currentRevisionIssued;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private DesignQuoteStatus status;
    @Column(name="accepted_by") private UUID acceptedBy;
    @Column(name="accepted_at") private Instant acceptedAt;
    @Column(name="consumed_order_id") private UUID consumedOrderId;
    @Column(name="response_note",length=2000) private String responseNote;
    protected DesignQuoteJpaEntity(){}
    public DesignQuoteJpaEntity(UUID id,UUID requestId,UUID customerId,UUID userId,UUID snapshot,String sku){
        super(id);this.requestId=requestId;this.customerId=customerId;this.customerUserId=userId;this.designSnapshotId=snapshot;
        this.sku=sku;this.revision=1;this.status=DesignQuoteStatus.DRAFT;
    }
    public DesignQuote state(){return new DesignQuote(status,customerUserId);}
    public void transition(DesignQuoteStatus status,String note){this.status=status;this.responseNote=note;if(status==DesignQuoteStatus.SENT)currentRevisionIssued=true;}
    public void revise(){revision++;status=DesignQuoteStatus.DRAFT;responseNote=null;currentRevisionIssued=false;}
    public void accept(UUID actor,Instant now){status=DesignQuoteStatus.ACCEPTED;acceptedBy=actor;acceptedAt=now;}
    public void consume(UUID orderId){status=DesignQuoteStatus.CONSUMED;consumedOrderId=orderId;}
    public UUID getCustomerId(){return customerId;}
    public UUID getCustomerUserId(){return customerUserId;}
    public UUID getDesignSnapshotId(){return designSnapshotId;}
    public String getSku(){return sku;}
    public int getRevision(){return revision;}
    public boolean isCurrentRevisionIssued(){return currentRevisionIssued;}
    public DesignQuoteStatus getStatus(){return status;}
    public UUID getAcceptedBy(){return acceptedBy;}
    public Instant getAcceptedAt(){return acceptedAt;}
    public UUID getConsumedOrderId(){return consumedOrderId;}
    public String getResponseNote(){return responseNote;}
    @Override public String ownerAttribute(){return "customerUserId";}
    @Override public String warehouseAttribute(){return null;}
}
