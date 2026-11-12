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
}
