package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;

/** A physical count never rewrites stock until its variance has been independently approved. */
public record CycleCount(CycleCountStatus status, UUID counter, UUID approver) {
    public CycleCount start() { require(CycleCountStatus.PLANNED); return state(CycleCountStatus.COUNTING,null); }
    public CycleCount submit(boolean variance) {
        require(CycleCountStatus.COUNTING);
        return state(variance ? CycleCountStatus.VARIANCE_REVIEW : CycleCountStatus.POSTED,null);
    }
    public CycleCount approve(UUID actor) {
        require(CycleCountStatus.VARIANCE_REVIEW);
        if (counter.equals(actor)) throw new BusinessException(ErrorCode.COUNT_SELF_APPROVAL);
        return state(CycleCountStatus.APPROVED,actor);
    }
    public CycleCount post() { require(CycleCountStatus.APPROVED); return state(CycleCountStatus.POSTED,approver); }
    public CycleCount recount() {
        if (status!=CycleCountStatus.COUNTING && status!=CycleCountStatus.VARIANCE_REVIEW && status!=CycleCountStatus.APPROVED)
            throw new BusinessException(ErrorCode.CONFLICT);
        return state(CycleCountStatus.COUNTING,null);
    }
    public CycleCount cancel() { require(CycleCountStatus.PLANNED); return state(CycleCountStatus.CANCELLED,null); }
    public CycleCount reassign(UUID replacement) {
        if(status==CycleCountStatus.POSTED || status==CycleCountStatus.CANCELLED)
            throw new BusinessException(ErrorCode.CONFLICT);
        return new CycleCount(status==CycleCountStatus.PLANNED?status:CycleCountStatus.COUNTING,replacement,null);
    }
    public void require(CycleCountStatus expected) {
        if(status!=expected) throw new BusinessException(ErrorCode.CONFLICT);
    }
    private CycleCount state(CycleCountStatus next,UUID approved) { return new CycleCount(next,counter,approved); }
    public static void validateQuantity(int baseline,int counted,int reserved,String serial,String reason) {
        validateMeasurement(baseline,counted,serial,reason);
        if(counted<reserved) throw new BusinessException(ErrorCode.COUNT_RESERVED_CONFLICT);
    }
    public static void validateMeasurement(int baseline,int counted,String serial,String reason) {
        if(counted<0 || serial!=null && counted>1) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if(counted!=baseline && (reason==null || reason.isBlank())) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
