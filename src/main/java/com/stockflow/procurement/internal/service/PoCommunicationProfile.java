package com.stockflow.procurement.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-owned buyer identity; copied into the durable PO delivery snapshot at send time. */
@Component
class PoCommunicationProfile {
    private final PurchaseOrderSent.Buyer buyer;

    PoCommunicationProfile(
            @Value("${stockflow.procurement.buyer.company-name:}") String name,
            @Value("${stockflow.procurement.buyer.company-address:}") String address,
            @Value("${stockflow.procurement.buyer.contact-name:}") String contact,
            @Value("${stockflow.procurement.buyer.phone:}") String phone,
            @Value("${stockflow.procurement.buyer.email:}") String email,
            @Value("${stockflow.procurement.buyer.receiving-address:}") String receivingAddress) {
        buyer = new PurchaseOrderSent.Buyer(name.trim(), address.trim(), contact.trim(),
                phone.trim(), email.trim(), receivingAddress.trim());
    }

    PurchaseOrderSent.Buyer requireBuyer() {
        if (java.util.stream.Stream.of(buyer.companyName(), buyer.companyAddress(), buyer.contactName(),
                buyer.phone(), buyer.email(), buyer.receivingAddress()).anyMatch(String::isBlank)
                || !buyer.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new BusinessException(ErrorCode.PO_COMMUNICATION_NOT_CONFIGURED);
        }
        return buyer;
    }
}
