package com.stockflow.contracts;

import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>invoicing</b> module when a draft e-invoice is drawn up, or its buyer refreshed, and the
 * customer's profile still lacks what the invoice cannot be issued without (HĐ-8; kltn-docs 21 BR-06, §4.2 step 4): the
 * name, tax code or address, or the e-mail that receives the invoice. Issuing is refused until Sales records it on the
 * customer and the accountant refreshes the buyer, so Sales - who keep the customer's profile - are told what is
 * missing; otherwise the invoice waits on the accountant's list while its deadline runs.
 *
 * <p>Codes and ids only. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param code       the invoice's internal code
 * @param customerId the buyer whose profile is incomplete
 * @param missing    what is missing, at least one
 */
public record EInvoiceBuyerIncomplete(UUID einvoiceId, String code, UUID orderId, String orderNumber, UUID customerId,
                                      List<Missing> missing) {

    public enum Missing { NAME, TAX_CODE, ADDRESS, EMAIL }
}
