package com.stockflow.notification.internal.domain;

import java.util.Objects;

/**
 * A transport accepted a message. {@code messageId} is the provider's own id (Resend's {@code id}), null when the
 * transport has none worth keeping (SMTP): it is what a later delivery report names.
 */
public record EmailReceipt(EmailProvider provider, String messageId) {

    public EmailReceipt {
        Objects.requireNonNull(provider, "provider");
        messageId = messageId == null || messageId.isBlank() ? null : messageId;
    }
}
