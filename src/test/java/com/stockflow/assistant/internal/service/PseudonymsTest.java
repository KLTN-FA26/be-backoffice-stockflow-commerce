package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Customer aliases (ADR-0014): given out once per customer and conversation, read back as names. */
class PseudonymsTest {

    private static final UUID ABC = UUID.randomUUID();
    private static final UUID XYZ = UUID.randomUUID();

    @Test
    @DisplayName("a customer keeps one alias in a conversation, numbered after the ones it already had")
    void aliases() {
        Pseudonyms aliases = new Pseudonyms(Map.of(1, XYZ));

        assertThat(aliases.aliasOf(XYZ, "XYZ")).isEqualTo("KH#1");
        assertThat(aliases.aliasOf(ABC, "Công ty ABC")).isEqualTo("KH#2");
        assertThat(aliases.aliasOf(ABC, "Công ty ABC")).isEqualTo("KH#2");
        assertThat(aliases.added()).as("only the new one is stored").isEqualTo(Map.of(2, ABC));
        assertThat(aliases.customerOf(" kh#2 ")).contains(ABC);
        assertThat(aliases.customerOf("KH#9")).isEmpty();
        assertThat(aliases.customerOf("Công ty ABC")).isEmpty();
    }

    @Test
    @DisplayName("tools running at once give one customer one alias, and two customers two")
    void concurrentTools() {
        Pseudonyms aliases = new Pseudonyms(Map.of());
        CopyOnWriteArraySet<String> given = new CopyOnWriteArraySet<>();

        IntStream.range(0, 200).parallel().forEach(i -> given.add(aliases.aliasOf(i % 2 == 0 ? ABC : XYZ, "x")));

        assertThat(given).containsExactlyInAnyOrder("KH#1", "KH#2");
        assertThat(aliases.added()).hasSize(2);
    }

    @Test
    @DisplayName("text read back has each known alias as the name, whole aliases only; unknown ones stay as written")
    void revealer() {
        var reveal = Pseudonyms.revealer(Map.of(1, ABC, 2, XYZ), ids -> Map.of(ABC, "Công ty ABC"));

        assertThat(reveal.apply("KH#1 nợ quá hạn; KH#2 và KH#3 thì không; KH#1A, SKH#1, KH#01, KH-1 giữ nguyên."))
                .isEqualTo("Công ty ABC nợ quá hạn; KH#2 và KH#3 thì không; KH#1A, SKH#1, KH#01, KH-1 giữ nguyên.");
        assertThat(reveal.apply(null)).isNull();
        assertThat(Pseudonyms.revealer(Map.of(), ids -> {
            throw new AssertionError("no lookup without aliases");
        }).apply("KH#1")).isEqualTo("KH#1");
    }

    @Test
    @DisplayName("a result is written with aliases for the model provider and with names everywhere else")
    void writer() throws Exception {
        ObjectMapper json = new ObjectMapper();
        record Row(CustomerName customer, int owed) {
        }
        Row row = new Row(new CustomerName(ABC, "Công ty ABC"), 5);
        Pseudonyms aliases = new Pseudonyms(Map.of());

        assertThat(json.writer().withAttribute(Pseudonyms.class, aliases).writeValueAsString(row))
                .isEqualTo("{\"customer\":\"KH#1\",\"owed\":5}");
        assertThat(json.writeValueAsString(row)).isEqualTo("{\"customer\":\"Công ty ABC\",\"owed\":5}");
        assertThat(json.writeValueAsString(new Row(new CustomerName(ABC, null), 5)))
                .isEqualTo("{\"customer\":null,\"owed\":5}");
    }

    @Test
    @DisplayName("a customer argument may be an alias of the conversation: that customer, with no search; one unknown "
            + "names nobody")
    void aliasAsArgument() {
        CustomerService customers = mock(CustomerService.class);
        when(customers.findById(ABC)).thenReturn(Optional.of(new CustomerSummary(ABC, null, "Công ty ABC", null, null,
                "0101234567", null, "ACTIVE", 0, Instant.now())));
        Pseudonyms aliases = new Pseudonyms(Map.of(3, ABC));

        CustomerMatch known = CustomerMatch.find(customers, "KH#3", false, aliases);
        CustomerMatch unknown = CustomerMatch.find(customers, "KH#4", true, aliases);

        assertThat(known.customerId()).isEqualTo(ABC);
        assertThat(unknown.found()).isFalse();
        assertThat(unknown.candidates()).isEmpty();
        assertThat(unknown.note()).contains("stands for no customer of this conversation");
    }

    @Test
    @DisplayName("a known customer's registered name typed in free text is sent as its alias, whole names only, longest "
            + "first; a customer the conversation has not met is not guessed at")
    void namesInText() {
        Pseudonyms aliases = new Pseudonyms(Map.of(1, ABC, 2, XYZ),
                Map.of(ABC, "Công ty ABC", XYZ, "Công ty ABC Sài Gòn"));

        assertThat(aliases.hideNames("Gọi nhắc công ty abc và Công ty ABC Sài Gòn, không phải Công ty ABCD."))
                .isEqualTo("Gọi nhắc KH#1 và KH#2, không phải Công ty ABCD.");
        assertThat(aliases.hideNames("Công ty Mới chưa có bí danh")).isEqualTo("Công ty Mới chưa có bí danh");
        assertThat(new Pseudonyms(Map.of(1, ABC), Map.of(ABC, "ABC")).hideNames("Hàng ABC về chưa?"))
                .as("a name too short to tell from a word stays").isEqualTo("Hàng ABC về chưa?");
    }

    @Test
    @DisplayName("typed text shaped like an alias is not read back as one")
    void aliasShapedNotes() {
        assertThat(TypedText.masked("KH#12 xin lùi hạn")).isEqualTo("KH# 12 xin lùi hạn");
        assertThat(Pseudonyms.revealer(Map.of(12, ABC), ids -> Map.of(ABC, "Công ty ABC"))
                .apply(TypedText.masked("KH#12 xin lùi hạn"))).isEqualTo("KH# 12 xin lùi hạn");
    }

    @Test
    @DisplayName("off, nothing is given out, and with no aliases stored nothing is replaced")
    void off() {
        assertThat(CustomerPseudonyms.OFF.forQuestion(UUID.randomUUID())).isNull();
        assertThat(CustomerPseudonyms.OFF.revealer(UUID.randomUUID()).apply("KH#1")).isEqualTo("KH#1");
    }
}
