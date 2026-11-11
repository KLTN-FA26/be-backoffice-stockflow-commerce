package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 4A-1 (kltn-docs 17 §4.3): a producer of the catalogue - its code, its channel, its standard lead time. */
class ProducerTest {

    private static Producer.Details details(Producer.Channel channel, String email, Integer days) {
        return new Producer.Details("  Xuong in Minh Phat  ", " Chi Lan ", "0901234567", email, channel, days, null);
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("registered ACTIVE, the code upper-cased, the lead time 14 days by default (B44)")
    void registered() {
        Producer producer = Producer.register(" in-minhphat ", details(Producer.Channel.EMAIL, "don@minhphat.vn", null));
        assertThat(producer.code()).isEqualTo("IN-MINHPHAT");
        assertThat(producer.name()).isEqualTo("Xuong in Minh Phat");
        assertThat(producer.contactName()).isEqualTo("Chi Lan");
        assertThat(producer.standardLeadDays()).isEqualTo(Producer.DEFAULT_LEAD_DAYS);
        assertThat(producer.status()).isEqualTo(Producer.Status.ACTIVE);
        assertThat(producer.takesOrders()).isTrue();
    }

    @Test
    @DisplayName("refused: a code that is not one, no name, e-mail orders with no address, a lead time out of 1-365")
    void refused() {
        assertThatThrownBy(() -> Producer.register("X", details(Producer.Channel.PDF, null, 10)))
                .extracting(ProducerTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Producer.register("IN 1", details(Producer.Channel.PDF, null, 10)))
                .extracting(ProducerTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Producer.register("IN1", new Producer.Details(" ", null, null, null,
                Producer.Channel.PDF, null, null))).extracting(ProducerTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Producer.register("IN1", details(Producer.Channel.EMAIL, null, 10)))
                .as("an order by e-mail needs an address").extracting(ProducerTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Producer.register("IN1", details(Producer.Channel.EMAIL, "not-an-address", 10)))
                .extracting(ProducerTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Producer.register("IN1", details(Producer.Channel.PDF, null, 0)))
                .extracting(ProducerTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Producer.register("IN1", details(Producer.Channel.PDF, null, 366)))
                .extracting(ProducerTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a PDF producer needs no address; out of use, it takes no new order")
    void pdfAndInactive() {
        Producer producer = Producer.register("XUONG-01", details(Producer.Channel.PDF, null, 21));
        assertThat(producer.email()).isNull();
        assertThat(producer.standardLeadDays()).isEqualTo(21);
        producer.changeStatus(Producer.Status.INACTIVE);
        assertThat(producer.takesOrders()).isFalse();
    }

    @Test
    @DisplayName("the e-mail check accepts and refuses exactly what the regular expression it replaced did")
    void emailShapeAsBefore() {
        Pattern before = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
        String[] fixed = {"don@minhphat.vn", "a@b.c", "a@.b", "a@b.", "@b.c", "a@b", "a@@b.c", "a@b@c.d", "a b@c.d",
                "a@b.c\n", "a@b..c", "a@.b.c", "a.b@c", "a@b.c.d", "\u000Ba@b.c", "a@b\u00A0.c"};
        for (String value : fixed) {
            assertThat(Producer.isEmail(value)).as(value).isEqualTo(before.matcher(value).matches());
        }
        char[] alphabet = {'a', '@', '.', ' ', '\t', '\n', '\u000B', '\f', '\r'};
        Random random = new Random(42);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder value = new StringBuilder();
            for (int i = random.nextInt(10); i > 0; i--) {
                value.append(alphabet[random.nextInt(alphabet.length)]);
            }
            assertThat(Producer.isEmail(value.toString())).as("[%s]", value)
                    .isEqualTo(before.matcher(value).matches());
        }
    }

    @Test
    @DisplayName("a long address of many dots is judged at once (CodeQL java/polynomial-redos)")
    void emailCheckIsLinear() {
        String refused = "!@!." + "!.".repeat(500_000) + "@";
        String accepted = "!@!." + "!.".repeat(500_000) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(Producer.isEmail(refused)).isFalse();
            assertThat(Producer.isEmail(accepted)).isTrue();
        });
    }
}
