package com.stockflow.common.document;

import com.stockflow.common.config.SellerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seller's letterhead: the new fields of {@code stockflow.seller.*} bind beside the five a contract always needed,
 * a blank one is left off the paper, and a logo that cannot be printed stops the application rather than the first
 * quotation.
 */
class SellerLetterheadTest {

    @EnableConfigurationProperties(SellerProperties.class)
    static class Config {
    }

    private static SellerProperties seller(String logo) {
        return new SellerProperties("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "0312345678", "Số 268 Đường số 7", "Trần "
                + "Quốc An", "Giám đốc", " (028) 3765 0000 ", "", null, "0071001234567", "Vietcombank", "Chi nhánh "
                + "Bình Tây", "Thành phố Hồ Chí Minh", logo);
    }

    @Test
    @DisplayName("stockflow.seller.* binds the letterhead beside the legal identity; a deployment that sets only the "
            + "five legal fields still has a complete seller")
    void binds() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("stockflow.seller.name=AN PHÁT", "stockflow.seller.tax-code=0312345678",
                        "stockflow.seller.address=Số 268", "stockflow.seller.representative=Trần Quốc An",
                        "stockflow.seller.representative-title=Giám đốc", "stockflow.seller.phone=(028) 3765 0000",
                        "stockflow.seller.bank-account-number=0071001234567", "stockflow.seller.bank-name=Vietcombank",
                        "stockflow.seller.bank-branch=Chi nhánh Bình Tây", "stockflow.seller.city=Thành phố Hồ Chí Minh",
                        "stockflow.seller.logo=classpath:branding/seller-logo.png")
                .run(context -> {
                    SellerProperties bound = context.getBean(SellerProperties.class);
                    assertThat(bound.complete()).isTrue();
                    assertThat(bound.phone()).isEqualTo("(028) 3765 0000");
                    assertThat(bound.bankAccountNumber()).isEqualTo("0071001234567");
                    assertThat(bound.bankBranch()).isEqualTo("Chi nhánh Bình Tây");
                    assertThat(bound.logo()).isEqualTo("classpath:branding/seller-logo.png");
                    assertThat(bound.email()).isNull();
                });
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("stockflow.seller.name=AN PHÁT", "stockflow.seller.tax-code=0312345678",
                        "stockflow.seller.address=Số 268", "stockflow.seller.representative=Trần Quốc An",
                        "stockflow.seller.representative-title=Giám đốc")
                .run(context -> assertThat(context.getBean(SellerProperties.class).complete()).isTrue());
        assertThat(new SellerProperties("a", "b", "c", "d", "e").complete()).isTrue();
        assertThat(new SellerProperties("a", "b", "c", "d", "").complete()).isFalse();
    }

    @Test
    @DisplayName("the letterhead carries what is set, trimmed, and null for what is blank; the bundled logo is a PNG")
    void letterhead() {
        Letterhead head = new SellerLetterhead(seller("classpath:branding/seller-logo.png"),
                new DefaultResourceLoader()).get();

        assertThat(head.name()).isEqualTo("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT");
        assertThat(head.phone()).isEqualTo("(028) 3765 0000");
        assertThat(head.email()).isNull();
        assertThat(head.website()).isNull();
        assertThat(head.bankBranch()).isEqualTo("Chi nhánh Bình Tây");
        assertThat(head.city()).isEqualTo("Thành phố Hồ Chí Minh");
        assertThat(head.logo()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(new SellerLetterhead(seller("  "), new DefaultResourceLoader()).get().logo()).isNull();
    }

    @Test
    @DisplayName("a logo that is missing, or is not a PNG, stops the application at startup and says which setting")
    void unusableLogoIsRefused() {
        assertThatThrownBy(() -> new SellerLetterhead(seller("classpath:branding/no-such-logo.png"),
                new DefaultResourceLoader())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stockflow.seller.logo").hasMessageContaining("no-such-logo.png");
        assertThatThrownBy(() -> new SellerLetterhead(seller("classpath:fonts/OFL-Tinos.txt"),
                new DefaultResourceLoader())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a PNG");
    }
}
