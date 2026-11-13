package com.stockflow.chat.internal.domain;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pure rules of package 3 (kltn-docs 16): business hours, masking (BR-08), the VietQR payload (BR-06), the
 * first-response alert once (BR-09), CSAT once, the edit window, and saved replies' variables.
 */
class ChatRulesTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BusinessHours HOURS = new BusinessHours(VN, EnumSet.range(DayOfWeek.MONDAY,
            DayOfWeek.SATURDAY), LocalTime.of(8, 0), LocalTime.of(17, 30));
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final Actor AGENT = Actor.agent(AGENT_ID, false);
    private static final Actor PORTAL = Actor.customer(UUID.randomUUID());

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, VN).toInstant();
    }

    @Test
    @DisplayName("16 §4.1 step 3: 15 working minutes - asked on Saturday 17:25, due on Monday 08:10")
    void businessHours() {
        Instant saturdayLate = at(2026, 11, 14, 17, 25);
        assertThat(HOURS.plus(saturdayLate, Duration.ofMinutes(15))).isEqualTo(at(2026, 11, 16, 8, 10));
        assertThat(HOURS.plus(at(2026, 11, 13, 10, 0), Duration.ofMinutes(15))).isEqualTo(at(2026, 11, 13, 10, 15));
        assertThat(HOURS.plus(at(2026, 11, 15, 12, 0), Duration.ofMinutes(15))).as("Sunday").isEqualTo(
                at(2026, 11, 16, 8, 15));
        assertThat(HOURS.isOpenAt(at(2026, 11, 15, 10, 0))).isFalse();
        assertThat(HOURS.nextOpening(at(2026, 11, 13, 20, 0))).isEqualTo(at(2026, 11, 14, 8, 0));
        assertThat(HOURS.between(saturdayLate, at(2026, 11, 16, 8, 10))).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("BR-08: card numbers (Luhn), CVV, passwords and OTPs are masked; document numbers and phones are not")
    void masking() {
        assertThat(SensitiveData.mask("so the 4111-1111-1111-1111 nhe")).isEqualTo("so the ****-****-****-1111 nhe");
        assertThat(SensitiveData.mask("4111111111111112")).as("fails Luhn").isEqualTo("4111111111111112");
        assertThat(SensitiveData.mask("don SO-20991231-000001, sdt 0901234567"))
                .isEqualTo("don SO-20991231-000001, sdt 0901234567");
        assertThat(SensitiveData.mask("CVV 123")).isEqualTo("CVV ***");
        assertThat(SensitiveData.mask("mật khẩu là Abc@123")).isEqualTo("mật khẩu là ********");
        assertThat(SensitiveData.mask("password: hunter2")).isEqualTo("password: ********");
        assertThat(SensitiveData.mask("OTP: 123456")).isEqualTo("OTP: ********");
        assertThat(SensitiveData.mask("passport ok, mkt team")).isEqualTo("passport ok, mkt team");
        ChatMessage text = ChatMessage.text(UUID.randomUUID(), UUID.randomUUID(), 1, PORTAL,
                "the 4111 1111 1111 1111", null, null, Instant.now());
        assertThat(text.body()).isEqualTo("the **** **** **** 1111");
        assertThat(text.sameText("the 4111 1111 1111 1111")).as("a retried send is still the same").isTrue();
    }

    @Test
    @DisplayName("BR-06: the VietQR payload names the bank, account, amount and content, with its CRC")
    void vietQr() {
        assertThat(VietQr.crc16("123456789")).isEqualTo("29B1");
        String qr = VietQr.payload("970436", "0071000888999", new BigDecimal("1500000"), "HĐ-01 Đợt 1");
        assertThat(qr).startsWith("000201010212").contains("0010A000000727").contains("0006970436")
                .contains("01130071000888999").contains("0208QRIBFTTA").contains("5303704").contains("54071500000")
                .contains("5802VN").contains("0811HD-01 Dot 1");
        assertThat(qr.substring(qr.length() - 4)).isEqualTo(VietQr.crc16(qr.substring(0, qr.length() - 4)));
        assertThatThrownBy(() -> VietQr.payload("97", "1", BigDecimal.ONE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BR-09: a late first answer alerts once; an answered conversation never")
    void firstResponseAlertOnce() {
        Instant t0 = Instant.parse("2026-11-13T03:00:00Z");
        Conversation c = Conversation.open(UUID.randomUUID(), UUID.randomUUID(), null, null, PORTAL, null, t0,
                t0.plusSeconds(900));
        c.route(AGENT_ID, t0);
        assertThat(c.alertFirstResponseOverdue(t0.plusSeconds(60))).isFalse();
        assertThat(c.alertFirstResponseOverdue(t0.plusSeconds(1000))).isTrue();
        assertThat(c.alertFirstResponseOverdue(t0.plusSeconds(2000))).isFalse();
        assertThat(c.pullLog()).extracting(LogEntry::type).containsOnlyOnce(ConversationEventType.FIRST_RESPONSE_OVERDUE);

        Conversation quiet = Conversation.open(UUID.randomUUID(), UUID.randomUUID(), null, null, Actor.system(), null,
                t0, (Instant) null);
        assertThat(quiet.alertFirstResponseOverdue(t0.plusSeconds(100_000))).as("no clock: nobody asked").isFalse();
        quiet.acceptCustomerMessage(PORTAL, t0, t0.plusSeconds(900));
        assertThat(quiet.firstResponseDueAt()).as("the customer's word starts it").isEqualTo(t0.plusSeconds(900));
    }

    @Test
    @DisplayName("CSAT: 1-5, once, while resolved")
    void csatOnce() {
        Instant t0 = Instant.parse("2026-11-13T03:00:00Z");
        Conversation c = Conversation.open(UUID.randomUUID(), UUID.randomUUID(), null, null, PORTAL, null, t0,
                Duration.ofMinutes(15));
        c.route(AGENT_ID, t0);
        c.acceptAgentMessage(AGENT, t0);
        assertThatThrownBy(() -> c.rate(PORTAL, 5, null, t0)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("rated");
        c.changeStatus(AGENT, ConversationStatus.RESOLVED, null, t0, Duration.ofDays(14));
        assertThatThrownBy(() -> c.rate(PORTAL, 6, null, t0)).isInstanceOf(BusinessException.class);
        c.rate(PORTAL, 4, " Nhanh ", t0);
        assertThat(c.csatScore()).isEqualTo(4);
        assertThat(c.csatComment()).isEqualTo("Nhanh");
        assertThatThrownBy(() -> c.rate(PORTAL, 5, null, t0)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("Edit window: the author, 5 minutes, text or note; a card never")
    void editWindow() {
        Instant sent = Instant.parse("2026-11-13T03:00:00Z");
        Duration window = Duration.ofMinutes(5);
        ChatMessage text = ChatMessage.text(UUID.randomUUID(), UUID.randomUUID(), 1, PORTAL, "500 thung", null, null,
                sent);
        assertThat(text.edit(PORTAL, "600 thung", sent.plusSeconds(60), window).body()).isEqualTo("600 thung");
        assertThatThrownBy(() -> text.edit(PORTAL, "700", sent.plusSeconds(301), window))
                .isInstanceOf(BusinessException.class).hasMessageContaining("passed");
        assertThatThrownBy(() -> text.delete(AGENT, sent.plusSeconds(10), window))
                .isInstanceOf(BusinessException.class);
        assertThat(text.delete(PORTAL, sent.plusSeconds(10), window).deletedAt()).isNotNull();
        ChatMessage card = ChatMessage.card(UUID.randomUUID(), UUID.randomUUID(), 2, Actor.system(), CardKind.QUOTE,
                "{}", null, null, sent);
        assertThatThrownBy(() -> card.delete(Actor.system(), sent, window)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Saved replies: known variables filled, an unknown one left as written; shared ones are the supervisor's")
    void cannedResponse() {
        CannedResponse reply = new CannedResponse(UUID.randomUUID(), null, "Bao gia", "Chao {customerName}, bao gia"
                + " {quoteCode} da gui. {orderCode}{other}", "bao-gia", null, null, 0);
        assertThat(reply.render(Map.of("customerName", "Cong ty A", "quoteCode", "QT-1")))
                .isEqualTo("Chao Cong ty A, bao gia QT-1 da gui. {other}");
        assertThatThrownBy(() -> reply.requireChangeableBy(AGENT)).isInstanceOf(BusinessException.class);
        reply.requireChangeableBy(Actor.agent(UUID.randomUUID(), true));
        assertThatThrownBy(() -> new CannedResponse(UUID.randomUUID(), null, "x", "y", "Bad Shortcut", null, null, 0))
                .isInstanceOf(BusinessException.class);
    }
}
