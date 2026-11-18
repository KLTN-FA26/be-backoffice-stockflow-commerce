package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The providers this application can issue through, by code. Which one is used is configuration
 * ({@code invoicing.einvoice_provider_config}); this is only how the code named there becomes the implementation. A new
 * provider is a new {@link EInvoiceProvider} bean - nothing here, and nothing in an invoice's rules, changes.
 */
@Component
class EInvoiceProviders {

    private final Map<String, EInvoiceProvider> byCode = new HashMap<>();
    private final ProviderAccounts accounts;

    EInvoiceProviders(List<EInvoiceProvider> providers, ProviderAccounts accounts) {
        this.accounts = accounts;
        for (EInvoiceProvider provider : providers) {
            if (byCode.put(provider.code(), provider) != null) {
                throw new IllegalStateException("Two e-invoice providers are called " + provider.code());
            }
        }
    }

    /**
     * @throws BusinessException 503 {@code EINVOICE_PROVIDER_UNAVAILABLE} when the provider configured has no
     *         implementation in this build
     */
    EInvoiceProvider require(String code) {
        EInvoiceProvider provider = byCode.get(code);
        if (provider == null) {
            throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE,
                    "E-invoice provider %s is configured but not available in this application".formatted(code));
        }
        return provider;
    }

    /** The provider called {@code code}; empty when this build has no implementation of it. */
    Optional<EInvoiceProvider> find(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code));
    }

    /**
     * Whether invoices can still be sent through {@code code} although it may no longer be the provider in use (HĐ-5):
     * this build implements it and it has an account to call it with - the deployment's for a provider whose account
     * is configuration, a stored one for the others; the sandbox needs none. Which series it numbers under is the
     * caller's to check.
     */
    boolean usable(String code) {
        EInvoiceProvider provider = byCode.get(code);
        if (provider == null) {
            return false;
        }
        if (SandboxProvider.CODE.equals(code)) {
            return true;
        }
        return provider.accountInDeployment() ? provider.deploymentAccountReady()
                : accounts.find(code).map(ProviderAccounts.Account::complete).orElse(false);
    }
}
