package com.stockflow.order.internal.domain;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.common.idempotency.IdempotencyKeys;
import java.nio.charset.StandardCharsets;

/** Length-prefixed, normalized command values; address IDs preserve original replay intent even
 * if the address book or its defaults change after the order was committed. */
public final class CheckoutFingerprint {
    private CheckoutFingerprint(){}
    public static String of(PlaceOrderCommand c){
        var data=new StringBuilder();
        append(data,c.customerId());append(data,c.shippingAddressId());append(data,c.billingAddressId());append(data,c.snapshotCustomer());
        for(var l:c.lines()){
            append(data,l.sku().code());append(data,l.quantity());append(data,l.unitPrice().amount().stripTrailingZeros().toPlainString());
            append(data,l.unitPrice().currency().getCurrencyCode());append(data,l.designSnapshotId());append(data,l.quoteId());
        }
        return IdempotencyKeys.fingerprint("CHECKOUT","v1",data.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void append(StringBuilder b,Object v){String s=v==null?"":v.toString();b.append(s.length()).append(':').append(s);}
}
