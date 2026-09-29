package com.stockflow.order.internal.domain;

import com.stockflow.common.error.*;
import java.time.Instant;
import java.util.UUID;

/** Customer assent is separate from company issuance. Accepted terms cannot be silently revised. */
public record DesignQuote(DesignQuoteStatus status,UUID customerUserId) {
    public DesignQuoteStatus issue(QuoteOffer offer,Instant now){require(DesignQuoteStatus.DRAFT);offer.requireValid(now);return DesignQuoteStatus.SENT;}
    public DesignQuoteStatus accept(UUID actor,QuoteOffer offer,Instant now){
        customer(actor);require(DesignQuoteStatus.SENT);offer.requireValid(now);return DesignQuoteStatus.ACCEPTED;
    }
    public DesignQuoteStatus requestChanges(UUID actor,String reason){
        customer(actor);require(DesignQuoteStatus.SENT);reason(reason);return DesignQuoteStatus.CHANGES_REQUESTED;
    }
    public DesignQuoteStatus revise(){
        if(status!=DesignQuoteStatus.DRAFT && status!=DesignQuoteStatus.SENT && status!=DesignQuoteStatus.CHANGES_REQUESTED)
            throw new BusinessException(ErrorCode.DESIGN_QUOTE_CONFLICT);
        return DesignQuoteStatus.DRAFT;
    }
    public DesignQuoteStatus cancel(String reason){revise();reason(reason);return DesignQuoteStatus.CANCELLED;}
    public void require(DesignQuoteStatus expected){if(status!=expected)throw new BusinessException(ErrorCode.DESIGN_QUOTE_CONFLICT);}
    private void customer(UUID actor){if(!customerUserId.equals(actor))throw new BusinessException(ErrorCode.FORBIDDEN);}
    private static void reason(String reason){if(reason==null || reason.isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED);}
}
