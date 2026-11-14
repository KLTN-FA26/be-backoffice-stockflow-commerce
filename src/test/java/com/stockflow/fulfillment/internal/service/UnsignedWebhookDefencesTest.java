package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The defences of a webhook that is not signed which need no carrier to answer (WF-05 fix plan item 6): the list of
 * addresses a call may come from, the window of time its report must fall in, either of two credentials while one
 * replaces the other, and the configuration that carries them.
 */
class UnsignedWebhookDefencesTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    @Test
    @DisplayName("no list allows every address; a list allows its addresses and blocks, IPv4 and IPv6, and nothing else")
    void allowListAllowsItsAddressesOnly() {
        assertThat(IpAllowList.of(null).open()).isTrue();
        assertThat(IpAllowList.of(List.of()).allows("203.0.113.9")).isTrue();
        assertThat(IpAllowList.of(Arrays.asList(" ", null)).allows("203.0.113.9")).isTrue();

        IpAllowList list = IpAllowList.of(List.of("203.0.113.7", " 10.20.0.0/16 ", "2001:db8::/32", "192.0.2.128/25"));
        assertThat(list.open()).isFalse();
        assertThat(list.allows("203.0.113.7")).isTrue();
        assertThat(list.allows("203.0.113.8")).isFalse();
        assertThat(list.allows("10.20.255.1")).isTrue();
        assertThat(list.allows("10.21.0.1")).isFalse();
        assertThat(list.allows("192.0.2.129")).isTrue();
        assertThat(list.allows("192.0.2.127")).isFalse();
        assertThat(list.allows("2001:db8:1::5")).isTrue();
        assertThat(list.allows("2001:db9::5")).isFalse();
        // An IPv4 address a dual-stack server reports mapped into IPv6 is that IPv4 address.
        assertThat(list.allows("::ffff:203.0.113.7")).isTrue();
        assertThat(IpAllowList.of(List.of("0.0.0.0/0")).allows("198.51.100.1")).isTrue();
    }

    @Test
    @DisplayName("an address that cannot be read is not allowed, and a host name is never looked up")
    void allowListRefusesWhatIsNotAnAddress() {
        IpAllowList list = IpAllowList.of(List.of("203.0.113.7"));
        assertThat(list.allows(null)).isFalse();
        assertThat(list.allows("")).isFalse();
        assertThat(list.allows("localhost")).isFalse();
        assertThat(list.allows("203.0.113.7, 10.0.0.1")).isFalse();

        assertThatThrownBy(() -> IpAllowList.of(List.of("ahamove.com"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ahamove.com");
        assertThatThrownBy(() -> IpAllowList.of(List.of("10.0.0.0/33"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpAllowList.of(List.of("10.0.0.0/x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpAllowList.of(List.of("300.1.1.1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a report older than the window is outdated; one from the future goes to the error list; one with no "
            + "time is not judged")
    void windowOfCarrierTimes() {
        CarrierReportWindow window = new CarrierReportWindow(Duration.ofMinutes(30), Duration.ofMinutes(5));

        assertThat(window.judge(null, NOW)).isNull();
        assertThat(window.judge(NOW, NOW)).isNull();
        assertThat(window.judge(NOW.minus(Duration.ofMinutes(30)), NOW)).isNull();
        assertThat(window.judge(NOW.plus(Duration.ofMinutes(5)), NOW)).isNull();

        CarrierReportWindow.Outside old = window.judge(NOW.minus(Duration.ofMinutes(31)), NOW);
        assertThat(old.result()).isEqualTo(CarrierEventResult.STALE);
        assertThat(old.note()).contains("time window");
        CarrierReportWindow.Outside ahead = window.judge(NOW.plus(Duration.ofMinutes(6)), NOW);
        assertThat(ahead.result()).isEqualTo(CarrierEventResult.NEEDS_REVIEW);
        assertThat(ahead.note()).contains("time window");
    }

    @Test
    @DisplayName("while a credential is replaced the header may carry the current one or the next; with no next, only "
            + "the current")
    void eitherOfTwoCredentials() {
        String current = "webhook-credential-0123456789";
        String next = "webhook-credential-next-0123456789";

        assertThat(WebhookCredentials.headerHoldsEither(current, next, current)).isTrue();
        assertThat(WebhookCredentials.headerHoldsEither(current, next, " " + next + " ")).isTrue();
        assertThat(WebhookCredentials.headerHoldsEither(current, next, "guess")).isFalse();
        assertThat(WebhookCredentials.headerHoldsEither(current, next, null)).isFalse();
        assertThat(WebhookCredentials.headerHoldsEither(current, null, current)).isTrue();
        assertThat(WebhookCredentials.headerHoldsEither(current, null, next)).isFalse();
        // A missing value never matches a missing header.
        assertThat(WebhookCredentials.headerHoldsEither(null, null, null)).isFalse();
        assertThat(WebhookCredentials.headerHoldsEither(current, "", "")).isFalse();
    }

    @Test
    @DisplayName("the defences default to on - outcomes verified, a day back, five minutes ahead, any address - and a "
            + "mistyped setting stops the start-up")
    void configurationDefaultsAndRefusals() {
        AhamoveProperties properties = new AhamoveProperties("https://stg", "https://prod", "Khách hàng muốn hủy đơn",
                null);
        assertThat(properties.webhook().verifyOutcomes()).isTrue();
        assertThat(properties.webhook().maxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.webhook().maxFuture()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.webhook().allowedIps()).isEmpty();
        assertThat(new AhamoveProperties.Webhook(false, Duration.ofMinutes(10), Duration.ZERO, null).allowedIps())
                .isEmpty();

        assertThatThrownBy(() -> new AhamoveProperties.Webhook(true, Duration.ZERO, Duration.ofMinutes(5), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-age");
        assertThatThrownBy(() -> new AhamoveProperties.Webhook(true, Duration.ofHours(1), Duration.ofMinutes(-1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-future");
        assertThatThrownBy(() -> new AhamoveProperties.Webhook(true, Duration.ofHours(1), Duration.ZERO,
                List.of("10.0.0.0/8", "not-an-address")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not-an-address");
    }
}
