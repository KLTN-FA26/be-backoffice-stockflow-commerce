package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;

/**
 * A change of a supplier item's price, handed to the repository so it can close the open price
 * period and open the next one in the same transaction as the update.
 */
public record PriceChange(Money from, Money to) {}
