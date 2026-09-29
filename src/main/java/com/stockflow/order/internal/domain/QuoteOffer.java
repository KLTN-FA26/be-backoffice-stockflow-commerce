package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A final per-unit VND selling price, not an editable client suggestion or a tax calculator. */
public record QuoteOffer(int revision,int quantity,Money unitPrice,Instant validUntil,String terms,Instant createdAt,UUID createdBy) {
    public static QuoteOffer create(int revision,int quantity,BigDecimal amount,Instant validUntil,String terms,Instant now,UUID actor) {
        if(validUntil!=null)validUntil=validUntil.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        now=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(revision<1 || quantity<1 || amount==null || amount.signum()<=0 || amount.stripTrailingZeros().scale()>0
                || amount.multiply(BigDecimal.valueOf(quantity)).compareTo(new BigDecimal("999999999999999"))>0
                || validUntil==null || !validUntil.isAfter(now) || terms==null || terms.isBlank() || terms.length()>2000 || actor==null)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return new QuoteOffer(revision,quantity,new Money(amount,Money.VND),validUntil,terms.trim(),now,actor);
    }
    public void requireValid(Instant now){if(!now.isBefore(validUntil))throw new BusinessException(ErrorCode.DESIGN_QUOTE_EXPIRED);}
}
