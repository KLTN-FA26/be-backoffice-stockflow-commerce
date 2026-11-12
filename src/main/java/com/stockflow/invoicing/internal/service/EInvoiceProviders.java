package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The providers this application can issue through, by code. Which one is used is configuration
 * ({@code invoicing.einvoice_provider_config}); this is only how the code named there becomes the implementation. A new
 * provider is a new {@link EInvoiceProvider} bean - nothing here, and nothing in an invoice's rules, changes.
 */
@Component
class EInvoiceProviders {

    private final Map<String, EInvoiceProvider> byCode = new HashMap<>();

    EInvoiceProviders(List<EInvoiceProvider> providers) {
        for (EInvoiceProvider provider : providers) {
            if (byCode.put(provider.code(), provider) != null) {
                throw new IllegalStateException("Two e-invoice providers are called " + provider.code());
            }
        }
    }

    /**
     * @throws BusinessException 503 {@code EINVOICE_PROVIDER_UNAVAILABLE} when the provider configured has no
     *         implementation in this build (Viettel SInvoice before package E3)
     */
    EInvoiceProvider require(String code) {
        EInvoiceProvider provider = byCode.get(code);
        if (provider == null) {
            throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE,
                    "E-invoice provider %s is configured but not available in this application".formatted(code));
        }
        return provider;
    }
}
