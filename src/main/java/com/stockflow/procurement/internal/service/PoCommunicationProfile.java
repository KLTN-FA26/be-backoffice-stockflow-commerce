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
            @Value("${PO_BUYER_COMPANY_NAME:}") String name,
            @Value("${PO_BUYER_COMPANY_ADDRESS:}") String address,
            @Value("${PO_BUYER_CONTACT_NAME:}") String contact,
            @Value("${PO_BUYER_PHONE:}") String phone,
            @Value("${PO_BUYER_EMAIL:}") String email,
            @Value("${PO_RECEIVING_ADDRESS:}") String receivingAddress) {
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
