package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.fulfillment.internal.domain.CarrierApiEnvironment;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Opens an API_BOOKING carrier's sealed key for a call (3-4), under the context {@link CarrierService} sealed it with.
 * The clear key lives in the {@link CarrierBookingGateway.Caller} for the length of the call and is never logged. In
 * fake mode ({@code stockflow.fulfillment.ahamove.mode=fake}) nothing is opened.
 */
@Component
class CarrierSecrets {

    /** What the fake Ahamove is "called" with: it checks nothing, so no secret is opened (and none is needed). */
    static final String FAKE_KEY = "fake-ahamove-no-key";

    private final SecretCipher cipher;
    private final boolean fake;

    CarrierSecrets(SecretCipher cipher) {
        this(cipher, null);
    }

    @Autowired
    CarrierSecrets(SecretCipher cipher, AhamoveProperties properties) {
        this.cipher = cipher;
        this.fake = properties != null && properties.fake();
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
        // Fake mode: the fake Ahamove takes no key, so the sealed one is not opened and no secrets key is needed.
        String key = fake ? FAKE_KEY
                : cipher.open(carrier.getApiKeySealed(), CarrierService.context(carrier, "api-key"));
        return new CarrierBookingGateway.Caller(carrier.getId(), bookingId,
                CarrierApiEnvironment.valueOf(carrier.getApiEnvironment()), carrier.getApiAccount(), key);
    }
}
