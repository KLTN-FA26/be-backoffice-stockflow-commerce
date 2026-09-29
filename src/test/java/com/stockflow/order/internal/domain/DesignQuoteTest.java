package com.stockflow.order.internal.domain;
import com.stockflow.common.error.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class DesignQuoteTest {
    final Instant now=Instant.parse("2026-09-29T00:00:00Z");final UUID customer=UUID.randomUUID();
    QuoteOffer offer(){return QuoteOffer.create(1,2,new BigDecimal("3000000"),now.plusSeconds(3600),"Final unit price",now,UUID.randomUUID());}
    @Test void onlyCustomerCanAcceptAndExpiryIsEnforced(){
        var q=new DesignQuote(DesignQuoteStatus.SENT,customer);
        assertThatThrownBy(()->q.accept(UUID.randomUUID(),offer(),now)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->q.accept(customer,offer(),now.plusSeconds(3600))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.DESIGN_QUOTE_EXPIRED));
        assertThat(q.accept(customer,offer(),now)).isEqualTo(DesignQuoteStatus.ACCEPTED);
    }
    @Test void acceptedOrConsumedTermsCannotBeRevisedOrCancelled(){
        for(var status:new DesignQuoteStatus[]{DesignQuoteStatus.ACCEPTED,DesignQuoteStatus.CONSUMED}){
            var q=new DesignQuote(status,customer);
            assertThatThrownBy(q::revise).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->q.cancel("Changed mind")).isInstanceOf(BusinessException.class);
        }
    }
    @Test void quotationRejectsFractionalOrOverflowingMoney(){
        assertThatThrownBy(()->QuoteOffer.create(1,1,new BigDecimal("1.1"),now.plusSeconds(1),"Terms",now,customer)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->QuoteOffer.create(1,2,new BigDecimal("999999999999999"),now.plusSeconds(1),"Terms",now,customer)).isInstanceOf(BusinessException.class);
    }
}
