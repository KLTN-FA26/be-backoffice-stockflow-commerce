package com.stockflow.fulfillment.internal.service;

import org.springframework.context.ApplicationContext;

/**
 * Stands in for the carrier's file host (G7): the proof of delivery a webhook points at is answered with these bytes,
 * never fetched over the network. For tests outside this package.
 */
public final class CarrierPodTestDownloads {

    private CarrierPodTestDownloads() {
    }

    /** Every address answers {@code bytes} as {@code contentType}, named {@code fileName}. */
    public static void answer(ApplicationContext context, byte[] bytes, String contentType, String fileName) {
        context.getBean(CarrierPodFetcher.class).fetchWith(uri -> new CarrierPodFetcher.Fetched(bytes, contentType,
                fileName));
    }
}
