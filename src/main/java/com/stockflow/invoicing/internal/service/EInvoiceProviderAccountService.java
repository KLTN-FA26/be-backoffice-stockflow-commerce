package com.stockflow.invoicing.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The administrator's side of choosing an e-invoice provider (plan §1.3: "chuyển sang dùng thật chỉ cần đổi cấu hình
 * nhà cung cấp"): store a real provider's account, name the series it numbers under, put it in use - or go back to the
 * sandbox.
 *
 * <p>The account's password is sealed with the application's secrets key ({@link SecretCipher},
 * {@code STOCKFLOW_SECRETS_KEY}) exactly as a carrier's API key is, bound to the provider's row so a sealed value copied
 * onto another row does not open there. It is written and never read back out: an answer says only whether one is
 * stored (kltn-docs 21 BR-10).</p>
 *
 * <p>Switching provider touches no invoice. One already handed to a provider carries that provider's code and is asked
 * about there until it is settled; only invoices issued after the switch go to the new one.</p>
 */
@Service
public class EInvoiceProviderAccountService {

    private final ProviderAccounts accounts;
    private final EInvoiceProviders providers;
    private final SecretCipher cipher;

    EInvoiceProviderAccountService(ProviderAccounts accounts, EInvoiceProviders providers, SecretCipher cipher) {
        this.accounts = accounts;
        this.providers = providers;
        this.cipher = cipher;
    }

    /** What a provider's password is sealed under: the provider's row. Opening it takes the same. */
    static String secretContext(UUID providerConfigId) {
        return "einvoice-provider:" + providerConfigId + ":secret";
    }

    /** Every provider that can be configured, the one in use first. The sealed secret is in none of them for a caller to show. */
    @Transactional(readOnly = true)
    public List<ProviderAccounts.Account> providers() {
        return accounts.all();
    }

    /**
     * Stores the account a provider is called with.
     *
     * @param environment {@code SANDBOX} (the provider's test system) or {@code PRODUCTION}
     * @param secret      the account's password; null keeps the one stored. Never logged, never returned
     * @throws BusinessException 404 {@code NOT_FOUND} for an unknown provider; 400 {@code VALIDATION_FAILED} for the
     *         sandbox, which has no account, a provider whose account is the deployment's
     *         ({@link EInvoiceProvider#accountInDeployment()}), or an environment that is neither; 503
     *         {@code EINVOICE_PROVIDER_UNAVAILABLE} when this build has no implementation of it; 503
     *         {@code SECRETS_UNAVAILABLE} when no secrets key is configured; 409 {@code OPTIMISTIC_LOCK}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice-settings", resourceId = "#provider")
    public ProviderAccounts.Account storeAccount(String provider, String environment, String username, String secret,
                                                 long expectedVersion) {
        ProviderAccounts.Account account = require(provider);
        if (SandboxProvider.CODE.equals(provider)) {
            throw invalid("provider", "The sandbox has no account");
        }
        if (providers.require(provider).accountInDeployment()) {
            throw invalid("provider", "This provider's account is set in the deployment's environment, not here");
        }
        if (!"SANDBOX".equals(environment) && !"PRODUCTION".equals(environment)) {
            throw invalid("environment", "SANDBOX or PRODUCTION");
        }
        if (username == null || username.isBlank()) {
            throw invalid("username", "The account's user name is required");
        }
        if (secret != null && secret.isBlank()) {
            throw invalid("secret", "A password is not blank; leave it out to keep the one stored");
        }
        String sealed = secret == null ? null : cipher.seal(secret, secretContext(account.id()));
        accounts.store(provider, environment, username.strip(), sealed, expectedVersion);
        return require(provider);
    }

    /**
     * Makes a series the one invoices of its year are numbered under at a provider.
     *
     * <p>For a provider that lists its account's templates ({@link EInvoiceProvider#checkSeries}: SePay), the template
     * code and the symbol are checked against that list now (HĐ-1): a series it does not offer would have every invoice
     * sent under it refused, which used to surface only as invoices that never left SUBMITTING. A provider that is not
     * configured yet, or lists nothing, is not asked; its invoices are then refused under a code of their own if the
     * series is wrong.</p>
     *
     * @param providerTemplateCode the provider's own code of the template; required for a real provider (every
     *                             SInvoice and SePay call names the template by it), absent for the sandbox
     * @throws BusinessException 404 {@code NOT_FOUND}; 400 {@code VALIDATION_FAILED} when the symbol is not of the year
     *         or the provider's template code is missing or superfluous; 409 {@code EINVOICE_SERIES_NOT_AT_PROVIDER}
     *         when the provider does not offer the template and symbol on the account; 503
     *         {@code EINVOICE_PROVIDER_UNAVAILABLE} when it does not answer the question
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice-settings", resourceId = "#provider")
    public void useSeries(String provider, String templateCode, String symbol, int year, String providerTemplateCode) {
        require(provider);
        // ck_einvoice_series_template says the same: the seller issues VAT invoices only.
        if (!InvoiceSeries.VAT_TEMPLATE.equals(templateCode)) {
            throw invalid("templateCode", "Only template 1, a VAT invoice, is issued");
        }
        // ck_einvoice_series_year says the same: a symbol names its year.
        if (symbol == null || symbol.length() != 6
                || !symbol.substring(1, 3).equals("%02d".formatted(year % 100))) {
            throw invalid("symbol", "The symbol's two digits are the year's last two");
        }
        boolean sandbox = SandboxProvider.CODE.equals(provider);
        String ownTemplateCode = providerTemplateCode == null || providerTemplateCode.isBlank() ? null
                : providerTemplateCode.strip();
        if (sandbox != (ownTemplateCode == null)) {
            throw invalid("providerTemplateCode", sandbox ? "The sandbox has no template code of its own"
                    : "The provider's own template code is required");
        }
        if (!sandbox) {
            EInvoiceProvider.SeriesCheck offered;
            try {
                offered = providers.find(provider).map(port -> port.checkSeries(ownTemplateCode, symbol))
                        .orElse(EInvoiceProvider.SeriesCheck.NOT_CHECKED);
            } catch (EInvoiceProvider.ProviderUnavailableException unavailable) {
                throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE,
                        "%s did not say whether it offers template %s, symbol %s".formatted(provider, ownTemplateCode,
                                symbol));
            }
            if (offered == EInvoiceProvider.SeriesCheck.NOT_OFFERED) {
                throw new BusinessException(ErrorCode.EINVOICE_SERIES_NOT_AT_PROVIDER, ("%s does not offer template %s, "
                        + "symbol %s on the account; use one of the account's templates").formatted(provider,
                        ownTemplateCode, symbol));
            }
        }
        accounts.useSeries(new InvoiceSeries(provider, templateCode, symbol, year), ownTemplateCode);
    }

    /**
     * Puts a provider in use; the one that was steps back.
     *
     * @throws BusinessException 404 {@code NOT_FOUND}; 503 {@code EINVOICE_PROVIDER_UNAVAILABLE} when this build has no
     *         implementation of it; 409 {@code EINVOICE_PROVIDER_ACCOUNT_INCOMPLETE} for a real provider without a
     *         stored account ({@code ck_einvoice_provider_config_account} says the same), or, for one whose account is
     *         the deployment's, without one configured there; 409 {@code OPTIMISTIC_LOCK}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice-settings", resourceId = "#provider")
    public ProviderAccounts.Account activate(String provider, long expectedVersion) {
        ProviderAccounts.Account account = require(provider);
        EInvoiceProvider implementation = providers.require(provider);
        boolean hasAccount = implementation.accountInDeployment() ? implementation.deploymentAccountReady()
                : account.complete();
        if (!SandboxProvider.CODE.equals(provider) && !hasAccount) {
            throw new BusinessException(ErrorCode.EINVOICE_PROVIDER_ACCOUNT_INCOMPLETE,
                    "Provider %s has no account stored".formatted(provider));
        }
        if (!account.active()) {
            accounts.activate(provider, expectedVersion);
        }
        return require(provider);
    }

    private ProviderAccounts.Account require(String provider) {
        return accounts.find(provider).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "No e-invoice provider is called that"));
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, List.of(FieldError.of(field, message)));
    }
}
