package com.stockflow.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CodeQL java/log-injection: a value from outside cannot start a log line of its own. */
class LogSafeTest {

    @Test
    @DisplayName("line breaks and control characters become _, ordinary text is untouched")
    void oneHarmlessLine() {
        assertThat(LogSafe.text("/api/v1/orders?page=2")).isEqualTo("/api/v1/orders?page=2");
        assertThat(LogSafe.text("Nguyễn Văn A")).isEqualTo("Nguyễn Văn A");
        assertThat(LogSafe.text("key\r\n2026-10-11 INFO forged")).isEqualTo("key__2026-10-11 INFO forged");
        assertThat(LogSafe.text("a\tb\u0000c\u001bd e")).isEqualTo("a_b_c_d_e");
        assertThat(LogSafe.text(null)).isNull();
        assertThat(LogSafe.text(42)).isEqualTo("42");
    }

    @Test
    @DisplayName("a value longer than the limit is cut, so one request cannot flood a line")
    void cut() {
        String safe = LogSafe.text("x".repeat(5_000));
        assertThat(safe).hasSize(LogSafe.MAX_LENGTH + 1).endsWith("…");
    }
}
