package com.stockflow.payment.api;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The VietQR payload (kltn-docs 16 BR-06), moved from chat to {@code payment :: api} for R5-9. The whole payload is
 * pinned byte for byte, as computed independently of this class: the chat's transfer card must draw exactly the code
 * it drew before the move, and the order page's transfer instructions the same one.
 */
class VietQrTest {

    private static final String PINNED = "00020101021238570010A00000072701270006970436011300710008889990208QRIBFTTA"
            + "5303704540715000005802VN62150811HD-01 Dot 16304260A";

    @Test
    @DisplayName("BR-06: the VietQR payload names the bank, account, amount and content, with its CRC")
    void payload() {
        assertThat(VietQr.crc16("123456789")).isEqualTo("29B1");
        String qr = VietQr.payload("970436", "0071000888999", new BigDecimal("1500000"), "HĐ-01 Đợt 1");
        assertThat(qr).isEqualTo(PINNED);
        assertThat(qr.substring(qr.length() - 4)).isEqualTo(VietQr.crc16(qr.substring(0, qr.length() - 4)));
        assertThatThrownBy(() -> VietQr.payload("97", "1", BigDecimal.ONE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R5-9: an installment's code is the payload of what it still owes, and none when nothing is to be paid")
    void forInstallment() {
        CompanyBankAccount bank = new CompanyBankAccount("Vietcombank", "0071000888999", "CONG TY", "970436");
        BigDecimal owed = new BigDecimal("1500000");

        assertThat(VietQr.forInstallment(bank, owed, "VND", "DUE", "HĐ-01 Đợt 1")).isEqualTo(PINNED);
        assertThat(VietQr.forInstallment(bank, owed, "VND", "PARTIALLY_PAID", "HĐ-01 Đợt 1")).isEqualTo(PINNED);
        assertThat(VietQr.forInstallment(bank, owed, "VND", "PAID", "x")).isNull();
        assertThat(VietQr.forInstallment(bank, owed, "VND", "CANCELLED", "x")).isNull();
        assertThat(VietQr.forInstallment(bank, BigDecimal.ZERO, "VND", "DUE", "x")).isNull();
        assertThat(VietQr.forInstallment(bank, owed, "USD", "DUE", "x")).isNull();
        assertThat(VietQr.forInstallment(new CompanyBankAccount("Vietcombank", "0071000888999", "CONG TY"), owed,
                "VND", "DUE", "x")).as("no BIN configured: shown without a code").isNull();
    }
}
