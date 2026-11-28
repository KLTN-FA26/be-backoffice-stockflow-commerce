package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCodesTest {

    private static final AnswerNumbers.Grounds SOURCES = AnswerNumbers.Grounds.of(List.of(
            "{\"sku\":\"EVAL-CUP-12OZ\",\"bin\":\"HCM-A01-1-B\",\"orderNumber\":\"SO-20991231-900101\","
                    + "\"lot\":\"eval-lot-2401\"}",
            "Còn bao nhiêu LPN-20261105-0001?"));

    @Test
    @DisplayName("a code the model made up, or mis-copied by one digit, is flagged; one a source writes, in any case, "
            + "is not")
    void madeUpAndMisCopiedCodes() {
        assertThat(AnswerCodes.unverified("Có 2 mã: CUP-A và LID-B.", SOURCES)).containsExactly("CUP-A", "LID-B");
        assertThat(AnswerCodes.unverified("Đơn SO-20991231-900077.", SOURCES)).containsExactly("SO-20991231-900077");
        assertThat(AnswerCodes.unverified("EVAL-CUP-12OZ ở HCM-A01-1-B, lô EVAL-LOT-2401, thùng LPN-20261105-0001, "
                + "đơn SO-20991231-900101.", SOURCES)).isEmpty();
    }

    @Test
    @DisplayName("the leading part of a source's code is grounded: the rack a bin is on")
    void leadingParts() {
        assertThat(AnswerCodes.unverified("Kệ HCM-A01 và HCM-A01-1 còn chỗ.", SOURCES)).isEmpty();
        assertThat(AnswerCodes.unverified("Kệ HCM-A02.", SOURCES)).containsExactly("HCM-A02");
    }

    @Test
    @DisplayName("not codes: lower-case parts, words with a hyphen, statuses with underscores, dates, ISO times, and "
            + "rule or flow ids, which the citation check covers")
    void notCodes() {
        assertThat(AnswerCodes.written("Thiếu quyền reporting-reports:READ; trạng thái ON_HOLD; ngày 2026-11-05; "
                + "lúc 2026-11-05T09; theo BR-PAY-008 và F-PO, X-SHORT; e-mail; Wi-Fi; mã CUP-a1.")).isEmpty();
    }

    @Test
    @DisplayName("a placeholder of the prompt's examples is flagged; a date format is no code; any dash is a dash; a "
            + "refused call's line grounds no code")
    void placeholdersFormatsDashesAndErrors() {
        assertThat(AnswerCodes.unverified("Có «n» mã: «SKU 1» và «SKU 2».", SOURCES))
                .containsExactly("«n»", "«SKU 1»", "«SKU 2»");
        assertThat(AnswerCodes.written("Ngày theo dạng YYYY-MM-DD hoặc DD-MM-YYYY.")).isEmpty();
        AnswerNumbers.Grounds typed = AnswerNumbers.Grounds.of(List.of("Đơn SO\u201320991231\u2013900101 đâu rồi?"));
        assertThat(AnswerCodes.unverified("Đơn SO-20991231-900101 đang giao.", typed)).isEmpty();
        AnswerNumbers.Grounds refused = AnswerNumbers.Grounds.builder().source("Mã nào sắp hết?")
                .error("NOT_FOUND: SKU CUP-A not found").build();
        assertThat(AnswerCodes.unverified("Mã CUP-A sắp hết.", refused)).containsExactly("CUP-A");
    }

    @Test
    @DisplayName("short codes with single letters are codes: a lot and a bin written from memory are caught")
    void shortCodes() {
        assertThat(AnswerCodes.unverified("Thiếu 8 hộp, lô L-7, vị trí B02-3.", SOURCES)).containsExactly("L-7", "B02-3");
    }

    @Test
    @DisplayName("the system prompt's examples hold no code that looks real: a model copying one writes a placeholder, "
            + "which nobody mistakes for data")
    void thePromptHoldsNoRealLookingCode() {
        assertThat(AnswerCodes.written(new SystemPrompt().text())).isEmpty();
    }
}
