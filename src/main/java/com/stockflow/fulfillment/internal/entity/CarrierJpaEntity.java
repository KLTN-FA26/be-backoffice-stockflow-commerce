package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A declared carrier (table {@code fulfillment.carrier}; kltn-docs 09 §3: "chọn từ danh sách hãng đã khai báo: tên,
 * liên hệ"). A small catalogue the warehouse manager keeps; an INACTIVE carrier stays on the shipments and loads it
 * already has, and takes no new load.
 *
 * <p>The connection settings (3-1; kltn-docs 09 §4.1 step 2, BR-21) sit on the same row. The secrets are stored
 * sealed by {@code SecretCipher} ({@code *_sealed}, {@code v1:...}), never in clear, and are never read back to a
 * client: the row answers only whether each is set. Which settings a kind of connection needs is
 * {@code CarrierConnection}'s rule and {@code ck_carrier_connection_*}'s.</p>
 */
@Entity
@Table(name = "carrier", schema = "fulfillment")
public class CarrierJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32) private String code;
    @Column(name = "name", nullable = false, length = 120) private String name;
    @Column(name = "contact_name", length = 120) private String contactName;
    @Column(name = "contact_phone", length = 32) private String contactPhone;
    @Column(name = "status", nullable = false, length = 16) private String status;
    @Column(name = "connection_type", nullable = false, length = 16) private String connectionType = "MANUAL";
    @Column(name = "webhook_enabled", nullable = false) private boolean webhookEnabled;
    @Column(name = "webhook_secret_sealed") private String webhookSecretSealed;
    @Column(name = "webhook_auth_header", length = 64) private String webhookAuthHeader;
    @Column(name = "webhook_auth_value_sealed") private String webhookAuthValueSealed;
    @Column(name = "webhook_auth_value_next_sealed") private String webhookAuthValueNextSealed;
    @Column(name = "api_environment", length = 16) private String apiEnvironment;
    @Column(name = "api_account", length = 64) private String apiAccount;
    @Column(name = "api_key_sealed") private String apiKeySealed;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_reason_map", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> failureReasonMap = new LinkedHashMap<>();

    protected CarrierJpaEntity() {
    }

    public CarrierJpaEntity(UUID id, String code) {
        super(id);
        this.code = code;
        this.status = "ACTIVE";
    }

    public void apply(String code, String name, String contactName, String contactPhone, String status) {
        this.code = code;
        this.name = name;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.status = status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /**
     * Replaces the connection settings whole. The caller has already sealed the secrets and checked the settings
     * against {@code CarrierConnection}; a setting the connection does not use is passed as null and so cleared.
     *
     * @param webhookAuthValueNextSealed the second webhook credential of a rotation under way, kept only while the
     *                                   first stays what it was
     */
    @SuppressWarnings("java:S107")
    public void connect(String connectionType, boolean webhookEnabled, String webhookSecretSealed,
                        String webhookAuthHeader, String webhookAuthValueSealed, String webhookAuthValueNextSealed,
                        String apiEnvironment, String apiAccount, String apiKeySealed,
                        Map<String, String> failureReasonMap) {
        this.connectionType = connectionType;
        this.webhookEnabled = webhookEnabled;
        this.webhookSecretSealed = webhookSecretSealed;
        this.webhookAuthHeader = webhookAuthHeader;
        this.webhookAuthValueSealed = webhookAuthValueSealed;
        this.webhookAuthValueNextSealed = webhookAuthValueSealed == null ? null : webhookAuthValueNextSealed;
        this.apiEnvironment = apiEnvironment;
        this.apiAccount = apiAccount;
        this.apiKeySealed = apiKeySealed;
        this.failureReasonMap = new LinkedHashMap<>(failureReasonMap);
    }

    /**
     * The two webhook credentials of an API_BOOKING carrier while one replaces the other (WF-05 fix plan item 6): both
     * hold until the new one is promoted to be the only one, or dropped.
     *
     * @param currentSealed the credential the header is checked against first; never null
     * @param nextSealed    a second credential that holds as well; null when no rotation is under way
     * @throws IllegalStateException for a carrier whose webhooks carry no header credential
     */
    public void webhookCredentials(String currentSealed, String nextSealed) {
        if (!"API_BOOKING".equals(connectionType) || webhookAuthValueSealed == null || currentSealed == null) {
            throw new IllegalStateException("Carrier " + code + " has no webhook header credential to rotate");
        }
        this.webhookAuthValueSealed = currentSealed;
        this.webhookAuthValueNextSealed = nextSealed;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getStatus() { return status; }
    public String getConnectionType() { return connectionType; }
    public boolean isWebhookEnabled() { return webhookEnabled; }
    public String getWebhookSecretSealed() { return webhookSecretSealed; }
    public String getWebhookAuthHeader() { return webhookAuthHeader; }
    public String getWebhookAuthValueSealed() { return webhookAuthValueSealed; }
    public String getWebhookAuthValueNextSealed() { return webhookAuthValueNextSealed; }
    public String getApiEnvironment() { return apiEnvironment; }
    public String getApiAccount() { return apiAccount; }
    public String getApiKeySealed() { return apiKeySealed; }
    public Map<String, String> getFailureReasonMap() { return failureReasonMap; }
}
