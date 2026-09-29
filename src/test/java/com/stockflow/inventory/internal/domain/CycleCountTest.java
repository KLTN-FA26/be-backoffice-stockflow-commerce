package com.stockflow.inventory.internal.domain;
import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class CycleCountTest {
    @Test void varianceNeedsIndependentApprovalBeforePosting(){
        UUID counter=UUID.randomUUID();var count=new CycleCount(CycleCountStatus.PLANNED,counter,null).start().submit(true);
        assertThatThrownBy(count::post).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> count.approve(counter)).isInstanceOf(BusinessException.class);
        assertThat(count.approve(UUID.randomUUID()).post().status()).isEqualTo(CycleCountStatus.POSTED);
    }
    @Test void zeroVariancePostsWithoutAnAdjustment(){
        assertThat(new CycleCount(CycleCountStatus.PLANNED,UUID.randomUUID(),null).start().submit(false).status()).isEqualTo(CycleCountStatus.POSTED);
    }
    @Test void recountInvalidatesApprovalAndPostedCountsCannotReopen(){
        var approved=new CycleCount(CycleCountStatus.APPROVED,UUID.randomUUID(),UUID.randomUUID());
        assertThat(approved.recount().approver()).isNull();
        assertThatThrownBy(() -> approved.post().recount()).isInstanceOf(BusinessException.class);
    }
    @Test void quantityReasonReservationsAndSerialAreEnforced(){
        assertThatThrownBy(() -> CycleCount.validateQuantity(10,9,0,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CycleCount.validateQuantity(10,3,4,null,"Missing")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CycleCount.validateQuantity(1,2,0,"SN-1","Found")).isInstanceOf(BusinessException.class);
    }
}
