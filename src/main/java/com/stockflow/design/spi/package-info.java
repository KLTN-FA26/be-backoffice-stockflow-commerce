/**
 * Ports the design module declares and another module implements, because that module depends on design and design
 * cannot depend on it back. {@link com.stockflow.design.spi.RequestQuotes}: the quote drawn up from a customer's quote
 * request lives in {@code order}, which reads design's requests to draw it.
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.design.spi;
