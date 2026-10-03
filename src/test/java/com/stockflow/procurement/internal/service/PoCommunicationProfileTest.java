package com.stockflow.procurement.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PoCommunicationProfileTest {
    @Test void missingBuyerInformationIsAnExplicitConfigurationConflict() {
        assertThatThrownBy(() -> new PoCommunicationProfile(" ", "Address", "Contact", "0901234567",
                "buyer@example.com", "Warehouse").requireBuyer())
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PO_COMMUNICATION_NOT_CONFIGURED);
    }

    @Test void realBuyerDetailsAreTrimmedAndSnapshotted() {
        var buyer = new PoCommunicationProfile(" Buyer ", " Address ", " Contact ", "0901234567",
                " buyer@example.com ", " Warehouse ").requireBuyer();
        assertThat(buyer.companyName()).isEqualTo("Buyer");
        assertThat(buyer.receivingAddress()).isEqualTo("Warehouse");
        assertThat(buyer.email()).isEqualTo("buyer@example.com");
    }
}
