package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-474 LOCK-REASON: 5 to 500 characters, trimmed, required. */
class StatusChangeReasonTest {

    private static void refused(String reason) {
        assertThatThrownBy(() -> StatusChangeReason.require(reason))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "     ", "abcd", "  abcd  ", "\t\n  \n"})
    @DisplayName("missing, blank or shorter than 5 characters after trimming is refused")
    void tooShort(String reason) {
        refused(reason);
    }

    @Test
    @DisplayName("exactly 5 and exactly 500 characters pass; 501 does not")
    void boundaries() {
        assertThat(StatusChangeReason.require("abcde")).isEqualTo("abcde");
        assertThat(StatusChangeReason.require("x".repeat(500))).hasSize(500);
        refused("x".repeat(501));
    }

    @Test
    @DisplayName("the reason is trimmed before it is counted and kept")
    void trimmed() {
        assertThat(StatusChangeReason.require("   Nghi ngờ lộ mật khẩu \n")).isEqualTo("Nghi ngờ lộ mật khẩu");
        refused("  " + "x".repeat(501) + "  ");
        assertThat(StatusChangeReason.require(" " + "x".repeat(500) + " ")).hasSize(500);
    }

    @Test
    @DisplayName("characters are counted as code points, like the database CHECK does")
    void codePoints() {
        // 5 emoji = 10 UTF-16 units but 5 characters; 500 of them = 1000 units, still allowed
        assertThat(StatusChangeReason.require("🔒".repeat(5))).isNotBlank();
        assertThat(StatusChangeReason.require("🔒".repeat(500))).isNotBlank();
        refused("🔒".repeat(4));
        refused("🔒".repeat(501));
    }

    @Test
    @DisplayName("Unicode spaces (NBSP, narrow NBSP, ideographic, zero-width) are trimmed too, not counted")
    void unicodeSpaces() {
        assertThat(StatusChangeReason.require("\u00a0\u00a0 abcde \u202f\u3000\u200b")).isEqualTo("abcde");
        refused("\u00a0".repeat(5));
        refused("\u00a0\u00a0abcd\u00a0\u00a0");
        refused("\u3000\u2003\u200b\u202f\u2007\u2009");
        // inner spaces are content
        assertThat(StatusChangeReason.require("a\u00a0b\u00a0c")).hasSize(5);
    }
}
