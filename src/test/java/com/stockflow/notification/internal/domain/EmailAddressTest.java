package com.stockflow.notification.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"buyer@cup-maker.vn", "  sales.team+orders@in-ly.com.vn ", "A.B@Sub.Example-Co.vn"})
    @DisplayName("ordinary business addresses are usable, trimmed")
    void usable(String raw) {
        assertThat(EmailAddress.usable(raw)).contains(raw.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no-at-sign", "a@b", "a@localhost", "two@@ats.vn", "a..b@x.vn",
            "spaces in@x.vn", "<script>@x.vn", "trailing@dot.", "Nguyễn@công-ty.vn"})
    @DisplayName("what a provider would refuse - including what @Email lets through, like a@b - is not usable")
    void notUsable(String raw) {
        assertThat(EmailAddress.usable(raw)).isEmpty();
        assertThat(EmailAddress.usable(null)).isEmpty();
    }

    @Test
    @DisplayName("an address longer than 254 characters is not usable")
    void tooLong() {
        assertThat(EmailAddress.usable("a".repeat(60) + "@" + "d".repeat(190) + ".vn")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x@example.com", "x@shop.example.org", "x@EXAMPLE.NET", "x@demo.test", "x@a.invalid",
            "x@stockflow.local", "x@host.localhost", "x@acme.example"})
    @DisplayName("domains reserved by RFC 2606 / 6761 can never receive mail")
    void reserved(String address) {
        assertThat(EmailAddress.reservedDomain(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x@cup-maker.vn", "x@examples.com", "x@mytest.vn", "x@local-print.vn"})
    @DisplayName("real domains that merely look like reserved ones are not reserved")
    void notReserved(String address) {
        assertThat(EmailAddress.reservedDomain(address)).isFalse();
    }

    @Test
    @DisplayName("the address of a configured sender, with or without a display name")
    void addressOfSender() {
        assertThat(EmailAddress.addressOf("StockFlow <no-reply@stockflow.vn>")).isEqualTo("no-reply@stockflow.vn");
        assertThat(EmailAddress.addressOf("no-reply@stockflow.vn")).isEqualTo("no-reply@stockflow.vn");
        assertThat(EmailAddress.addressOf("StockFlow <no-reply@stockflow.vn> trailing")).isNull();
        assertThat(EmailAddress.addressOf("StockFlow")).isNull();
        assertThat(EmailAddress.addressOf(null)).isNull();
    }
}
