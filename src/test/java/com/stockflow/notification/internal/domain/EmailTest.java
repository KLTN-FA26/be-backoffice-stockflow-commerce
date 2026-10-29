package com.stockflow.notification.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The value every transport receives: one-line headers, a text part always, and nothing printed by toString. */
class EmailTest {

    private static Email email(String subject, String key, String category) {
        return new Email("StockFlow <no-reply@stockflow.vn>", List.of("buyer@cup-maker.vn"), null, subject,
                "Plain text", "<p>Html</p>", key, category);
    }

    @Test
    @DisplayName("a line break in a subject is how a header is injected: the subject is always one line")
    void subjectIsOneLine() {
        Email email = email("Order SO-1\r\nBcc: attacker@evil.test", null, null);
        assertThat(email.subject()).isEqualTo("Order SO-1  Bcc: attacker@evil.test").doesNotContain("\r", "\n");
    }

    @Test
    @DisplayName("a recipient with a control character, no recipient, no subject or no text is refused")
    void malformedMessagesAreRefused() {
        assertThatThrownBy(() -> new Email("a@stockflow.vn", List.of("x@y.vn\nBcc: z@w.vn"), null, "s", "t", null,
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Email("a@stockflow.vn", List.of(), null, "s", "t", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> email(" \n ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Email("a@stockflow.vn", List.of("x@y.vn"), null, "s", " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an idempotency key is printable ASCII of at most 256 characters; a category is a provider tag")
    void keyAndCategoryShapes() {
        assertThat(email("s", "customer-mail:order.placed:0190-abc", "order_placed").idempotencyKey())
                .isEqualTo("customer-mail:order.placed:0190-abc");
        assertThatThrownBy(() -> email("s", "key with space", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> email("s", "k".repeat(257), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> email("s", null, "order.placed")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString names sizes only: no address, subject or body ever reaches a log through it")
    void toStringLeaksNothing() {
        String printed = email("Đơn hàng SO-1 của Công ty ABC", "k", "order_placed").toString();
        assertThat(printed).doesNotContain("buyer@cup-maker.vn", "SO-1", "Công ty", "Plain text", "Html", "stockflow.vn");
        assertThat(printed).contains("1 recipient(s)", "html=true");
    }
}
