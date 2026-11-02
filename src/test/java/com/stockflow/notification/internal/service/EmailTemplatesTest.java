package com.stockflow.notification.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.placed;
import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

/** The HTML and plain-text layout of a customer message. */
class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates();
    private final CustomerEmailComposer composer = new CustomerEmailComposer(properties());
    private static final Locale VI = Locale.forLanguageTag("vi");

    private EmailTemplates.Rendered render(String addressee) {
        return templates.render(composer.orderPlaced(placed(UUID.randomUUID(), null)).orElseThrow().draft()
                .write(CustomerEmailComposer.displayName(addressee)), VI);
    }

    @Test
    @DisplayName("the HTML part carries every fact, the lines, the greeting, in a Vietnamese document")
    void html() {
        EmailTemplates.Rendered rendered = render("Công ty TNHH In Ly An Phát");
        assertThat(rendered.subject()).isEqualTo("Đơn hàng SO-20261024-0001 đã được tiếp nhận");
        assertThat(rendered.html()).contains("<html lang=\"vi\">", "Kính gửi Công ty TNHH In Ly An Phát,",
                "SO-20261024-0001", "1.500.000 ₫", "CUP-PET-500", "1.250.000 ₫", "Mã sản phẩm", "Trân trọng,",
                "vui lòng không trả lời");
        assertThat(rendered.html()).as("the template's own comment is stripped, its placeholders replaced")
                .doesNotContain("parser-level comment", ">Heading<", ">Footer<", "th:text", "null");
    }

    @Test
    @DisplayName("a name with markup is escaped in the HTML: no script, no tag, no attribute can reach an inbox")
    void htmlEscapes() {
        EmailTemplates.Rendered rendered = render("<script>alert('x')</script><img src=x onerror=alert(1)> & Co");
        assertThat(rendered.html()).doesNotContain("<script>", "<img", "onerror=alert(1)>");
        assertThat(rendered.html()).contains("&lt;script&gt;", "&lt;img", "&amp; Co");
        assertThat(rendered.text()).as("the plain text has no markup to escape").contains("<script>alert('x')</script>");
    }

    @Test
    @DisplayName("the plain-text part reads on its own: heading, greeting, facts, lines, sign-off, footer")
    void text() {
        String text = render("Công ty A").text();
        assertThat(text).startsWith("Chúng tôi đã tiếp nhận đơn hàng của Quý khách\n\nKính gửi Công ty A,\n\n");
        assertThat(text).contains("Mã đơn hàng: SO-20261024-0001\nTổng giá trị đơn hàng: 1.500.000 ₫\n",
                "Mã sản phẩm | Số lượng | Đơn giá | Thành tiền\nCUP-PET-500 | 2000 | 625 ₫ | 1.250.000 ₫\n",
                "Trân trọng,\nStockFlow\n\n--\nĐây là email tự động");
        assertThat(text).doesNotContain("null", "<");
    }
}
