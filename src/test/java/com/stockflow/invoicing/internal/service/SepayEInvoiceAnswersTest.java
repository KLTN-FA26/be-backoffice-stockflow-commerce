package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HĐ-1: SePay's tracking {@code Failed} is either a refusal or "the result is not known", told apart only by its words.
 * Read narrowly - "not confirmed yet" with "result" - so a real refusal is never taken for "not known" (which has no
 * exit but the provider's answer); a rewording SePay makes is then a refusal whose recording waits
 * ({@code EInvoiceIssuance}), never a second invoice.
 */
class SepayEInvoiceAnswersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode failed(String message) {
        ObjectNode data = JSON.createObjectNode().put("status", "Failed");
        if (message != null) {
            data.put("message", message);
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("data", data);
        return body;
    }

    @Test
    @DisplayName("the documented words, with or without accents, in any case or spacing, are unconfirmed")
    void theDocumentedWordsInAnySpelling() {
        assertThat(SepayEInvoiceAnswers.unconfirmed(
                "Hệ thống chưa xác nhận được kết quả phát hành với nhà cung cấp hóa đơn.")).isTrue();
        assertThat(SepayEInvoiceAnswers.unconfirmed("he thong chua xac nhan duoc ket qua phat hanh")).isTrue();
        assertThat(SepayEInvoiceAnswers.unconfirmed("CHƯA   XÁC\tNHẬN kết quả")).isTrue();
        // Decomposed accents (NFD), as some clients send them.
        assertThat(SepayEInvoiceAnswers.unconfirmed(
                java.text.Normalizer.normalize("Chưa xác nhận được kết quả", java.text.Normalizer.Form.NFD)))
                .isTrue();
    }

    @Test
    @DisplayName("a rewording that still says the result is not confirmed yet, in Vietnamese or English, is unconfirmed")
    void rewordingsAndEnglish() {
        assertThat(SepayEInvoiceAnswers.unconfirmed("Chưa xác nhận kết quả phát hành")).isTrue();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Issuance result unconfirmed by the provider")).isTrue();
        assertThat(SepayEInvoiceAnswers.unconfirmed("The result is not yet confirmed")).isTrue();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Unconfirmed result from the invoicing provider")).isTrue();
    }

    @Test
    @DisplayName("a refusal that happens to share some of the words is a refusal, not 'not known'")
    void lookAlikeRefusalsAreRefusals() {
        assertThat(SepayEInvoiceAnswers.unconfirmed("Không xác nhận được mã số thuế người mua")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Mã số thuế không hợp lệ")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Chưa xác nhận đăng ký sử dụng hóa đơn")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Buyer tax code could not be confirmed")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Result: invalid unit price")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Chưa xác nhận được MST người mua theo kết quả tra cứu")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Result: buyer tax code could not be confirmed")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("The result could not be confirmed")).isFalse();
        assertThat(SepayEInvoiceAnswers.tracking(200, failed("Không xác nhận được mã số thuế người mua")))
                .isEqualTo(SepayEInvoiceAnswers.Tracking.FAILED);
    }

    @Test
    @DisplayName("no words at all cannot be told from 'not known': never taken for a refusal")
    void noWordsIsUnconfirmed() {
        assertThat(SepayEInvoiceAnswers.unconfirmed(null)).isTrue();
        assertThat(SepayEInvoiceAnswers.unconfirmed("   ")).isTrue();
        assertThat(SepayEInvoiceAnswers.tracking(200, failed(null))).isEqualTo(SepayEInvoiceAnswers.Tracking.UNCONFIRMED);
    }

    @Test
    @DisplayName("a message about the invoice's data is a refusal - and is recorded under the code whose re-issue waits")
    void aRefusalIsStillARefusal() {
        assertThat(SepayEInvoiceAnswers.unconfirmed("Thông tin người mua không hợp lệ: mã số thuế sai")).isFalse();
        assertThat(SepayEInvoiceAnswers.unconfirmed("Đơn giá không hợp lệ")).isFalse();
        assertThat(SepayEInvoiceAnswers.tracking(200, failed("Mã số thuế người mua không đúng")))
                .isEqualTo(SepayEInvoiceAnswers.Tracking.FAILED);
        assertThat(SepayEInvoiceAnswers.tracking(200, failed("chua xac nhan duoc ket qua")))
                .isEqualTo(SepayEInvoiceAnswers.Tracking.UNCONFIRMED);
    }
}
