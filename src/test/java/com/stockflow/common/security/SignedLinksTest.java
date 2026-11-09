package com.stockflow.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The links a partner opens without signing in (4A-3): they work until their time and for their route only; a forged or
 * altered one never does, and is never told it merely expired.
 */
class SignedLinksTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PATH = "/api/v1/production-order-files/0192f000-0000-7000-8000-000000000001/slip";
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    private final SignedLinks links = new SignedLinks(new SignedLinkProperties(KEY, "https://api.stockflow.vn/"));

    private static String query(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        throw new AssertionError("no " + name + " in " + url);
    }

    @Test
    @DisplayName("a link is the route under the public address, with its expiry and signature")
    void shape() {
        String url = links.url(PATH, NOW.plusSeconds(3600));
        assertThat(url).startsWith("https://api.stockflow.vn" + PATH + "?expires=" + NOW.plusSeconds(3600)
                .getEpochSecond() + "&signature=");
        assertThat(query(url, "signature")).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("valid until its time, expired from then on")
    void validThenExpired() {
        Instant until = NOW.plusSeconds(3600);
        String signature = query(links.url(PATH, until), "signature");
        assertThat(links.verify(PATH, until.getEpochSecond(), signature, NOW)).isEqualTo(SignedLinks.Verdict.VALID);
        assertThat(links.verify(PATH, until.getEpochSecond(), signature, until.minusSeconds(1)))
                .isEqualTo(SignedLinks.Verdict.VALID);
        assertThat(links.verify(PATH, until.getEpochSecond(), signature, until)).isEqualTo(SignedLinks.Verdict.EXPIRED);
    }

    @Test
    @DisplayName("another route, a later expiry, an altered or missing signature, another key: not valid")
    void forged() {
        Instant until = NOW.plusSeconds(3600);
        String signature = query(links.url(PATH, until), "signature");
        long expires = until.getEpochSecond();
        assertThat(links.verify(PATH.replace("/slip", "/print-files/x"), expires, signature, NOW))
                .isEqualTo(SignedLinks.Verdict.INVALID);
        assertThat(links.verify(PATH, expires + 86_400, signature, NOW)).isEqualTo(SignedLinks.Verdict.INVALID);
        char last = signature.charAt(signature.length() - 1);
        String altered = signature.substring(0, signature.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(links.verify(PATH, expires, altered, NOW)).isEqualTo(SignedLinks.Verdict.INVALID);
        assertThat(links.verify(PATH, expires, "", NOW)).isEqualTo(SignedLinks.Verdict.INVALID);
        assertThat(links.verify(PATH, expires, null, NOW)).isEqualTo(SignedLinks.Verdict.INVALID);
        var other = new SignedLinks(new SignedLinkProperties(Base64.getEncoder().encodeToString(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
                30, 31, 32}), "https://api.stockflow.vn"));
        assertThat(other.verify(PATH, expires, signature, NOW)).isEqualTo(SignedLinks.Verdict.INVALID);
        assertThat(links.verify(PATH, expires - 7200, signature, NOW))
                .as("an expiry moved into the past is forged, not expired").isEqualTo(SignedLinks.Verdict.INVALID);
    }

    @Test
    @DisplayName("no public address: no link is made, and whoever wanted one can tell")
    void unavailable() {
        var none = new SignedLinks(new SignedLinkProperties(KEY, ""));
        assertThat(none.available()).isFalse();
        assertThatThrownBy(() -> none.url(PATH, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(links.available()).isTrue();
    }

    @Test
    @DisplayName("a route with a query, or not a route, is refused")
    void onlyRoutes() {
        assertThatThrownBy(() -> links.url(PATH + "?x=1", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.url("//evil.example/x", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.url("https://evil.example/x", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key that is not base64 of 32 bytes, or an address that is not http(s), stops the start")
    void misconfigured() {
        assertThatThrownBy(() -> new SignedLinks(new SignedLinkProperties("not base64!", "https://a.vn")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SignedLinks(new SignedLinkProperties(
                Base64.getEncoder().encodeToString(new byte[16]), "https://a.vn")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SignedLinks(new SignedLinkProperties(KEY, "ftp://a.vn")))
                .isInstanceOf(IllegalStateException.class);
        var generated = new SignedLinks(new SignedLinkProperties(null, "https://a.vn"));
        String url = generated.url(PATH, NOW.plusSeconds(60));
        assertThat(generated.verify(PATH, NOW.plusSeconds(60).getEpochSecond(), query(url, "signature"), NOW))
                .as("no key configured: one made for this process still signs and verifies")
                .isEqualTo(SignedLinks.Verdict.VALID);
    }
}
