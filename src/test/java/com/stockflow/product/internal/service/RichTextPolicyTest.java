package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The allow-list a description is cleaned to before it is stored and shown to every customer (ADR-0013). */
class RichTextPolicyTest {

    private static final String CDN = "https://cdn.example.test";
    private final RichTextPolicy policy = new RichTextPolicy(new ProductMediaProperties(CDN));

    @Test
    @DisplayName("what Tiptap's StarterKit writes is kept as it is")
    void keepsTheEditorsMarkup() {
        String html = "<h2>Ly giấy</h2><p>In <strong>logo</strong>, <em>2 mặt</em>, <u>màu</u> <s>cũ</s> "
                + "<code>12oz</code></p><ul><li><p>Dung tích</p></li></ul><ol><li>Một</li></ol>"
                + "<blockquote><p>Trích</p></blockquote><pre><code>mã</code></pre><hr><p>a<br>b</p>";

        assertThat(policy.clean(html, "description")).isEqualTo(html);
    }

    @Test
    @DisplayName("scripts, event handlers, styles and classes are removed")
    void removesWhatCouldRun() {
        String cleaned = policy.clean("<p class=\"x\" style=\"color:red\" onclick=\"steal()\">Ly</p>"
                + "<script>steal()</script><iframe src=\"https://evil.test\"></iframe>"
                + "<svg onload=\"steal()\"></svg><img src=x onerror=steal()>", "description");

        assertThat(cleaned).isEqualTo("<p>Ly</p>");
    }

    @Test
    @DisplayName("a link keeps only http, https or mailto, and never passes the page's authority on")
    void linksAreSafe() {
        assertThat(policy.clean("<p><a href=\"https://shop.test/a\" target=\"_blank\">a</a></p>", "description"))
                .isEqualTo("<p><a href=\"https://shop.test/a\" rel=\"nofollow noopener noreferrer\">a</a></p>");
        assertThat(policy.clean("<p><a href=\"javascript:steal()\">a</a></p>", "description"))
                .isEqualTo("<p><a rel=\"nofollow noopener noreferrer\">a</a></p>");
        assertThat(policy.clean("<p><a href=\"mailto:sales@shop.test\">a</a></p>", "description"))
                .contains("href=\"mailto:sales@shop.test\"");
    }

    @Test
    @DisplayName("an image shows only from the system's CDN prefix of published product images")
    void onlyOurImages() {
        String ours = CDN + "/product-images/2026/11/27/abc.jpg";

        assertThat(policy.clean("<p>a</p><img src=\"" + ours + "\" alt=\"Ly\">", "description"))
                .isEqualTo("<p>a</p><img src=\"" + ours + "\" alt=\"Ly\">");
        assertThat(policy.clean("<p>a</p><img src=\"https://tracker.test/pixel.gif\">", "description"))
                .isEqualTo("<p>a</p>");
        assertThat(policy.clean("<p>a</p><img src=\"" + CDN + "/product-originals/2026/private.png\">",
                "description")).isEqualTo("<p>a</p>");
        assertThat(policy.clean("<p>a</p><img src=\"" + CDN + "/product-images/../product-originals/x.png\">",
                "description")).isEqualTo("<p>a</p>");
        assertThat(policy.clean("<img src=\"data:image/png;base64,AAAA\">", "description")).isNull();
    }

    @Test
    @DisplayName("without a CDN configured, no image is shown at all")
    void noCdnNoImages() {
        var unconfigured = new RichTextPolicy(new ProductMediaProperties(""));

        assertThat(unconfigured.clean("<p>a</p><img src=\"" + CDN + "/product-images/x.jpg\">", "description"))
                .isEqualTo("<p>a</p>");
    }

    @Test
    @DisplayName("an empty editor is no description, and plain text stays plain text")
    void emptyAndPlain() {
        assertThat(policy.clean(null, "description")).isNull();
        assertThat(policy.clean("  ", "description")).isNull();
        assertThat(policy.clean("<p></p>", "description")).isNull();
        assertThat(policy.clean("<p> <br> </p>", "description")).isNull();
        assertThat(policy.clean("Ly in logo", "description")).isEqualTo("Ly in logo");
        assertThat(policy.clean("<p>a &lt; b</p>", "description")).isEqualTo("<p>a &lt; b</p>");
    }

    @Test
    @DisplayName("a description over the limit is refused, naming the field")
    void tooLong() {
        assertThatThrownBy(() -> policy.clean("x".repeat(RichTextPolicy.MAX_LENGTH + 1), "description"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("description");
                });
    }

    @Test
    @DisplayName("the text of a description is its words, tags gone")
    void text() {
        assertThat(RichTextPolicy.text("<h2>Ly</h2><p>In <strong>logo</strong></p>")).isEqualTo("Ly In logo");
        assertThat(RichTextPolicy.text("<p></p>")).isNull();
        assertThat(RichTextPolicy.text(null)).isNull();
    }
}
