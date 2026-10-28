-- =============================================================================
-- SCRUM-350/352: customer e-mail, sent through Resend (or SMTP locally), recorded in notification.delivery_log.
--
-- The customer messages (order placed, handed to the carrier, delivered, delivery failed, cancelled, part of the order
-- closed) reuse delivery_log the way purchase-order mail does: one row per attempt, operation_reference names the
-- message ("customer-mail:<template>:<source id>"), a SENT row carries the same value in external_reference, whose
-- unique index is what lets a redelivered event find the message already sent. What the existing columns could not
-- say is added here:
--
--   provider             which transport took it (RESEND, SMTP, RECORDING - the in-memory one of the test profile)
--   provider_message_id  the provider's id for a sent message (Resend's "id"); what a delivery webhook names
--   next_attempt_at      a failed attempt worth repeating is not repeated before this (Retry-After of a 429,
--                        exponential back-off otherwise); the retry job reads it, nothing else does
--   provider_status(_at) what the provider reported after accepting the message: DELIVERED, BOUNCED, COMPLAINED,
--                        DELIVERY_DELAYED, FAILED (Resend webhooks, signature-checked)
--   status SKIPPED       a message deliberately not sent: no customer, no usable address, customer mail switched
--                        off. Always terminal - a redelivered event does not try again.
--
-- Nothing here holds message text: subject and body are rendered when sent and never stored (they carry names and
-- amounts, and the portal invitation carries a secret). The recipient column already held addresses for supplier mail.
--
-- Owner of the notification schema: Tú (CLAUDE.md §7) - review before merge. No permission is added: the webhook is an
-- anonymous endpoint authenticated by its signature, and the log is not exposed by any new read endpoint.
-- =============================================================================

ALTER TABLE notification.delivery_log
    ADD COLUMN provider            VARCHAR(16),
    ADD COLUMN provider_message_id VARCHAR(100),
    ADD COLUMN next_attempt_at     TIMESTAMPTZ,
    ADD COLUMN provider_status     VARCHAR(24),
    ADD COLUMN provider_status_at  TIMESTAMPTZ;

ALTER TABLE notification.delivery_log DROP CONSTRAINT ck_delivery_log_status;
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_status
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'));

-- The same rules the entity states (DeliveryLogJpaEntity), so a manual UPDATE cannot break them either.
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_provider
    CHECK (provider IS NULL OR provider IN ('RESEND', 'SMTP', 'RECORDING'));
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_provider_status
    CHECK (provider_status IS NULL
        OR provider_status IN ('DELIVERY_DELAYED', 'DELIVERED', 'BOUNCED', 'COMPLAINED', 'FAILED'));
-- Only a message the provider accepted has its id, and only that message can be reported on afterwards.
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_provider_message
    CHECK (provider_message_id IS NULL OR (status = 'SENT' AND provider IS NOT NULL));
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_provider_report
    CHECK ((provider_status IS NULL) = (provider_status_at IS NULL)
        AND (provider_status IS NULL OR provider_message_id IS NOT NULL));
-- A skip is final; only a failure that will be tried again waits for a time.
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_skipped_terminal
    CHECK (status <> 'SKIPPED' OR terminal);
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_log_next_attempt
    CHECK (next_attempt_at IS NULL OR (status = 'FAILED' AND NOT terminal));

-- A webhook finds its message by the provider's id; one provider never reuses one.
CREATE UNIQUE INDEX uk_delivery_log_provider_message
    ON notification.delivery_log (provider, provider_message_id) WHERE provider_message_id IS NOT NULL;

COMMENT ON COLUMN notification.delivery_log.provider_message_id IS
    'The provider''s id of a sent message (Resend email id); delivery webhooks are matched on it.';
COMMENT ON COLUMN notification.delivery_log.next_attempt_at IS
    'A failed attempt that will be retried: not before this time (Retry-After or back-off).';
COMMENT ON COLUMN notification.delivery_log.provider_status IS
    'Last outcome the provider reported after accepting the message (webhook): DELIVERED, BOUNCED, COMPLAINED, DELIVERY_DELAYED, FAILED.';
