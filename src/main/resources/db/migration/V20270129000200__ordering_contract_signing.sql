-- =============================================================================
-- Contract e-signing machinery (contract-esign plan, BE-B). Module: order.
--
--   contract_signing_key   the self-issued TEST certificate and key the company signs contracts with when no PKCS#12 is
--                          configured (CONTRACT_SIGNING_KEYSTORE). One row per purpose, so two instances starting
--                          together create it once: the loser's INSERT ... ON CONFLICT does nothing and reads the
--                          winner's row. The private key is stored sealed (AES-256-GCM, a key derived from
--                          CONTRACT_SIGNING_SECRET), never in clear: a dump of this table is not a signing key.
--   contract_sign_otp      the one-time code a customer enters to sign a contract on the portal. Only the HMAC of the
--                          code is stored. One LIVE code per contract (a new one supersedes the old: the partial unique
--                          index); consumed and superseded rows stay as the record of how often codes were asked for,
--                          which the per-hour limit counts.
--
-- No grants: the machinery is used by the contract endpoints, which carry their own permissions.
-- =============================================================================

CREATE TABLE ordering.contract_signing_key (
    id                 UUID         NOT NULL,
    purpose            VARCHAR(20)  NOT NULL,
    certificate_der    BYTEA        NOT NULL,
    private_key_sealed TEXT         NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_contract_signing_key PRIMARY KEY (id),
    CONSTRAINT uk_contract_signing_key_purpose UNIQUE (purpose),
    CONSTRAINT ck_contract_signing_key_purpose CHECK (purpose IN ('TEST_CERT')),
    -- A sealed value is "v1:" and base64: a key stored in clear (PKCS#8 base64 starts "MII") is refused.
    CONSTRAINT ck_contract_signing_key_sealed CHECK (private_key_sealed LIKE 'v1:%')
);

CREATE TABLE ordering.contract_sign_otp (
    id               UUID         NOT NULL,
    contract_id      UUID         NOT NULL,
    revision         INT          NOT NULL,
    customer_user_id UUID         NOT NULL,
    email            VARCHAR(320) NOT NULL,
    code_hash        VARCHAR(64)  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    consumed_at      TIMESTAMPTZ,
    superseded_at    TIMESTAMPTZ,
    ip               VARCHAR(45),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_contract_sign_otp PRIMARY KEY (id),
    CONSTRAINT fk_contract_sign_otp_contract FOREIGN KEY (contract_id) REFERENCES ordering.sales_contract (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_contract_sign_otp_user FOREIGN KEY (customer_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_contract_sign_otp_revision CHECK (revision >= 1),
    CONSTRAINT ck_contract_sign_otp_attempts CHECK (attempts BETWEEN 0 AND 5),
    -- A hex HMAC-SHA256, never the six digits.
    CONSTRAINT ck_contract_sign_otp_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_contract_sign_otp_expiry CHECK (expires_at > created_at)
);

-- One live code per contract: not consumed, not superseded.
CREATE UNIQUE INDEX uk_contract_sign_otp_live ON ordering.contract_sign_otp (contract_id)
    WHERE consumed_at IS NULL AND superseded_at IS NULL;
CREATE INDEX ix_contract_sign_otp_contract_created ON ordering.contract_sign_otp (contract_id, created_at DESC);
