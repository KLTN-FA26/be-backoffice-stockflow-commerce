package com.stockflow.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 E1: the seller's company details are required in the prod profile and nowhere else. Run on the two classes
 * alone, so the test says what the profile and the properties do without the rest of the application.
 */
class SellerProfileRequirementTest {

    @Configuration
    @EnableConfigurationProperties(SellerProperties.class)
    @Import(SellerProfileRequirement.class)
    static class Seller {
    }

    private static final String[] COMPLETE = {
            "stockflow.seller.name=CONG TY TNHH BAO BI",
            "stockflow.seller.tax-code=0312345678",
            "stockflow.seller.address=1 Le Loi, P. Ben Nghe, TP HCM",
            "stockflow.seller.representative=Nguyen Van A",
            "stockflow.seller.representative-title=Giam doc"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Seller.class);

    @Test
    @DisplayName("prod with all five set starts")
    void prodStartsWithACompleteSeller() {
        runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(COMPLETE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SellerProperties.class).complete()).isTrue();
                });
    }

    @Test
    @DisplayName("prod refuses to start while any of the five is blank, naming the variables")
    void prodRefusesAnIncompleteSeller() {
        for (int missing = 0; missing < COMPLETE.length; missing++) {
            String[] values = COMPLETE.clone();
            values[missing] = values[missing].substring(0, values[missing].indexOf('=') + 1) + " ";
            runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(values)
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("SELLER_TAX_CODE"));
        }
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).as("nothing set at all").hasFailed());
    }

    @Test
    @DisplayName("outside prod a blank seller does not stop the application: documents naming it are refused one by one")
    void otherProfilesStartWithoutASeller() {
        for (String profile : new String[] {"dev", "local", "test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(SellerProperties.class).complete()).isFalse();
                assertThat(context).doesNotHaveBean(SellerProfileRequirement.class);
            });
        }
    }

    @Test
    @DisplayName("review finding 5: prod refuses a tax code that is not one, and a value too long for its column")
    void prodRefusesASellerThatCannotGoOnAnInvoice() {
        record Wrong(int field, String value, String named) { }
        for (Wrong wrong : new Wrong[] {
                new Wrong(1, "0312345678 (HCM)", "SELLER_TAX_CODE"),
                new Wrong(1, "031234567", "SELLER_TAX_CODE"),
                new Wrong(1, "031234567890", "SELLER_TAX_CODE"),
                new Wrong(1, "0312345678-01", "SELLER_TAX_CODE"),
                new Wrong(0, "C".repeat(201), "SELLER_NAME"),
                new Wrong(2, "D".repeat(501), "SELLER_ADDRESS"),
                new Wrong(3, "N".repeat(201), "SELLER_REPRESENTATIVE"),
                new Wrong(4, "T".repeat(101), "SELLER_REPRESENTATIVE_TITLE")}) {
            String[] values = COMPLETE.clone();
            values[wrong.field()] = values[wrong.field()].substring(0, values[wrong.field()].indexOf('=') + 1)
                    + wrong.value();
            runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(values)
                    .run(context -> assertThat(context).as(wrong.named()).hasFailed().getFailure().rootCause()
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining(wrong.named()));
        }
        // A dependent unit's 10-3, and values exactly as long as their columns, start.
        String[] edge = {
                "stockflow.seller.name=" + "C".repeat(200),
                "stockflow.seller.tax-code=0312345678-001",
                "stockflow.seller.address=" + "D".repeat(500),
                "stockflow.seller.representative=" + "N".repeat(200),
                "stockflow.seller.representative-title=" + "T".repeat(100)};
        runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(edge)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
