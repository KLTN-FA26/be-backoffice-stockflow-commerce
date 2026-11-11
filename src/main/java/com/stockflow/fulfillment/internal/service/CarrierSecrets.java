package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.fulfillment.internal.domain.CarrierApiEnvironment;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Opens an API_BOOKING carrier's sealed key for a call (3-4), under the context {@link CarrierService} sealed it with.
 * The clear key lives in the {@link CarrierBookingGateway.Caller} for the length of the call and is never logged.
 */
@Component
class CarrierSecrets {

    private final SecretCipher cipher;

    CarrierSecrets(SecretCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * @throws BusinessException 409 {@code CARRIER_NOT_API_BOOKING} for a carrier with no API settings; 503
     *         {@code SECRETS_UNAVAILABLE} without a secrets key
     * @throws IllegalStateException when the key does not open (the secrets key changed since it was sealed)
     */
    CarrierBookingGateway.Caller caller(CarrierJpaEntity carrier, UUID bookingId) {
        if (carrier.getApiKeySealed() == null || carrier.getApiEnvironment() == null
                || carrier.getApiAccount() == null) {
            throw new BusinessException(ErrorCode.CARRIER_NOT_API_BOOKING,
                    "Carrier %s has no API settings".formatted(carrier.getCode()));
        }
        String key = cipher.open(carrier.getApiKeySealed(), CarrierService.context(carrier, "api-key"));
        return new CarrierBookingGateway.Caller(carrier.getId(), bookingId,
                CarrierApiEnvironment.valueOf(carrier.getApiEnvironment()), carrier.getApiAccount(), key);
    }
}
