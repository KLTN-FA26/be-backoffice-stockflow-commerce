package com.stockflow.contracts;
/** Physical stock changed; consumers must re-read current quantities after commit. */
public record StockLevelChanged(String sku) {}
