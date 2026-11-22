package com.stockflow.order.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.order.internal.domain.TaxRates;
import com.stockflow.product.spi.TaxRatesToday;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order module's answer to {@link TaxRatesToday}: {@link TaxRates#onDay} of today's business day, the day a quote
 * drawn up now reads its rates on.
 */
@Component
class TaxRatesTodayAdapter implements TaxRatesToday {

    private final TaxRates rates;
    private final Clock clock;

    TaxRatesTodayAdapter(TaxRates rates, Clock clock) {
        this.rates = rates;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> today() {
        return rates.onDay(BusinessCalendar.date(clock.instant()));
    }
}
